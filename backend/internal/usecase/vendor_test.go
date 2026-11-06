package usecase_test

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"cameo/backend/internal/domain"
	"cameo/backend/internal/usecase"
)

// fakeVendor 記錄 Dispatch 呼叫；err 不為 nil 時回錯；onDispatch 可在呼叫中插入動作（模擬競爭）。
type fakeVendor struct {
	calls      []string
	err        error
	onDispatch func()
}

func (v *fakeVendor) Dispatch(_ context.Context, id string) error {
	v.calls = append(v.calls, id)
	if v.onDispatch != nil {
		v.onDispatch()
	}
	return v.err
}

// genEnv 建立一筆生成中的訂單（已扣款、有自拍檔）：期限為 base + 30 分。
func genEnv(status string) (*env, *fakeVendor) {
	e := newEnv(status)
	exp := base.Add(30 * time.Minute)
	e.db.cameo.GenerationExpiresAt = &exp
	e.db.balance = 90 // 已扣 10 點
	e.files.files["c-1"] = []byte("selfie")
	v := &fakeVendor{}
	e.svc.WithVendor(v)
	return e, v
}

func cb(id, status string) usecase.VendorEvent {
	return usecase.VendorEvent{EventID: id, JobID: "c-1", Status: status, VideoURL: "http://v/x.mp4"}
}

func strp(s string) *string { return &s }

func TestSubmitWritesGenerationExpiry(t *testing.T) {
	e := newEnv(domain.StatusUploaded)
	c, err := e.svc.SubmitCameo(context.Background(), "c-1")
	if err != nil {
		t.Fatal(err)
	}
	want := base.Add(30 * time.Minute)
	if c.GenerationExpiresAt == nil || !c.GenerationExpiresAt.Equal(want) || e.db.cameo.GenerationExpiresAt == nil || !e.db.cameo.GenerationExpiresAt.Equal(want) {
		t.Fatalf("expiry = %v / %v, want %v", c.GenerationExpiresAt, e.db.cameo.GenerationExpiresAt, want)
	}
	e = newEnv(domain.StatusUploaded)
	svc := usecase.NewService(fakeQueries{db: e.db}, fakeTxRunner{e.db, e.ev}, e.clock, usecase.Config{GenerationTTL: 5 * time.Minute}, e.files)
	if _, err := svc.SubmitCameo(context.Background(), "c-1"); err != nil {
		t.Fatal(err)
	}
	if got := e.db.cameo.GenerationExpiresAt; got == nil || !got.Equal(base.Add(5*time.Minute)) {
		t.Fatalf("custom ttl expiry = %v", got)
	}
}

func TestCallbackSucceeded(t *testing.T) {
	for _, st := range []string{domain.StatusGenerating, domain.StatusQueued} {
		e, _ := genEnv(st)
		if err := e.svc.HandleVendorCallback(context.Background(), cb("e1", "SUCCEEDED")); err != nil {
			t.Fatalf("%s: %v", st, err)
		}
		c := e.db.cameo
		if c.Status != domain.StatusSucceeded || c.PointsState != domain.PointsCharged || c.VideoURL == nil || *c.VideoURL != "http://v/x.mp4" {
			t.Fatalf("%s: cameo = %+v", st, c)
		}
		if e.db.balance != 90 || len(e.db.ledger) != 0 {
			t.Fatalf("%s: refunded on success: %d %v", st, e.db.balance, e.db.ledger)
		}
		if len(e.files.files) != 0 {
			t.Fatalf("%s: selfie not deleted", st)
		}
		if got := strings.Join(e.db.locks, ","); got != "account,cameo" {
			t.Fatalf("lock order = %s", got)
		}
		if c, d := e.ev.index("tx-commit"), e.ev.index("file-delete"); c < 0 || d < c {
			t.Fatalf("events = %v; want file-delete after tx-commit", e.ev.list)
		}
	}
}

func TestCallbackFailedRefundsOnceWithSnapshot(t *testing.T) {
	cases := []struct {
		name   string
		reason *string
		want   string
	}{
		{"content rejected", strp("CONTENT_REJECTED"), "CONTENT_REJECTED"},
		{"unknown reason", strp("GPU_ON_FIRE"), "VENDOR_FAILED"},
		{"nil reason", nil, "VENDOR_FAILED"},
		{"vendor failed", strp("VENDOR_FAILED"), "VENDOR_FAILED"},
		{"timeout from vendor is not trusted", strp("TIMEOUT"), "VENDOR_FAILED"},
	}
	for _, c := range cases {
		for _, st := range []string{domain.StatusGenerating, domain.StatusQueued} {
			e, _ := genEnv(st)
			e.db.cameo.Clip.Cost = 30 // 建單快照 30；目前片段價格不影響退款
			e.db.balance = 70
			ev := cb("e1", "FAILED")
			ev.Reason = c.reason
			if err := e.svc.HandleVendorCallback(context.Background(), ev); err != nil {
				t.Fatalf("%s/%s: %v", c.name, st, err)
			}
			got := e.db.cameo
			if got.Status != domain.StatusFailed || got.PointsState != domain.PointsRefunded || got.FailureReason == nil || *got.FailureReason != c.want {
				t.Fatalf("%s/%s: cameo = %+v", c.name, st, got)
			}
			if e.db.balance != 100 || len(e.db.ledger) != 1 {
				t.Fatalf("%s/%s: balance = %d ledger = %+v", c.name, st, e.db.balance, e.db.ledger)
			}
			l := e.db.ledger[0]
			if l.Type != domain.LedgerRefund || l.Amount != 30 || l.CameoID == nil || *l.CameoID != "c-1" || l.ClipTitle != "雨夜重逢" || !l.CreatedAt.Equal(base) {
				t.Fatalf("%s/%s: ledger = %+v", c.name, st, l)
			}
			if len(e.files.files) != 0 {
				t.Fatalf("%s/%s: selfie not deleted", c.name, st)
			}
			if got := strings.Join(e.db.locks, ","); got != "account,cameo" {
				t.Fatalf("lock order = %s", got)
			}
		}
	}
}

func TestCallbackDuplicateEventRefundsOnce(t *testing.T) {
	e, _ := genEnv(domain.StatusGenerating)
	for i := 0; i < 3; i++ {
		if err := e.svc.HandleVendorCallback(context.Background(), cb("same", "FAILED")); err != nil {
			t.Fatal(err)
		}
	}
	if e.db.balance != 100 || len(e.db.ledger) != 1 {
		t.Fatalf("balance = %d ledger = %d", e.db.balance, len(e.db.ledger))
	}
}

func TestCallbackUnknownJobRollsBackEvent(t *testing.T) {
	e, _ := genEnv(domain.StatusGenerating)
	ev := cb("e1", "SUCCEEDED")
	ev.JobID = "nope"
	if err := e.svc.HandleVendorCallback(context.Background(), ev); !errors.Is(err, domain.ErrCameoNotFound) {
		t.Fatalf("err = %v", err)
	}
	if len(e.db.events) != 0 {
		t.Fatalf("event kept: %v", e.db.events)
	}
	ev.JobID = "a\x00b"
	if err := e.svc.HandleVendorCallback(context.Background(), ev); !errors.Is(err, domain.ErrCameoNotFound) {
		t.Fatalf("NUL job: err = %v", err)
	}
}

func TestCallbackIgnoredInOtherStates(t *testing.T) {
	for _, st := range []string{domain.StatusAwaitingUpload, domain.StatusUploaded, domain.StatusSucceeded, domain.StatusFailed, domain.StatusExpired} {
		for _, status := range []string{"SUCCEEDED", "FAILED"} {
			e, _ := genEnv(st)
			before := e.db.cameo
			bal := e.db.balance
			if err := e.svc.HandleVendorCallback(context.Background(), cb("e1", status)); err != nil {
				t.Fatalf("%s/%s: %v", st, status, err)
			}
			if e.db.cameo.Status != before.Status || e.db.cameo.PointsState != before.PointsState || e.db.cameo.VideoURL != nil ||
				e.db.balance != bal || len(e.db.ledger) != 0 {
				t.Fatalf("%s/%s: changed: %+v balance %d ledger %v", st, status, e.db.cameo, e.db.balance, e.db.ledger)
			}
			if !e.db.events["e1"] {
				t.Fatalf("%s/%s: event not kept", st, status)
			}
			if len(e.files.deleted) != 0 {
				t.Fatalf("%s/%s: selfie deleted by ignored callback", st, status)
			}
		}
	}
}

// 回呼不比對期限：已過生成期限但還沒被掃描的訂單，先到的回呼為準。
func TestCallbackIgnoresDeadline(t *testing.T) {
	e, _ := genEnv(domain.StatusGenerating)
	e.clock.t = base.Add(2 * time.Hour)
	if err := e.svc.HandleVendorCallback(context.Background(), cb("e1", "SUCCEEDED")); err != nil {
		t.Fatal(err)
	}
	if e.db.cameo.Status != domain.StatusSucceeded {
		t.Fatalf("status = %s", e.db.cameo.Status)
	}
}

// 退款流程中途失敗：餘額、明細、事件、訂單全部還原，自拍不刪。
func TestCallbackFailureFlowIsAtomic(t *testing.T) {
	e, _ := genEnv(domain.StatusGenerating)
	e.db.failOn = "MarkFailed"
	if err := e.svc.HandleVendorCallback(context.Background(), cb("e1", "FAILED")); err == nil {
		t.Fatal("want error")
	}
	if e.db.balance != 90 || len(e.db.ledger) != 0 || len(e.db.events) != 0 || e.db.cameo.Status != domain.StatusGenerating {
		t.Fatalf("not rolled back: balance %d ledger %v events %v cameo %+v", e.db.balance, e.db.ledger, e.db.events, e.db.cameo)
	}
	if len(e.files.files) != 1 || len(e.files.deleted) != 0 {
		t.Fatal("selfie touched after rollback")
	}
	e.db.failOn = ""
	if err := e.svc.HandleVendorCallback(context.Background(), cb("e1", "FAILED")); err != nil || e.db.balance != 100 {
		t.Fatalf("retry: err = %v balance = %d", err, e.db.balance)
	}
}

func TestFailTimedOut(t *testing.T) {
	ctx := context.Background()
	deadline := base.Add(30 * time.Minute)
	for _, st := range []string{domain.StatusQueued, domain.StatusGenerating} {
		for _, c := range []struct {
			name string
			now  time.Time
			want bool
		}{
			{"1ns before", deadline.Add(-time.Nanosecond), false},
			{"at deadline", deadline, true},
			{"after", deadline.Add(time.Hour), true},
		} {
			e, _ := genEnv(st)
			e.clock.t = c.now
			ids, err := e.svc.FailTimedOut(ctx)
			if err != nil {
				t.Fatal(err)
			}
			if (len(ids) == 1) != c.want {
				t.Fatalf("%s/%s: ids = %v, want timed out = %v", st, c.name, ids, c.want)
			}
			if !c.want {
				if e.db.cameo.Status != st || e.db.balance != 90 || len(e.files.files) != 1 {
					t.Fatalf("%s/%s: touched", st, c.name)
				}
				continue
			}
			got := e.db.cameo
			if got.Status != domain.StatusFailed || got.PointsState != domain.PointsRefunded || got.FailureReason == nil || *got.FailureReason != "TIMEOUT" {
				t.Fatalf("%s/%s: cameo = %+v", st, c.name, got)
			}
			if e.db.balance != 100 || len(e.db.ledger) != 1 || e.db.ledger[0].Type != domain.LedgerRefund || e.db.ledger[0].Amount != 10 {
				t.Fatalf("%s/%s: balance %d ledger %v", st, c.name, e.db.balance, e.db.ledger)
			}
			if len(e.files.files) != 0 {
				t.Fatalf("%s/%s: selfie not deleted", st, c.name)
			}
			if cm, d := e.ev.index("tx-commit"), e.ev.index("file-delete"); cm < 0 || d < cm {
				t.Fatalf("events = %v", e.ev.list)
			}
			if got := strings.Join(e.db.locks, ","); got != "account,cameo" {
				t.Fatalf("lock order = %s", got)
			}
		}
	}
}

func TestFailTimedOutLeavesTerminalAlone(t *testing.T) {
	// 掃描查到後、鎖列前已變終態：交易內重查，不得再退款。
	e, _ := genEnv(domain.StatusGenerating)
	e.clock.t = base.Add(time.Hour)
	q := e.db
	q.cameo.Status = domain.StatusSucceeded // TimedOutIDs 不會回它；直接驗交易內重查需要 fakeQueries 回 id
	svc := usecase.NewService(staleQueries{fakeQueries{db: q}}, fakeTxRunner{q, e.ev}, e.clock, usecase.Config{}, e.files)
	ids, err := svc.FailTimedOut(context.Background())
	if err != nil || len(ids) != 0 || q.balance != 90 || len(q.ledger) != 0 || q.cameo.Status != domain.StatusSucceeded {
		t.Fatalf("ids = %v err = %v balance = %d ledger = %v status = %s", ids, err, q.balance, q.ledger, q.cameo.Status)
	}
}

// staleQueries 一律回傳訂單 id，模擬「查詢後狀態又變了」。
type staleQueries struct{ fakeQueries }

func (q staleQueries) TimedOutIDs(context.Context, time.Time) ([]string, error) {
	return []string{q.db.cameo.ID}, nil
}

func TestDispatchDue(t *testing.T) {
	ctx := context.Background()
	t.Run("success moves to generating", func(t *testing.T) {
		e, v := genEnv(domain.StatusQueued)
		ids, err := e.svc.DispatchDue(ctx)
		if err != nil || len(ids) != 1 || e.db.cameo.Status != domain.StatusGenerating || len(v.calls) != 1 || v.calls[0] != "c-1" {
			t.Fatalf("ids = %v err = %v status = %s calls = %v", ids, err, e.db.cameo.Status, v.calls)
		}
		if got := strings.Join(e.db.locks, ","); got != "account,cameo" {
			t.Fatalf("lock order = %s", got)
		}
	})
	t.Run("failure stays queued and retries", func(t *testing.T) {
		e, v := genEnv(domain.StatusQueued)
		v.err = errors.New("boom")
		ids, err := e.svc.DispatchDue(ctx)
		if err != nil || len(ids) != 0 || e.db.cameo.Status != domain.StatusQueued {
			t.Fatalf("ids = %v err = %v status = %s", ids, err, e.db.cameo.Status)
		}
		v.err = nil
		ids, err = e.svc.DispatchDue(ctx)
		if err != nil || len(ids) != 1 || e.db.cameo.Status != domain.StatusGenerating || len(v.calls) != 2 {
			t.Fatalf("retry: ids = %v err = %v status = %s calls = %v", ids, err, e.db.cameo.Status, v.calls)
		}
	})
	t.Run("callback first is not overwritten", func(t *testing.T) {
		e, v := genEnv(domain.StatusQueued)
		v.onDispatch = func() {
			if err := e.svc.HandleVendorCallback(ctx, cb("e1", "SUCCEEDED")); err != nil {
				t.Error(err)
			}
		}
		ids, err := e.svc.DispatchDue(ctx)
		if err != nil || len(ids) != 0 || e.db.cameo.Status != domain.StatusSucceeded {
			t.Fatalf("ids = %v err = %v status = %s", ids, err, e.db.cameo.Status)
		}
	})
	t.Run("expired is not dispatched", func(t *testing.T) {
		e, v := genEnv(domain.StatusQueued)
		e.clock.t = base.Add(30 * time.Minute) // 期限當下
		ids, err := e.svc.DispatchDue(ctx)
		if err != nil || len(ids) != 0 || len(v.calls) != 0 || e.db.cameo.Status != domain.StatusQueued {
			t.Fatalf("ids = %v err = %v calls = %v", ids, err, v.calls)
		}
	})
	t.Run("generating is not dispatched again", func(t *testing.T) {
		e, v := genEnv(domain.StatusGenerating)
		if ids, err := e.svc.DispatchDue(ctx); err != nil || len(ids) != 0 || len(v.calls) != 0 {
			t.Fatalf("ids = %v err = %v calls = %v", ids, err, v.calls)
		}
	})
}
