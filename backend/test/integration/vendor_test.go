package integration_test

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"cameo/backend/internal/adapter/filestore"
	apihttp "cameo/backend/internal/adapter/http"
	"cameo/backend/internal/adapter/postgres"
	"cameo/backend/internal/domain"
	"cameo/backend/internal/usecase"
	"cameo/backend/test/internal/testdb"
)

const webhookSecret = "test-webhook-secret"

func sign(secret string, body []byte) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(body)
	return hex.EncodeToString(mac.Sum(nil))
}

// recordingVendor 記錄派工；err 不為 nil 時回錯；hook 可在派工當下插入動作（模擬競爭）。
type recordingVendor struct {
	mu    sync.Mutex
	calls []string
	err   error
	hook  func()
}

func (v *recordingVendor) Dispatch(_ context.Context, id string) error {
	v.mu.Lock()
	v.calls = append(v.calls, id)
	err, hook := v.err, v.hook
	v.mu.Unlock()
	if hook != nil {
		hook()
	}
	return err
}

func (v *recordingVendor) set(err error, hook func()) {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.err, v.hook = err, hook
}

func (v *recordingVendor) callList() []string {
	v.mu.Lock()
	defer v.mu.Unlock()
	return append([]string(nil), v.calls...)
}

// vfx 是廠商回呼測試的環境：真實 Postgres、真實檔案目錄、帶 webhook 的 HTTP handler。
type vfx struct {
	fx
	vendor *recordingVendor
}

func newVfx(t *testing.T) *vfx {
	t.Helper()
	f := newFx(t, 0)
	v := &recordingVendor{}
	f.svc = newSvc(f.pool, f.clock, usecase.Config{DailyLimit: 1000, UploadTTL: ttl, GenerationTTL: 30 * time.Minute}, newFiles2(t, f.dir)).WithVendor(v)
	f.h = apihttp.Handler(f.svc, apihttp.WithVendorWebhook([]byte(webhookSecret)))
	return &vfx{fx: *f, vendor: v}
}

func newFiles2(t *testing.T, dir string) *filestore.Local {
	t.Helper()
	fs, err := filestore.New(dir)
	if err != nil {
		t.Fatal(err)
	}
	return fs
}

// queued 建立一筆已上傳、已送出（QUEUED）的訂單。
func (f *vfx) queued(t *testing.T, key string) string {
	t.Helper()
	id := f.createUploaded(t, key)
	if r := f.submit(id); r.Code != 200 {
		t.Fatalf("submit = %d %v", r.Code, r.body)
	}
	return id
}

// generating 建立一筆 GENERATING 的訂單（經 DispatchDue）。
func (f *vfx) generating(t *testing.T, key string) string {
	t.Helper()
	id := f.queued(t, key)
	ids, err := f.svc.DispatchDue(context.Background())
	if err != nil || len(ids) == 0 {
		t.Fatalf("dispatch: %v %v", ids, err)
	}
	if st, _ := f.state(t, id); st != "GENERATING" {
		t.Fatalf("status = %s", st)
	}
	return id
}

func (f *vfx) webhook(body string, sig string) resp {
	hdr := map[string]string{}
	if sig != "" {
		hdr["X-Signature"] = sig
	}
	return send(f.h, http.MethodPost, "/webhooks/vendor", []byte(body), false, hdr)
}

// signed 用正確的密鑰簽章並送出。
func (f *vfx) signed(body string) resp { return f.webhook(body, sign(webhookSecret, []byte(body))) }

func okBody(event, job string) string {
	return fmt.Sprintf(`{"event_id":%q,"job_id":%q,"status":"SUCCEEDED","video_url":"http://vendor-sim:9000/videos/%s.mp4","reason":null}`, event, job, job)
}

func failBody(event, job string, reason string) string {
	r := "null"
	if reason != "" {
		r = fmt.Sprintf("%q", reason)
	}
	return fmt.Sprintf(`{"event_id":%q,"job_id":%q,"status":"FAILED","video_url":null,"reason":%s}`, event, job, r)
}

func (f *vfx) row(t *testing.T, id string) (status, ps string, reason, video *string) {
	t.Helper()
	if err := f.pool.QueryRow(context.Background(),
		`SELECT status, points_state, failure_reason, video_url FROM cameos WHERE id = $1`, id).Scan(&status, &ps, &reason, &video); err != nil {
		t.Fatal(err)
	}
	return
}

func (f *vfx) ledgerCount(t *testing.T, typ string) int {
	t.Helper()
	var n int
	if err := f.pool.QueryRow(context.Background(), `SELECT count(*) FROM ledger_entries WHERE type = $1`, typ).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func (f *vfx) wantNothingChanged(t *testing.T, id, status string, balance, charges, refunds, events int) {
	t.Helper()
	if st, _, _, v := f.row(t, id); st != status || v != nil {
		t.Fatalf("status = %s video = %v, want %s", st, v, status)
	}
	if b := f.balance(t); b != balance {
		t.Fatalf("balance = %d, want %d", b, balance)
	}
	if c, r, e := f.ledgerCount(t, "CHARGE"), f.ledgerCount(t, "REFUND"), count(t, f.pool, "vendor_events"); c != charges || r != refunds || e != events {
		t.Fatalf("charges/refunds/events = %d/%d/%d, want %d/%d/%d", c, r, e, charges, refunds, events)
	}
}

func TestVendorSucceededFlow(t *testing.T) {
	f := newVfx(t)
	id := f.queued(t, "k")
	if _, ok := f.selfie(t, id); !ok {
		t.Fatal("selfie missing before callback")
	}
	if ids, err := f.svc.DispatchDue(context.Background()); err != nil || len(ids) != 1 || ids[0] != id {
		t.Fatalf("dispatch = %v %v", ids, err)
	}
	if got := f.vendor.callList(); len(got) != 1 || got[0] != id {
		t.Fatalf("vendor calls = %v", got)
	}
	// 回呼前：GENERATING 的顯示文字。
	g := call(t, f.h, http.MethodGet, "/v1/cameos/"+id, "", "")
	if cameoOf(g)["display"].(map[string]any)["title"] != "生成中" {
		t.Fatalf("generating = %v", cameoOf(g))
	}

	if r := f.signed(okBody("e1", id)); r.Code != 200 {
		t.Fatalf("webhook = %d %s", r.Code, r.Body.String())
	}
	st, ps, reason, video := f.row(t, id)
	if st != "SUCCEEDED" || ps != "CHARGED" || reason != nil || video == nil || *video != "http://vendor-sim:9000/videos/"+id+".mp4" {
		t.Fatalf("row = %s %s %v %v", st, ps, reason, video)
	}
	if b := f.balance(t); b != 90 || f.ledgerCount(t, "CHARGE") != 1 || f.ledgerCount(t, "REFUND") != 0 {
		t.Fatalf("balance %d / ledger wrong", b)
	}
	f.wantNoFiles(t)
	g = call(t, f.h, http.MethodGet, "/v1/cameos/"+id, "", "")
	c := cameoOf(g)
	if c["status"] != "SUCCEEDED" || c["points_state"] != "CHARGED" || c["video_available"] != false || c["failure_reason"] != nil {
		t.Fatalf("cameo = %v", c)
	}
	if d := c["display"].(map[string]any); d["title"] != "已完成" || d["message"] != "你的客串短片完成了" {
		t.Fatalf("display = %v", d)
	}
	if strings.Contains(g.Body.String(), "video_url") || strings.Contains(g.Body.String(), "videos/") {
		t.Fatalf("video_url leaked: %s", g.Body.String())
	}
}

func TestVendorFailedRefunds(t *testing.T) {
	for _, c := range []struct {
		name, reason, want string
		quotaRestored      bool
		title, message     string
	}{
		{"content rejected", "CONTENT_REJECTED", "CONTENT_REJECTED", false, "無法生成", "照片未通過審核，點數已退還，今日次數不退還"},
		{"unknown reason", "GPU_ON_FIRE", "VENDOR_FAILED", true, "生成失敗", "點數已退還"},
		{"null reason", "", "VENDOR_FAILED", true, "生成失敗", "點數已退還"},
	} {
		t.Run(c.name, func(t *testing.T) {
			f := newVfx(t)
			id := f.generating(t, "k")
			if q := quota(t, f.svc); q.Remaining != 999 {
				t.Fatalf("quota before = %d", q.Remaining)
			}
			if r := f.signed(failBody("e1", id, c.reason)); r.Code != 200 {
				t.Fatalf("webhook = %d %s", r.Code, r.Body.String())
			}
			st, ps, reason, video := f.row(t, id)
			if st != "FAILED" || ps != "REFUNDED" || reason == nil || *reason != c.want || video != nil {
				t.Fatalf("row = %s %s %v %v", st, ps, reason, video)
			}
			if b := f.balance(t); b != 100 {
				t.Fatalf("balance = %d", b)
			}
			var typ, title string
			var amount int
			var cameoID *string
			var at time.Time
			if err := f.pool.QueryRow(context.Background(), `SELECT type, amount, cameo_id, clip_title, created_at FROM ledger_entries WHERE type = 'REFUND'`).
				Scan(&typ, &amount, &cameoID, &title, &at); err != nil {
				t.Fatal(err)
			}
			if amount != 10 || cameoID == nil || *cameoID != id || title != "雨夜重逢" || !at.Equal(t0) {
				t.Fatalf("refund = %d %v %s %v", amount, cameoID, title, at)
			}
			if f.ledgerCount(t, "REFUND") != 1 || f.ledgerCount(t, "CHARGE") != 1 {
				t.Fatal("ledger rows wrong")
			}
			wantRemaining := 999
			if c.quotaRestored {
				wantRemaining = 1000
			}
			if q := quota(t, f.svc); q.Remaining != wantRemaining {
				t.Fatalf("quota = %d, want %d", q.Remaining, wantRemaining)
			}
			if p := points(t, f.svc); p.Available != 100 || p.Held != 0 {
				t.Fatalf("points = %+v", p)
			}
			f.wantNoFiles(t)
			g := cameoOf(call(t, f.h, http.MethodGet, "/v1/cameos/"+id, "", ""))
			if d := g["display"].(map[string]any); d["title"] != c.title || d["message"] != c.message || g["failure_reason"] != c.want {
				t.Fatalf("cameo = %v", g)
			}
		})
	}
}

// 回呼先於「接受」寫入：QUEUED 直接被回呼完成。
func TestVendorCallbackWhileQueued(t *testing.T) {
	f := newVfx(t)
	id := f.queued(t, "k")
	if r := f.signed(failBody("e1", id, "")); r.Code != 200 {
		t.Fatalf("webhook = %d", r.Code)
	}
	if st, ps, _, _ := f.row(t, id); st != "FAILED" || ps != "REFUNDED" || f.balance(t) != 100 {
		t.Fatalf("row = %s %s balance %d", st, ps, f.balance(t))
	}
	id2 := f.queued(t, "k2")
	if r := f.signed(okBody("e2", id2)); r.Code != 200 {
		t.Fatalf("webhook = %d", r.Code)
	}
	if st, _, _, v := f.row(t, id2); st != "SUCCEEDED" || v == nil {
		t.Fatalf("status = %s", st)
	}
}

func TestVendorRefundUsesCostSnapshot(t *testing.T) {
	f := newVfx(t)
	id := f.generating(t, "k")
	if _, err := f.pool.Exec(context.Background(), `UPDATE clips SET cost = 50 WHERE id = 'c1'`); err != nil {
		t.Fatal(err)
	}
	if r := f.signed(failBody("e1", id, "")); r.Code != 200 {
		t.Fatalf("webhook = %d", r.Code)
	}
	var amount int
	if err := f.pool.QueryRow(context.Background(), `SELECT amount FROM ledger_entries WHERE type = 'REFUND'`).Scan(&amount); err != nil || amount != 10 {
		t.Fatalf("refund amount = %d err = %v", amount, err)
	}
	if b := f.balance(t); b != 100 {
		t.Fatalf("balance = %d", b)
	}
}

func TestVendorWebhookSignature(t *testing.T) {
	f := newVfx(t)
	id := f.generating(t, "k")
	body := failBody("e1", id, "")
	tampered := strings.Replace(body, `"FAILED"`, `"SUCCEEDED"`, 1)
	good := sign(webhookSecret, []byte(body))
	cases := []struct{ name, body, signature string }{
		{"wrong secret", body, sign("other-secret", []byte(body))},
		{"empty secret", body, sign("", []byte(body))},
		{"missing", body, ""},
		{"tampered body", tampered, good},
		{"not hex", body, "zzzz"},
		{"truncated", body, good[:len(good)-2]},
		{"sha256 prefix", body, "sha256=" + good},
		{"last byte flipped", body, flipLastHexByte(good)},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			r := f.webhook(c.body, c.signature)
			wantError(t, r, 401, "INVALID_SIGNATURE")
			if r.body["retryable"] != false {
				t.Errorf("retryable = %v", r.body["retryable"])
			}
			f.wantNothingChanged(t, id, "GENERATING", 90, 1, 0, 0)
		})
	}
	// 簽章對、body 不是 JSON：先驗簽才會到 400。
	notJSON := "not json"
	wantError(t, f.webhook(notJSON, sign(webhookSecret, []byte(notJSON))), 400, "INVALID_REQUEST")
	// 不是 JSON 且簽章錯：仍是 401（未通過前不解析）。
	wantError(t, f.webhook(notJSON, "00"), 401, "INVALID_SIGNATURE")
	// 正確簽章才處理。
	if r := f.webhook(body, good); r.Code != 200 {
		t.Fatalf("good = %d %v", r.Code, r.body)
	}
	f.wantNothingChanged2(t, id)
}

// flipLastHexByte 只改最後一個 hex 位元組（長度不變、仍是合法 hex），並確認真的不同。
func flipLastHexByte(sig string) string {
	last := sig[len(sig)-2:]
	flipped := "00"
	if last == "00" {
		flipped = "ff"
	}
	out := sig[:len(sig)-2] + flipped
	if out == sig || len(out) != len(sig) {
		panic("flipLastHexByte did not change the signature")
	}
	return out
}

func (f *vfx) wantNothingChanged2(t *testing.T, id string) {
	t.Helper()
	if st, _, _, _ := f.row(t, id); st != "FAILED" || f.balance(t) != 100 || f.ledgerCount(t, "REFUND") != 1 {
		t.Fatalf("not processed: %s", st)
	}
}

func TestVendorWebhookRouting(t *testing.T) {
	f := newVfx(t)
	r := send(f.h, http.MethodGet, "/webhooks/vendor", nil, false, nil)
	wantError(t, r, 405, "METHOD_NOT_ALLOWED")
	if r.Header().Get("Allow") != "POST" {
		t.Fatalf("Allow = %q", r.Header().Get("Allow"))
	}
	// 沒有設定密鑰時不註冊這條路由。
	h := apihttp.Handler(f.svc)
	wantError(t, send(h, http.MethodPost, "/webhooks/vendor", []byte("{}"), false, nil), 404, "NOT_FOUND")
	// 超過 64KB 的 body：400，不處理。
	big := bytes.Repeat([]byte("a"), 1<<16+1)
	wantError(t, send(f.h, http.MethodPost, "/webhooks/vendor", big, false, map[string]string{"X-Signature": sign(webhookSecret, big)}), 400, "INVALID_REQUEST")
}

func TestVendorWebhookValidation(t *testing.T) {
	f := newVfx(t)
	id := f.generating(t, "k")
	bad := map[string]string{
		"not json":                 `nope`,
		"empty object":             `{}`,
		"array":                    `[]`,
		"missing event_id":         fmt.Sprintf(`{"job_id":%q,"status":"FAILED"}`, id),
		"empty event_id":           fmt.Sprintf(`{"event_id":"","job_id":%q,"status":"FAILED"}`, id),
		"missing job_id":           `{"event_id":"e","status":"FAILED"}`,
		"empty job_id":             `{"event_id":"e","job_id":"","status":"FAILED"}`,
		"NUL in job_id":            `{"event_id":"e","job_id":"a\u0000b","status":"FAILED"}`,
		"NUL in event_id":          fmt.Sprintf(`{"event_id":"a\u0000b","job_id":%q,"status":"FAILED"}`, id),
		"invalid utf8 in event_id": "{\"event_id\":\"a\xffb\",\"job_id\":\"" + id + "\",\"status\":\"FAILED\"}",
		"NUL in reason":            fmt.Sprintf(`{"event_id":"e","job_id":%q,"status":"FAILED","reason":"a\u0000b"}`, id),
		"bad status":               fmt.Sprintf(`{"event_id":"e","job_id":%q,"status":"PENDING"}`, id),
		"lowercase status":         fmt.Sprintf(`{"event_id":"e","job_id":%q,"status":"failed"}`, id),
		"missing status":           fmt.Sprintf(`{"event_id":"e","job_id":%q}`, id),
		"succeeded without video":  fmt.Sprintf(`{"event_id":"e","job_id":%q,"status":"SUCCEEDED"}`, id),
		"succeeded empty video":    fmt.Sprintf(`{"event_id":"e","job_id":%q,"status":"SUCCEEDED","video_url":""}`, id),
		"succeeded null video":     fmt.Sprintf(`{"event_id":"e","job_id":%q,"status":"SUCCEEDED","video_url":null}`, id),
		"NUL in video_url":         fmt.Sprintf(`{"event_id":"e","job_id":%q,"status":"SUCCEEDED","video_url":"a\u0000b"}`, id),
		"trailing garbage":         fmt.Sprintf(`{"event_id":"e","job_id":%q,"status":"FAILED"} x`, id),
	}
	for name, body := range bad {
		r := f.signed(body)
		if r.Code != 400 || r.body["code"] != "INVALID_REQUEST" {
			t.Errorf("%s: got %d %v", name, r.Code, r.body)
		}
	}
	f.wantNothingChanged(t, id, "GENERATING", 90, 1, 0, 0)
}

func TestVendorUnknownJob(t *testing.T) {
	f := newVfx(t)
	f.generating(t, "k")
	r := f.signed(okBody("e1", "00000000-0000-0000-0000-000000000000"))
	wantError(t, r, 404, "CAMEO_NOT_FOUND")
	wantError(t, f.signed(okBody("e1", "not-a-uuid")), 404, "CAMEO_NOT_FOUND")
	if n := count(t, f.pool, "vendor_events"); n != 0 {
		t.Fatalf("vendor_events = %d, want 0", n)
	}
}

func TestVendorCallbackInEarlyStatesChangesNothing(t *testing.T) {
	f := newVfx(t)
	awaiting := f.create(t, "a")
	uploaded := f.createUploaded(t, "b")
	for i, id := range []string{awaiting, uploaded} {
		for _, body := range []string{okBody(fmt.Sprint("s", i), id), failBody(fmt.Sprint("f", i), id, "")} {
			if r := f.signed(body); r.Code != 200 {
				t.Fatalf("%s: %d %v", id, r.Code, r.body)
			}
		}
	}
	if st, _ := f.state(t, awaiting); st != "AWAITING_UPLOAD" {
		t.Fatalf("awaiting = %s", st)
	}
	if st, _ := f.state(t, uploaded); st != "UPLOADED" {
		t.Fatalf("uploaded = %s", st)
	}
	if b := f.balance(t); b != 100 || count(t, f.pool, "ledger_entries") != 0 || count(t, f.pool, "vendor_events") != 4 {
		t.Fatalf("balance %d ledger %d events %d", b, count(t, f.pool, "ledger_entries"), count(t, f.pool, "vendor_events"))
	}
	if _, ok := f.selfie(t, uploaded); !ok {
		t.Fatal("selfie of UPLOADED order deleted by an ignored callback")
	}
	if p := points(t, f.svc); p.Held != 20 {
		t.Fatalf("points = %+v", p)
	}
}

// UPLOADED 時收到的 FAILED 事件被忽略但事件留存；送出後同一 event_id 重送仍視為重複，不退點。
func TestVendorIgnoredEventIsNotReplayedAfterSubmit(t *testing.T) {
	f := newVfx(t)
	id := f.createUploaded(t, "k")
	body := failBody("e1", id, "")
	if r := f.signed(body); r.Code != 200 {
		t.Fatalf("while UPLOADED = %d %v", r.Code, r.body)
	}
	if st, _ := f.state(t, id); st != "UPLOADED" {
		t.Fatalf("status = %s", st)
	}
	if n := count(t, f.pool, "vendor_events"); n != 1 {
		t.Fatalf("events = %d, want 1", n)
	}
	if r := f.submit(id); r.Code != 200 {
		t.Fatalf("submit = %d %v", r.Code, r.body)
	}
	if st, ps, _, _ := f.row(t, id); st != "QUEUED" || ps != "CHARGED" || f.balance(t) != 90 {
		t.Fatalf("after submit: %s/%s balance %d", st, ps, f.balance(t))
	}
	if r := f.signed(body); r.Code != 200 {
		t.Fatalf("replay = %d %v", r.Code, r.body)
	}
	if st, ps, _, _ := f.row(t, id); st != "QUEUED" || ps != "CHARGED" {
		t.Fatalf("after replay: %s/%s", st, ps)
	}
	if b := f.balance(t); b != 90 {
		t.Fatalf("balance = %d, want 90", b)
	}
	if n := f.ledgerCount(t, "REFUND"); n != 0 {
		t.Fatalf("refunds = %d, want 0", n)
	}
	if n := count(t, f.pool, "vendor_events"); n != 1 {
		t.Fatalf("events = %d, want 1", n)
	}
}

func TestVendorTerminalStatesAreImmutable(t *testing.T) {
	f := newVfx(t)
	failed := f.generating(t, "a")
	if r := f.signed(failBody("e1", failed, "")); r.Code != 200 {
		t.Fatal(r.Body.String())
	}
	succeeded := f.generating(t, "b")
	if r := f.signed(okBody("e2", succeeded)); r.Code != 200 {
		t.Fatal(r.Body.String())
	}
	// 100 -10 -10 +10 = 90
	for i, body := range []string{okBody("e3", failed), failBody("e4", succeeded, ""), failBody("e5", succeeded, "CONTENT_REJECTED"), okBody("e6", succeeded)} {
		if r := f.signed(body); r.Code != 200 {
			t.Fatalf("%d: %d %v", i, r.Code, r.body)
		}
	}
	if st, ps, reason, v := f.row(t, failed); st != "FAILED" || ps != "REFUNDED" || v != nil || reason == nil || *reason != "VENDOR_FAILED" {
		t.Fatalf("failed row = %s %s %v %v", st, ps, reason, v)
	}
	if st, ps, reason, v := f.row(t, succeeded); st != "SUCCEEDED" || ps != "CHARGED" || reason != nil || v == nil {
		t.Fatalf("succeeded row = %s %s %v %v", st, ps, reason, v)
	}
	if b := f.balance(t); b != 90 || f.ledgerCount(t, "REFUND") != 1 || f.ledgerCount(t, "CHARGE") != 2 {
		t.Fatalf("balance %d refunds %d", b, f.ledgerCount(t, "REFUND"))
	}
	// 逾時掃描也不能動終態。
	f.clock.Set(t0.Add(24 * time.Hour))
	if ids, err := f.svc.FailTimedOut(context.Background()); err != nil || len(ids) != 0 {
		t.Fatalf("FailTimedOut = %v %v", ids, err)
	}
	if b := f.balance(t); b != 90 {
		t.Fatalf("balance = %d", b)
	}
}

func TestVendorDuplicateEventSequentialAndConcurrent(t *testing.T) {
	f := newVfx(t)
	id := f.generating(t, "k")
	body := failBody("same", id, "")
	for i := 0; i < 3; i++ {
		if r := f.signed(body); r.Code != 200 {
			t.Fatalf("sequential %d = %d", i, r.Code)
		}
	}
	f.wantNothingChanged(t, id, "FAILED", 100, 1, 1, 1)

	id2 := f.generating(t, "k2")
	body2 := failBody("same2", id2, "")
	ok, errs := runConcurrently(20, func(int) error {
		if r := f.signed(body2); r.Code != 200 {
			return fmt.Errorf("code %d", r.Code)
		}
		return nil
	})
	if ok != 20 || len(errs) != 0 {
		t.Fatalf("ok = %d errs = %v", ok, errs)
	}
	f.wantNothingChanged(t, id2, "FAILED", 100, 2, 2, 2)
}

func TestVendorDifferentEventsConcurrentRefundOnce(t *testing.T) {
	f := newVfx(t)
	id := f.generating(t, "k")
	ok, errs := runConcurrently(20, func(i int) error {
		reason := ""
		if i%2 == 0 {
			reason = "CONTENT_REJECTED"
		}
		return f.svc.HandleVendorCallback(context.Background(), usecase.VendorEvent{
			EventID: fmt.Sprint("ev-", i), JobID: id, Status: "FAILED", Reason: strPtr(reason),
		})
	})
	if ok != 20 || len(errs) != 0 {
		t.Fatalf("ok = %d errs = %v", ok, errs)
	}
	f.wantNothingChanged(t, id, "FAILED", 100, 1, 1, 20)
	if _, ps, _, _ := f.row(t, id); ps != "REFUNDED" {
		t.Fatalf("points_state = %s", ps)
	}
}

func strPtr(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

// 成功與失敗回呼併發：只有一個終態。
func TestVendorSucceededAndFailedConcurrent(t *testing.T) {
	f := newVfx(t)
	for round := 0; round < 10; round++ {
		id := f.generating(t, fmt.Sprint("k", round))
		_, errs := runConcurrently(2, func(i int) error {
			if i == 0 {
				return f.svc.HandleVendorCallback(context.Background(), usecase.VendorEvent{EventID: fmt.Sprint("s", round), JobID: id, Status: "SUCCEEDED", VideoURL: "http://v/x.mp4"})
			}
			return f.svc.HandleVendorCallback(context.Background(), usecase.VendorEvent{EventID: fmt.Sprint("f", round), JobID: id, Status: "FAILED"})
		})
		if len(errs) != 0 {
			t.Fatal(errs)
		}
		f.assertConsistent(t, id)
	}
}

// assertConsistent 驗證終態與餘額、明細一致（這筆訂單先扣 10 點）。
func (f *vfx) assertConsistent(t *testing.T, id string) {
	t.Helper()
	st, ps, reason, video := f.row(t, id)
	var refunds int
	if err := f.pool.QueryRow(context.Background(), `SELECT count(*) FROM ledger_entries WHERE type='REFUND' AND cameo_id = $1`, id).Scan(&refunds); err != nil {
		t.Fatal(err)
	}
	switch st {
	case "SUCCEEDED":
		if ps != "CHARGED" || video == nil || reason != nil || refunds != 0 {
			t.Fatalf("inconsistent SUCCEEDED: %s %v %v refunds %d", ps, reason, video, refunds)
		}
	case "FAILED":
		if ps != "REFUNDED" || video != nil || reason == nil || refunds != 1 {
			t.Fatalf("inconsistent FAILED: %s %v %v refunds %d", ps, reason, video, refunds)
		}
	default:
		t.Fatalf("not terminal: %s", st)
	}
}

// 回呼與逾時併發競爭：最後只有一個終態，餘額與明細一致。
func TestVendorCallbackRacingTimeout(t *testing.T) {
	f := newVfx(t)
	files := newFiles2(t, f.dir)
	cfg := usecase.Config{DailyLimit: 1000, UploadTTL: ttl, GenerationTTL: 30 * time.Minute}
	// 回呼端的交易在鎖住訂單後多等一下，放大競爭視窗；逾時端的時鐘在期限之後。
	cbSvc := usecase.NewService(postgres.NewQueries(f.pool), delayRunner{postgres.NewTxRunner(f.pool), 15 * time.Millisecond}, testdb.NewClock(t0), cfg, files)
	toSvc := usecase.NewService(postgres.NewQueries(f.pool), postgres.NewTxRunner(f.pool), testdb.NewClock(t0.Add(time.Hour)), cfg, files)
	if _, err := f.pool.Exec(context.Background(), `UPDATE points_account SET balance = 1000`); err != nil {
		t.Fatal(err)
	}
	var succeeded, failed int
	for round := 0; round < 30; round++ {
		id := f.generating(t, fmt.Sprint("k", round))
		// 讓 f.svc 看到的期限已到：直接把期限改成過去，使逾時端必定符合條件。
		if _, err := f.pool.Exec(context.Background(), `UPDATE cameos SET generation_expires_at = $1 WHERE id = $2`, t0.Add(time.Minute), id); err != nil {
			t.Fatal(err)
		}
		var wg sync.WaitGroup
		wg.Add(2)
		start := make(chan struct{})
		var cbErr, toErr error
		status := "SUCCEEDED"
		if round%2 == 1 {
			status = "FAILED"
		}
		go func() {
			defer wg.Done()
			<-start
			cbErr = cbSvc.HandleVendorCallback(context.Background(), usecase.VendorEvent{EventID: fmt.Sprint("e", round), JobID: id, Status: status, VideoURL: "http://v/x.mp4"})
		}()
		go func() {
			defer wg.Done()
			<-start
			time.Sleep(time.Duration(round%5) * 4 * time.Millisecond)
			_, toErr = toSvc.FailTimedOut(context.Background())
		}()
		close(start)
		wg.Wait()
		if cbErr != nil || toErr != nil {
			t.Fatalf("round %d: %v %v", round, cbErr, toErr)
		}
		f.assertConsistent(t, id)
		if st, _, _, _ := f.row(t, id); st == "SUCCEEDED" {
			succeeded++
		} else {
			failed++
		}
		f.wantNoFiles(t)
	}
	// 餘額：每筆訂單扣 10，失敗的退 10。
	if want := 1000 - 10*succeeded; f.balance(t) != want {
		t.Fatalf("balance = %d, want %d (succeeded %d failed %d)", f.balance(t), want, succeeded, failed)
	}
	t.Logf("succeeded=%d failed=%d", succeeded, failed)
}

func TestFailTimedOut(t *testing.T) {
	f := newVfx(t)
	ctx := context.Background()
	deadline := t0.Add(30 * time.Minute)
	queued := f.queued(t, "q")
	gen := f.generating(t, "g")
	other := f.createUploaded(t, "u") // 未送出：不受生成期限影響

	f.clock.Set(deadline.Add(-time.Nanosecond))
	if ids, err := f.svc.FailTimedOut(ctx); err != nil || len(ids) != 0 {
		t.Fatalf("before deadline: %v %v", ids, err)
	}
	if b := f.balance(t); b != 80 {
		t.Fatalf("balance = %d", b)
	}

	f.clock.Set(deadline) // 期限當下算逾期
	ids, err := f.svc.FailTimedOut(ctx)
	if err != nil || len(ids) != 2 {
		t.Fatalf("at deadline: %v %v", ids, err)
	}
	for _, id := range []string{queued, gen} {
		st, ps, reason, _ := f.row(t, id)
		if st != "FAILED" || ps != "REFUNDED" || reason == nil || *reason != "TIMEOUT" {
			t.Fatalf("%s row = %s %s %v", id, st, ps, reason)
		}
		if _, ok := f.selfie(t, id); ok {
			t.Fatalf("selfie of %s not deleted", id)
		}
	}
	if st, _ := f.state(t, other); st != "UPLOADED" {
		t.Fatalf("other = %s", st)
	}
	if b := f.balance(t); b != 100 || f.ledgerCount(t, "REFUND") != 2 {
		t.Fatalf("balance %d refunds %d", b, f.ledgerCount(t, "REFUND"))
	}
	// 額度退回：3 筆建單，2 筆失敗（非 CONTENT_REJECTED）→ 剩 1 筆占用額度。
	if q := quota(t, f.svc); q.Remaining != 999 {
		t.Fatalf("quota = %d", q.Remaining)
	}
	// 重跑不會再退。
	if ids, err := f.svc.FailTimedOut(ctx); err != nil || len(ids) != 0 || f.balance(t) != 100 {
		t.Fatalf("rerun: %v %v balance %d", ids, err, f.balance(t))
	}
	// 之後到的成功回呼不能改變 FAILED。
	if r := f.signed(okBody("late", gen)); r.Code != 200 {
		t.Fatal(r.Body.String())
	}
	if st, _, _, v := f.row(t, gen); st != "FAILED" || v != nil {
		t.Fatalf("late success changed state: %s", st)
	}
}

func TestDispatchDue(t *testing.T) {
	ctx := context.Background()
	t.Run("success then no re-dispatch", func(t *testing.T) {
		f := newVfx(t)
		id := f.queued(t, "k")
		if ids, err := f.svc.DispatchDue(ctx); err != nil || len(ids) != 1 {
			t.Fatalf("%v %v", ids, err)
		}
		if st, _ := f.state(t, id); st != "GENERATING" {
			t.Fatalf("status = %s", st)
		}
		if ids, err := f.svc.DispatchDue(ctx); err != nil || len(ids) != 0 || len(f.vendor.callList()) != 1 {
			t.Fatalf("second run: %v %v calls %v", ids, err, f.vendor.callList())
		}
	})
	t.Run("failure stays queued and retries", func(t *testing.T) {
		f := newVfx(t)
		id := f.queued(t, "k")
		f.vendor.set(errors.New("vendor down"), nil)
		if ids, err := f.svc.DispatchDue(ctx); err != nil || len(ids) != 0 {
			t.Fatalf("%v %v", ids, err)
		}
		if st, _ := f.state(t, id); st != "QUEUED" {
			t.Fatalf("status = %s", st)
		}
		f.vendor.set(nil, nil)
		if ids, err := f.svc.DispatchDue(ctx); err != nil || len(ids) != 1 {
			t.Fatalf("retry: %v %v", ids, err)
		}
		if st, _ := f.state(t, id); st != "GENERATING" || len(f.vendor.callList()) != 2 {
			t.Fatalf("status = %s calls %v", st, f.vendor.callList())
		}
	})
	t.Run("one failure does not block the others", func(t *testing.T) {
		f := newVfx(t)
		a := f.queued(t, "a")
		f.clock.Advance(time.Second)
		b := f.queued(t, "b")
		f.vendor.set(nil, nil)
		svc := newSvc(f.pool, f.clock, usecase.Config{DailyLimit: 1000, UploadTTL: ttl}, newFiles2(t, f.dir)).WithVendor(vendorFunc(func(id string) error {
			if id == a {
				return errors.New("nope")
			}
			return nil
		}))
		ids, err := svc.DispatchDue(ctx)
		if err != nil || len(ids) != 1 || ids[0] != b {
			t.Fatalf("ids = %v err = %v", ids, err)
		}
		if sa, _ := f.state(t, a); sa != "QUEUED" {
			t.Fatalf("a = %s", sa)
		}
		if sb, _ := f.state(t, b); sb != "GENERATING" {
			t.Fatalf("b = %s", sb)
		}
	})
	t.Run("callback first is not overwritten", func(t *testing.T) {
		f := newVfx(t)
		id := f.queued(t, "k")
		f.vendor.set(nil, func() {
			if r := f.signed(okBody("e1", id)); r.Code != 200 {
				t.Errorf("webhook = %d", r.Code)
			}
		})
		ids, err := f.svc.DispatchDue(ctx)
		if err != nil || len(ids) != 0 {
			t.Fatalf("%v %v", ids, err)
		}
		if st, _, _, v := f.row(t, id); st != "SUCCEEDED" || v == nil {
			t.Fatalf("status = %s", st)
		}
	})
	t.Run("expired is not dispatched", func(t *testing.T) {
		f := newVfx(t)
		id := f.queued(t, "k")
		f.clock.Set(t0.Add(30 * time.Minute))
		if ids, err := f.svc.DispatchDue(ctx); err != nil || len(ids) != 0 || len(f.vendor.callList()) != 0 {
			t.Fatalf("%v %v calls %v", ids, err, f.vendor.callList())
		}
		if st, _ := f.state(t, id); st != "QUEUED" {
			t.Fatalf("status = %s", st)
		}
	})
	t.Run("oldest first and at most 20 per sweep", func(t *testing.T) {
		f := newVfx(t)
		if _, err := f.pool.Exec(ctx, `UPDATE points_account SET balance = 1000`); err != nil {
			t.Fatal(err)
		}
		var want []string
		for i := 0; i < 22; i++ {
			f.clock.Advance(time.Second)
			want = append(want, f.queued(t, fmt.Sprint("k", i)))
		}
		ids, err := f.svc.DispatchDue(ctx)
		if err != nil || len(ids) != 20 {
			t.Fatalf("dispatched %d err %v", len(ids), err)
		}
		got := f.vendor.callList()
		for i := range got {
			if got[i] != want[i] {
				t.Fatalf("call %d = %s, want %s (created_at order)", i, got[i], want[i])
			}
		}
		if ids, err := f.svc.DispatchDue(ctx); err != nil || len(ids) != 2 {
			t.Fatalf("second sweep: %d %v", len(ids), err)
		}
	})
}

type vendorFunc func(id string) error

func (v vendorFunc) Dispatch(_ context.Context, id string) error { return v(id) }

// failingTx 在指定的寫入操作回錯，用來驗證交易整個回滾。
type failingTx struct {
	usecase.Tx
	failMarkFailed, failLedger bool
}

func (t failingTx) MarkFailed(ctx context.Context, id, reason string, now time.Time) error {
	if t.failMarkFailed {
		return errors.New("injected MarkFailed failure")
	}
	return t.Tx.MarkFailed(ctx, id, reason, now)
}

func (t failingTx) InsertLedger(ctx context.Context, e domain.LedgerEntry) error {
	if t.failLedger {
		return errors.New("injected InsertLedger failure")
	}
	return t.Tx.InsertLedger(ctx, e)
}

type failingRunner struct {
	usecase.TxRunner
	failMarkFailed, failLedger bool
}

func (r failingRunner) Run(ctx context.Context, fn func(usecase.Tx) error) error {
	return r.TxRunner.Run(ctx, func(tx usecase.Tx) error { return fn(failingTx{tx, r.failMarkFailed, r.failLedger}) })
}

func TestRefundFlowIsAtomic(t *testing.T) {
	for _, mode := range []string{"MarkFailed", "InsertLedger"} {
		t.Run(mode, func(t *testing.T) {
			f := newVfx(t)
			id := f.generating(t, "k")
			files := newFiles2(t, f.dir)
			cfg := usecase.Config{DailyLimit: 1000, UploadTTL: ttl}
			runner := failingRunner{postgres.NewTxRunner(f.pool), mode == "MarkFailed", mode == "InsertLedger"}
			cbSvc := usecase.NewService(postgres.NewQueries(f.pool), runner, f.clock, cfg, files)
			err := cbSvc.HandleVendorCallback(context.Background(), usecase.VendorEvent{EventID: "e1", JobID: id, Status: "FAILED"})
			if err == nil {
				t.Fatal("want error")
			}
			// 回呼：餘額已加回的寫入、事件、明細、狀態全部還原。
			f.wantNothingChanged(t, id, "GENERATING", 90, 1, 0, 0)
			if _, ok := f.selfie(t, id); !ok {
				t.Fatal("selfie deleted although the transaction rolled back")
			}
			// 逾時流程同樣原子。
			f.clock.Set(t0.Add(time.Hour))
			toSvc := usecase.NewService(postgres.NewQueries(f.pool), runner, f.clock, cfg, files)
			ids, err := toSvc.FailTimedOut(context.Background())
			if err == nil || len(ids) != 0 {
				t.Fatalf("FailTimedOut = %v %v, want error", ids, err)
			}
			f.wantNothingChanged(t, id, "GENERATING", 90, 1, 0, 0)
			if _, ok := f.selfie(t, id); !ok {
				t.Fatal("selfie deleted although the transaction rolled back")
			}
			// 修好之後可以重試，只退一次。
			if r := f.signed(failBody("e2", id, "")); r.Code != 200 {
				t.Fatal(r.Body.String())
			}
			f.wantNothingChanged(t, id, "FAILED", 100, 1, 1, 1)
		})
	}
}

func TestMigration0005Constraints(t *testing.T) {
	f := newVfx(t)
	id := f.generating(t, "k")
	ctx := context.Background()
	if r := f.signed(failBody("e1", id, "")); r.Code != 200 {
		t.Fatal(r.Body.String())
	}
	// 每單最多一筆 REFUND。
	if _, err := f.pool.Exec(ctx, `INSERT INTO ledger_entries (type, amount, cameo_id, clip_title) VALUES ('REFUND', 10, $1, 'x')`, id); err == nil {
		t.Fatal("second REFUND for the same cameo was accepted")
	}
	// vendor_events 的 event_id 主鍵；job_id 沒有外鍵。
	if _, err := f.pool.Exec(ctx, `INSERT INTO vendor_events (event_id, job_id, status, received_at) VALUES ('orphan', 'no-such-job', 'FAILED', now())`); err != nil {
		t.Fatalf("job_id must not have a foreign key: %v", err)
	}
	if _, err := f.pool.Exec(ctx, `INSERT INTO vendor_events (event_id, job_id, status, received_at) VALUES ('orphan', 'x', 'FAILED', now())`); err == nil {
		t.Fatal("duplicate event_id accepted")
	}
	var idx int
	if err := f.pool.QueryRow(ctx, `SELECT count(*) FROM pg_indexes WHERE schemaname = current_schema() AND indexname = 'cameos_generation_expiry_idx'`).Scan(&idx); err != nil || idx != 1 {
		t.Fatalf("expiry index missing: %d %v", idx, err)
	}
}

// F.2：WriteTemp 的非 ctx 錯誤（例如磁碟滿）不是讀 body 失敗，HTTP 回 500。
type diskFullFiles struct{ usecase.FileStore }

func (d diskFullFiles) WriteTemp(_ context.Context, r io.Reader) (string, error) {
	_, _ = io.Copy(io.Discard, r)
	return "", &os.PathError{Op: "write", Path: "/data/selfies/.tmp-x", Err: errors.New("no space left on device")}
}

func TestUploadDiskFullIs500NotBodyReadError(t *testing.T) {
	f := newFx(t, 0)
	files := diskFullFiles{newFiles2(t, f.dir)}
	svc := newSvc(f.pool, f.clock, usecase.Config{DailyLimit: 1000, UploadTTL: ttl}, files)
	h := apihttp.Handler(svc)
	id := f.create(t, "k")
	_, err := svc.UploadSelfie(context.Background(), id, bytes.NewReader(jpegBytes))
	var be *usecase.BodyReadError
	if err == nil || errors.As(err, &be) {
		t.Fatalf("err = %v, want a non-BodyReadError", err)
	}
	r := send(h, http.MethodPut, "/v1/cameos/"+id+"/selfie", jpegBytes, true, nil)
	wantError(t, r, 500, "INTERNAL")
	if st, _ := f.state(t, id); st != "AWAITING_UPLOAD" {
		t.Fatalf("status = %s", st)
	}
	f.wantNoFiles(t)
}

// F.4：已逾期但尚未被掃描的訂單，內容不合法或超限時回 400／413，訂單不變（仍待掃描釋放）。
func TestOverdueUnsweptInvalidOrOversizedContent(t *testing.T) {
	for _, uploaded := range []bool{false, true} {
		f := newFx(t, 1024)
		var id string
		if uploaded {
			id = f.createUploaded(t, "k")
		} else {
			id = f.create(t, "k")
		}
		before, hadFile := f.selfie(t, id)
		f.clock.Set(t0.Add(ttl + time.Hour))
		wantState := "AWAITING_UPLOAD"
		if uploaded {
			wantState = "UPLOADED"
		}
		check := func(name string) {
			t.Helper()
			if st, ps := f.state(t, id); st != wantState || ps != "HELD" {
				t.Fatalf("%s (uploaded=%v): state = %s/%s, want %s/HELD", name, uploaded, st, ps, wantState)
			}
			after, has := f.selfie(t, id)
			if has != hadFile || !bytes.Equal(before, after) {
				t.Fatalf("%s (uploaded=%v): selfie changed", name, uploaded)
			}
			if names := f.dirNames(t); len(names) != map[bool]int{false: 0, true: 1}[uploaded] {
				t.Fatalf("%s: files = %v", name, names)
			}
		}
		wantError(t, f.put(id, []byte("\x89PNG not a jpeg")), 400, "INVALID_SELFIE")
		check("invalid")
		wantError(t, f.put(id, nil), 400, "INVALID_SELFIE")
		check("empty")
		over := append([]byte{0xFF, 0xD8, 0xFF}, bytes.Repeat([]byte{1}, 1100)...)
		wantError(t, f.put(id, over), 413, "SELFIE_TOO_LARGE")
		check("oversized")
		// 內容合法才釋放並回 410。
		wantError(t, f.put(id, jpegBytes), 410, "UPLOAD_EXPIRED")
		if st, ps := f.state(t, id); st != "EXPIRED" || ps != "RELEASED" {
			t.Fatalf("after valid upload: %s/%s", st, ps)
		}
	}
}
