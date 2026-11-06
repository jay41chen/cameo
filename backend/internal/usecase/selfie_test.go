package usecase_test

import (
	"bytes"
	"context"
	"errors"
	"io"
	"strings"
	"testing"
	"time"

	"cameo/backend/internal/domain"
	"cameo/backend/internal/usecase"
)

// 以下是記憶體版的 TxRunner／Tx／FileStore，只用來測狀態與期限的各分支（不需資料庫）。

var base = time.Date(2026, 11, 6, 2, 0, 0, 0, time.UTC)

type fakeDB struct {
	cameo   domain.Cameo
	exists  bool
	balance int
	ledger  []domain.LedgerEntry
	locks   []string
}

// events 依序記錄交易 commit 與檔案操作，用來驗證「rename 在交易內、刪檔在 commit 之後」。
type events struct{ list []string }

func (e *events) add(s string) { e.list = append(e.list, s) }

// index 回傳第一個等於 name 的位置，找不到回 -1。
func (e *events) index(name string) int {
	for i, s := range e.list {
		if s == name {
			return i
		}
	}
	return -1
}

type fakeTxRunner struct {
	db *fakeDB
	ev *events
}

// Run 模擬交易：fn 回傳錯誤就還原狀態（rollback）。
func (r fakeTxRunner) Run(_ context.Context, fn func(usecase.Tx) error) error {
	saved := *r.db
	saved.ledger = append([]domain.LedgerEntry(nil), r.db.ledger...)
	if err := fn(r.db); err != nil {
		*r.db = saved
		r.ev.add("tx-rollback")
		return err
	}
	r.ev.add("tx-commit")
	return nil
}

func (d *fakeDB) LockAccount(context.Context) (int, error) {
	d.locks = append(d.locks, "account")
	return d.balance, nil
}
func (d *fakeDB) CameoByKey(context.Context, string) (domain.Cameo, bool, error) {
	return domain.Cameo{}, false, nil
}
func (d *fakeDB) Clip(context.Context, string) (domain.Clip, bool, error) {
	return domain.Clip{}, false, nil
}
func (d *fakeDB) HeldPoints(context.Context) (int, error)                      { return 0, nil }
func (d *fakeDB) QuotaUsed(context.Context, time.Time, time.Time) (int, error) { return 0, nil }
func (d *fakeDB) InsertCameo(context.Context, domain.Cameo) (string, error)    { return "", nil }
func (d *fakeDB) ExpireDue(_ context.Context, now time.Time) ([]string, error) {
	ok, _ := d.ExpireCameo(context.Background(), d.cameo.ID, now)
	if ok {
		return []string{d.cameo.ID}, nil
	}
	return nil, nil
}
func (d *fakeDB) CameoForUpdate(_ context.Context, id string) (domain.Cameo, bool, error) {
	d.locks = append(d.locks, "cameo")
	if !d.exists || d.cameo.ID != id {
		return domain.Cameo{}, false, nil
	}
	return d.cameo, true, nil
}

// ExpireCameo 的規則與 SQL 相同：upload_expires_at <= now。
func (d *fakeDB) ExpireCameo(_ context.Context, id string, now time.Time) (bool, error) {
	c := &d.cameo
	if c.ID != id || (c.Status != domain.StatusAwaitingUpload && c.Status != domain.StatusUploaded) || c.UploadExpiresAt.After(now) {
		return false, nil
	}
	c.Status, c.PointsState = domain.StatusExpired, domain.PointsReleased
	return true, nil
}
func (d *fakeDB) MarkUploaded(_ context.Context, _ string, _ time.Time) error {
	d.cameo.Status = domain.StatusUploaded
	return nil
}
func (d *fakeDB) DebitAccount(_ context.Context, amount int) error {
	d.balance -= amount
	return nil
}
func (d *fakeDB) InsertLedger(_ context.Context, e domain.LedgerEntry) error {
	d.ledger = append(d.ledger, e)
	return nil
}
func (d *fakeDB) MarkQueued(context.Context, string, time.Time) error {
	d.cameo.Status, d.cameo.PointsState = domain.StatusQueued, domain.PointsCharged
	return nil
}

type fakeFiles struct {
	temps   map[string][]byte
	files   map[string][]byte
	deleted []string
	n       int
	ev      *events
}

func newFakeFiles(ev *events) *fakeFiles {
	return &fakeFiles{temps: map[string][]byte{}, files: map[string][]byte{}, ev: ev}
}

// ctxReader 與真實 filestore 一樣：ctx 取消時中止讀取。
type ctxReader struct {
	ctx context.Context
	r   io.Reader
}

func (c ctxReader) Read(p []byte) (int, error) {
	if err := c.ctx.Err(); err != nil {
		return 0, err
	}
	return c.r.Read(p)
}

func (f *fakeFiles) WriteTemp(ctx context.Context, r io.Reader) (string, error) {
	b, err := io.ReadAll(ctxReader{ctx, r})
	if err != nil {
		return "", err
	}
	f.n++
	name := ".tmp-" + string(rune('a'+f.n))
	f.temps[name] = b
	return name, nil
}
func (f *fakeFiles) Commit(temp, id string) error {
	f.ev.add("file-commit")
	f.files[id] = f.temps[temp]
	delete(f.temps, temp)
	return nil
}
func (f *fakeFiles) Discard(temp string) error { delete(f.temps, temp); return nil }
func (f *fakeFiles) Delete(id string) error {
	f.ev.add("file-delete")
	f.deleted = append(f.deleted, id)
	delete(f.files, id)
	return nil
}

type fakeClock struct{ t time.Time }

func (c *fakeClock) Now() time.Time { return c.t }

// fakeQueries 讓 UploadSelfie 的第一步（不鎖列的預檢）讀到 fakeDB 的訂單。
type fakeQueries struct {
	emptyQueries
	db *fakeDB
}

func (q fakeQueries) CameoByID(_ context.Context, id string) (domain.Cameo, error) {
	if !q.db.exists || q.db.cameo.ID != id {
		return domain.Cameo{}, domain.ErrCameoNotFound
	}
	return q.db.cameo, nil
}

type env struct {
	svc   *usecase.Service
	db    *fakeDB
	files *fakeFiles
	clock *fakeClock
	ev    *events
}

func newEnv(status string) *env {
	db := &fakeDB{exists: true, balance: 100, cameo: domain.Cameo{
		ID: "c-1", Status: status, PointsState: domain.PointsHeld,
		Clip:            domain.Clip{ID: "c1", Title: "雨夜重逢", Cost: 10},
		UploadExpiresAt: base.Add(time.Hour),
	}}
	if status == domain.StatusExpired {
		db.cameo.PointsState = domain.PointsReleased
	}
	if status == domain.StatusQueued || status == domain.StatusGenerating || status == domain.StatusSucceeded || status == domain.StatusFailed {
		db.cameo.PointsState = domain.PointsCharged
	}
	ev := &events{}
	files := newFakeFiles(ev)
	clock := &fakeClock{t: base}
	svc := usecase.NewService(fakeQueries{db: db}, fakeTxRunner{db, ev}, clock, usecase.Config{}, files)
	return &env{svc, db, files, clock, ev}
}

var jpeg = []byte{0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3}

func TestUploadBranches(t *testing.T) {
	ctx := context.Background()
	t.Run("awaiting becomes uploaded", func(t *testing.T) {
		e := newEnv(domain.StatusAwaitingUpload)
		c, err := e.svc.UploadSelfie(ctx, "c-1", bytes.NewReader(jpeg))
		if err != nil || c.Status != domain.StatusUploaded || e.db.cameo.Status != domain.StatusUploaded {
			t.Fatalf("c = %+v err = %v db = %+v", c, err, e.db.cameo)
		}
		if !bytes.Equal(e.files.files["c-1"], jpeg) || len(e.files.temps) != 0 {
			t.Fatalf("files = %v temps = %v", e.files.files, e.files.temps)
		}
	})
	t.Run("uploaded is overwritten", func(t *testing.T) {
		e := newEnv(domain.StatusUploaded)
		e.files.files["c-1"] = []byte("old")
		if _, err := e.svc.UploadSelfie(ctx, "c-1", bytes.NewReader(jpeg)); err != nil {
			t.Fatal(err)
		}
		if !bytes.Equal(e.files.files["c-1"], jpeg) {
			t.Fatalf("file = %q", e.files.files["c-1"])
		}
	})
	t.Run("not found", func(t *testing.T) {
		e := newEnv(domain.StatusAwaitingUpload)
		if _, err := e.svc.UploadSelfie(ctx, "nope", bytes.NewReader(jpeg)); !errors.Is(err, domain.ErrCameoNotFound) {
			t.Fatalf("err = %v", err)
		}
	})
	for _, st := range []string{domain.StatusQueued, domain.StatusGenerating, domain.StatusSucceeded, domain.StatusFailed} {
		t.Run("invalid state "+st, func(t *testing.T) {
			e := newEnv(st)
			if _, err := e.svc.UploadSelfie(ctx, "c-1", bytes.NewReader(jpeg)); !errors.Is(err, domain.ErrInvalidState) {
				t.Fatalf("err = %v", err)
			}
			if len(e.files.temps) != 0 || len(e.files.files) != 0 {
				t.Fatal("files written")
			}
		})
	}
	t.Run("already expired", func(t *testing.T) {
		e := newEnv(domain.StatusExpired)
		if _, err := e.svc.UploadSelfie(ctx, "c-1", bytes.NewReader(jpeg)); !errors.Is(err, domain.ErrUploadExpired) {
			t.Fatalf("err = %v", err)
		}
	})
	t.Run("overdue but not swept releases and commits", func(t *testing.T) {
		for _, st := range []string{domain.StatusAwaitingUpload, domain.StatusUploaded} {
			e := newEnv(st)
			e.files.files["c-1"] = []byte("old")
			e.clock.t = base.Add(2 * time.Hour)
			if _, err := e.svc.UploadSelfie(ctx, "c-1", bytes.NewReader(jpeg)); !errors.Is(err, domain.ErrUploadExpired) {
				t.Fatalf("%s: err = %v", st, err)
			}
			if e.db.cameo.Status != domain.StatusExpired || e.db.cameo.PointsState != domain.PointsReleased {
				t.Fatalf("%s: release was rolled back: %+v", st, e.db.cameo)
			}
			if len(e.files.files) != 0 || len(e.files.temps) != 0 {
				t.Fatalf("%s: files left: %v %v", st, e.files.files, e.files.temps)
			}
		}
	})
}

func TestUploadContentRules(t *testing.T) {
	ctx := context.Background()
	for name, body := range map[string]string{
		"empty":     "",
		"short":     "\xff\xd8",
		"png":       "\x89PNG\r\n",
		"text":      "hello",
		"wrong 3rd": "\xff\xd8\x00rest",
	} {
		e := newEnv(domain.StatusAwaitingUpload)
		_, err := e.svc.UploadSelfie(ctx, "c-1", strings.NewReader(body))
		if !errors.Is(err, domain.ErrInvalidSelfie) {
			t.Errorf("%s: err = %v", name, err)
		}
		if e.db.cameo.Status != domain.StatusAwaitingUpload || len(e.files.temps) != 0 || len(e.files.files) != 0 {
			t.Errorf("%s: state changed", name)
		}
	}
}

type brokenBody struct {
	data []byte
	err  error
	pos  int
}

func (b *brokenBody) Read(p []byte) (int, error) {
	if b.pos >= len(b.data) {
		return 0, b.err
	}
	n := copy(p, b.data[b.pos:])
	b.pos += n
	return n, nil
}

func TestUploadBodyReadFailure(t *testing.T) {
	ctx := context.Background()
	for name, data := range map[string][]byte{"in head": {0xFF}, "after head": jpeg} {
		e := newEnv(domain.StatusAwaitingUpload)
		cause := io.ErrUnexpectedEOF
		_, err := e.svc.UploadSelfie(ctx, "c-1", &brokenBody{data: data, err: cause})
		var be *usecase.BodyReadError
		if !errors.As(err, &be) || !errors.Is(err, cause) {
			t.Errorf("%s: err = %v", name, err)
		}
		if e.db.cameo.Status != domain.StatusAwaitingUpload || len(e.files.temps) != 0 || len(e.files.files) != 0 {
			t.Errorf("%s: state changed", name)
		}
	}
}

func TestSubmitBranches(t *testing.T) {
	ctx := context.Background()
	t.Run("uploaded charges once", func(t *testing.T) {
		e := newEnv(domain.StatusUploaded)
		c, err := e.svc.SubmitCameo(ctx, "c-1")
		if err != nil || c.Status != domain.StatusQueued || c.PointsState != domain.PointsCharged {
			t.Fatalf("c = %+v err = %v", c, err)
		}
		if e.db.balance != 90 || len(e.db.ledger) != 1 {
			t.Fatalf("balance = %d ledger = %+v", e.db.balance, e.db.ledger)
		}
		l := e.db.ledger[0]
		if l.Type != domain.LedgerCharge || l.Amount != 10 || *l.CameoID != "c-1" || l.ClipTitle != "雨夜重逢" || !l.CreatedAt.Equal(base) {
			t.Fatalf("ledger = %+v", l)
		}
		if got := strings.Join(e.db.locks, ","); got != "account,cameo" {
			t.Fatalf("lock order = %s", got)
		}
	})
	t.Run("uses cost snapshot", func(t *testing.T) {
		e := newEnv(domain.StatusUploaded)
		e.db.cameo.Clip.Cost = 30
		if _, err := e.svc.SubmitCameo(ctx, "c-1"); err != nil || e.db.balance != 70 || e.db.ledger[0].Amount != 30 {
			t.Fatalf("balance = %d ledger = %+v err = %v", e.db.balance, e.db.ledger, err)
		}
	})
	for _, st := range []string{domain.StatusQueued, domain.StatusGenerating, domain.StatusSucceeded, domain.StatusFailed} {
		t.Run("idempotent "+st, func(t *testing.T) {
			e := newEnv(st)
			c, err := e.svc.SubmitCameo(ctx, "c-1")
			if err != nil || c.Status != st || e.db.balance != 100 || len(e.db.ledger) != 0 {
				t.Fatalf("c = %+v err = %v balance = %d ledger = %v", c, err, e.db.balance, e.db.ledger)
			}
		})
	}
	t.Run("awaiting upload", func(t *testing.T) {
		e := newEnv(domain.StatusAwaitingUpload)
		if _, err := e.svc.SubmitCameo(ctx, "c-1"); !errors.Is(err, domain.ErrSelfieNotUploaded) {
			t.Fatalf("err = %v", err)
		}
		if e.db.balance != 100 || len(e.db.ledger) != 0 {
			t.Fatal("charged")
		}
	})
	t.Run("not found", func(t *testing.T) {
		e := newEnv(domain.StatusUploaded)
		for _, id := range []string{"nope", "a\x00b"} {
			if _, err := e.svc.SubmitCameo(ctx, id); !errors.Is(err, domain.ErrCameoNotFound) {
				t.Fatalf("%q: err = %v", id, err)
			}
		}
	})
	t.Run("expired state", func(t *testing.T) {
		e := newEnv(domain.StatusExpired)
		if _, err := e.svc.SubmitCameo(ctx, "c-1"); !errors.Is(err, domain.ErrUploadExpired) {
			t.Fatalf("err = %v", err)
		}
	})
	t.Run("overdue is checked before upload state and release commits", func(t *testing.T) {
		for _, st := range []string{domain.StatusAwaitingUpload, domain.StatusUploaded} {
			e := newEnv(st)
			e.files.files["c-1"] = []byte("x")
			e.clock.t = base.Add(2 * time.Hour)
			if _, err := e.svc.SubmitCameo(ctx, "c-1"); !errors.Is(err, domain.ErrUploadExpired) {
				t.Fatalf("%s: err = %v", st, err)
			}
			if e.db.cameo.Status != domain.StatusExpired || e.db.cameo.PointsState != domain.PointsReleased {
				t.Fatalf("%s: release rolled back: %+v", st, e.db.cameo)
			}
			if e.db.balance != 100 || len(e.db.ledger) != 0 || len(e.files.files) != 0 {
				t.Fatalf("%s: balance/ledger/files wrong", st)
			}
		}
	})
}

// 邊界：upload_expires_at 當下就算逾期（<=），前 1ns 還沒。上傳、送出、掃描三條路徑規則一致。
func TestExpiryBoundaryIsSameForAllPaths(t *testing.T) {
	ctx := context.Background()
	deadline := base.Add(time.Hour)
	for _, c := range []struct {
		name string
		now  time.Time
		want bool
	}{
		{"1ns before", deadline.Add(-time.Nanosecond), false},
		{"at deadline", deadline, true},
		{"1ns after", deadline.Add(time.Nanosecond), true},
	} {
		check := func(path string, expired bool) {
			if expired != c.want {
				t.Errorf("%s/%s: expired = %v, want %v", c.name, path, expired, c.want)
			}
		}
		e := newEnv(domain.StatusUploaded)
		e.clock.t = c.now
		_, err := e.svc.UploadSelfie(ctx, "c-1", bytes.NewReader(jpeg))
		check("upload", errors.Is(err, domain.ErrUploadExpired))

		e = newEnv(domain.StatusUploaded)
		e.clock.t = c.now
		_, err = e.svc.SubmitCameo(ctx, "c-1")
		check("submit", errors.Is(err, domain.ErrUploadExpired))

		e = newEnv(domain.StatusUploaded)
		e.clock.t = c.now
		ids, err := e.svc.ExpireDue(ctx)
		if err != nil {
			t.Fatal(err)
		}
		check("sweep", len(ids) == 1)
	}
}

// 順序：刪檔一定在 DB 交易 commit 之後（交易失敗不會誤刪），
// 上傳的 rename（FileStore.Commit）一定在交易內、commit 之前（rename 失敗會讓整筆還原）。
func TestFileOperationOrderRelativeToTxCommit(t *testing.T) {
	ctx := context.Background()
	overdue := base.Add(2 * time.Hour)
	wantDeleteAfterCommit := func(t *testing.T, e *env) {
		t.Helper()
		c, d := e.ev.index("tx-commit"), e.ev.index("file-delete")
		if c < 0 || d < 0 || d < c {
			t.Fatalf("events = %v; want file-delete after tx-commit", e.ev.list)
		}
		if e.ev.index("file-commit") != -1 {
			t.Fatalf("events = %v; unexpected file-commit", e.ev.list)
		}
	}
	t.Run("sweep deletes after commit", func(t *testing.T) {
		e := newEnv(domain.StatusUploaded)
		e.files.files["c-1"] = []byte("x")
		e.clock.t = overdue
		ids, err := e.svc.ExpireDue(ctx)
		if err != nil || len(ids) != 1 || ids[0] != "c-1" || len(e.files.files) != 0 {
			t.Fatalf("ids = %v err = %v files = %v", ids, err, e.files.files)
		}
		wantDeleteAfterCommit(t, e)
	})
	t.Run("overdue upload deletes after commit", func(t *testing.T) {
		e := newEnv(domain.StatusUploaded)
		e.files.files["c-1"] = []byte("x")
		e.clock.t = overdue
		if _, err := e.svc.UploadSelfie(ctx, "c-1", bytes.NewReader(jpeg)); !errors.Is(err, domain.ErrUploadExpired) {
			t.Fatalf("err = %v", err)
		}
		wantDeleteAfterCommit(t, e)
	})
	t.Run("overdue submit deletes after commit", func(t *testing.T) {
		e := newEnv(domain.StatusUploaded)
		e.files.files["c-1"] = []byte("x")
		e.clock.t = overdue
		if _, err := e.svc.SubmitCameo(ctx, "c-1"); !errors.Is(err, domain.ErrUploadExpired) {
			t.Fatalf("err = %v", err)
		}
		wantDeleteAfterCommit(t, e)
	})
	t.Run("upload renames inside the transaction", func(t *testing.T) {
		e := newEnv(domain.StatusAwaitingUpload)
		if _, err := e.svc.UploadSelfie(ctx, "c-1", bytes.NewReader(jpeg)); err != nil {
			t.Fatal(err)
		}
		f, c := e.ev.index("file-commit"), e.ev.index("tx-commit")
		if f < 0 || c < 0 || f > c {
			t.Fatalf("events = %v; want file-commit before tx-commit", e.ev.list)
		}
		if e.ev.index("file-delete") != -1 {
			t.Fatalf("events = %v; unexpected file-delete", e.ev.list)
		}
	})
}

// 讀 body 期間 ctx 取消，一律是 BodyReadError，暫存檔不留、狀態不變。
func TestUploadContextCancelledWhileReadingIsBodyReadError(t *testing.T) {
	e := newEnv(domain.StatusAwaitingUpload)
	ctx, cancel := context.WithCancel(context.Background())
	body := &cancelOnRead{cancel: cancel, data: jpeg}
	_, err := e.svc.UploadSelfie(ctx, "c-1", body)
	var be *usecase.BodyReadError
	if !errors.As(err, &be) || !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v, want BodyReadError wrapping context.Canceled", err)
	}
	if e.db.cameo.Status != domain.StatusAwaitingUpload || len(e.files.temps) != 0 || len(e.files.files) != 0 {
		t.Fatal("state changed or files left")
	}
	if e.ev.index("tx-commit") != -1 {
		t.Fatalf("events = %v", e.ev.list)
	}
}

// cancelOnRead 讀完 data 之後（標頭已通過檢查）取消 ctx，並繼續回傳資料，
// 模擬 client 在串流中途斷線、body 本身卻沒有回錯。
type cancelOnRead struct {
	cancel context.CancelFunc
	data   []byte
	pos    int
}

func (c *cancelOnRead) Read(p []byte) (int, error) {
	if c.pos >= len(c.data) {
		return 0, io.EOF
	}
	n := copy(p, c.data[c.pos:])
	c.pos += n
	c.cancel()
	return n, nil
}
