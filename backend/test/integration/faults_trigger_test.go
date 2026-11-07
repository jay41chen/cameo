package integration_test

import (
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"cameo/backend/internal/usecase"
)

// 8.2：API 這側三種異常的實際觸發。連線中斷用真實連線（httptest.NewServer）；
// 用 DisableKeepAlives 避免 Transport 自動重送，並在伺服器端數請求次數確認只送了一次。

type liveSrv struct {
	srv  *httptest.Server
	c    *http.Client
	hits atomic.Int64
}

func (f *vfx) live(t *testing.T) *liveSrv {
	t.Helper()
	l := &liveSrv{}
	l.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		l.hits.Add(1)
		f.h.ServeHTTP(w, r)
	}))
	t.Cleanup(l.srv.Close)
	l.c = &http.Client{Transport: &http.Transport{DisableKeepAlives: true}, Timeout: 10 * time.Second}
	return l
}

// do 送一次請求；回傳狀態碼（連線錯誤時為 0）與 error。
func (l *liveSrv) do(method, path, key string, body []byte) (int, error) {
	req, _ := http.NewRequest(method, l.srv.URL+path, bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer test-token")
	if key != "" {
		req.Header.Set("Idempotency-Key", key)
	}
	res, err := l.c.Do(req)
	if err != nil {
		return 0, err
	}
	res.Body.Close()
	return res.StatusCode, nil
}

func (l *liveSrv) wantLost(t *testing.T, method, path, key string, body []byte) {
	t.Helper()
	before := l.hits.Load()
	if code, err := l.do(method, path, key, body); err == nil {
		t.Fatalf("%s %s: got HTTP %d, want connection error", method, path, code)
	}
	if got := l.hits.Load() - before; got != 1 {
		t.Fatalf("%s %s: server saw %d requests, want exactly 1 (no client retry)", method, path, got)
	}
}

// faultTimes 回傳已登記異常的剩餘次數；沒登記回 0。
func (f *vfx) faultTimes(t *testing.T, name string) int {
	t.Helper()
	for _, e := range f.activeFaults(t) {
		m := e.(map[string]any)
		if m["name"] == name {
			return int(m["times"].(float64))
		}
	}
	return 0
}

func (f *vfx) arm(t *testing.T, body string) {
	t.Helper()
	if r := f.debugPost("/debug/faults", body); r.Code != 200 {
		t.Fatalf("arm %s = %d %s", body, r.Code, r.Body.String())
	}
}

func (f *vfx) createReq(key string) resp {
	return send(f.h, http.MethodPost, "/v1/cameos", []byte(`{"clip_id":"c1"}`), true, map[string]string{"Idempotency-Key": key})
}

func (f *vfx) putReq(id string, body []byte) resp {
	return send(f.h, http.MethodPut, "/v1/cameos/"+id+"/selfie", body, true, nil)
}

func (f *vfx) cameoCount(t *testing.T) int { return count(t, f.pool, "cameos") }

func (f *vfx) setStatus(t *testing.T, id, st string) {
	t.Helper()
	if _, err := f.pool.Exec(context.Background(), `UPDATE cameos SET status = $1 WHERE id = $2`, st, id); err != nil {
		t.Fatal(err)
	}
}

func (f *vfx) onlyCameoID(t *testing.T) string {
	t.Helper()
	var id string
	if err := f.pool.QueryRow(context.Background(), `SELECT id FROM cameos`).Scan(&id); err != nil {
		t.Fatal(err)
	}
	return id
}

func bigJPEG(n int) []byte {
	return append([]byte{0xFF, 0xD8, 0xFF, 0xE0}, bytes.Repeat([]byte{7}, n)...)
}

// ---------- response_lost_after_commit ----------

func TestResponseLostCreateCommitsThenIdempotentRetry(t *testing.T) {
	f := newDebugVfx(t)
	l := f.live(t)
	f.arm(t, `{"name":"response_lost_after_commit","target":"create"}`)

	l.wantLost(t, http.MethodPost, "/v1/cameos", "k1", []byte(`{"clip_id":"c1"}`))
	if n := f.cameoCount(t); n != 1 {
		t.Fatalf("cameos = %d, want 1", n)
	}
	if avail, held := f.getPoints(t); avail != 90 || held != 10 {
		t.Fatalf("points = %d/%d, want 90/10", avail, held)
	}
	if got := f.faultTimes(t, "response_lost_after_commit"); got != 0 {
		t.Fatalf("times = %d, want consumed", got)
	}
	id := f.onlyCameoID(t)
	r := f.createReq("k1")
	if r.Code != 200 || cameoOf(r)["id"] != id {
		t.Fatalf("retry = %d %v, want 200 same id %s", r.Code, r.body, id)
	}
	if n := f.cameoCount(t); n != 1 {
		t.Fatalf("cameos after retry = %d", n)
	}
	if _, held := f.getPoints(t); held != 10 {
		t.Fatalf("held = %d, want 10 (occupied once)", held)
	}
	if got := f.remaining(t); got != 2 {
		t.Fatalf("remaining = %d, want 2 (counted once)", got)
	}
	// 次數扣完後回正常。
	if r := f.createReq("k2"); r.Code != 201 {
		t.Fatalf("after exhausted = %d", r.Code)
	}
}

// 冪等重放（Created == false）也算成功，會觸發。
func TestResponseLostCreateReplayAlsoTriggers(t *testing.T) {
	f := newDebugVfx(t)
	l := f.live(t)
	if r := f.createReq("k1"); r.Code != 201 {
		t.Fatalf("create = %d", r.Code)
	}
	f.arm(t, `{"name":"response_lost_after_commit","target":"create"}`)
	l.wantLost(t, http.MethodPost, "/v1/cameos", "k1", []byte(`{"clip_id":"c1"}`))
	if n := f.cameoCount(t); n != 1 {
		t.Fatalf("cameos = %d", n)
	}
	if got := f.faultTimes(t, "response_lost_after_commit"); got != 0 {
		t.Fatalf("times = %d", got)
	}
}

func TestResponseLostSubmitCommitsThenRetry(t *testing.T) {
	f := newDebugVfx(t)
	l := f.live(t)
	id := f.createUploaded(t, "k")
	f.arm(t, `{"name":"response_lost_after_commit","target":"submit"}`)

	l.wantLost(t, http.MethodPost, "/v1/cameos/"+id+"/submit", "", nil)
	if st, ps := f.state(t, id); st != "QUEUED" || ps != "CHARGED" {
		t.Fatalf("state = %s/%s", st, ps)
	}
	if n := f.ledgerCount(t, "CHARGE"); n != 1 {
		t.Fatalf("CHARGE = %d", n)
	}
	if b := f.balance(t); b != 90 {
		t.Fatalf("balance = %d", b)
	}
	r := f.submit(id)
	if r.Code != 200 || cameoOf(r)["status"] != "QUEUED" {
		t.Fatalf("retry = %d %v", r.Code, r.body)
	}
	if n, b := f.ledgerCount(t, "CHARGE"), f.balance(t); n != 1 || b != 90 {
		t.Fatalf("CHARGE = %d balance = %d after retry", n, b)
	}
}

func TestResponseLostTargetsAreIndependent(t *testing.T) {
	f := newDebugVfx(t)
	l := f.live(t)
	id := f.createUploaded(t, "k0")

	f.arm(t, `{"name":"response_lost_after_commit","target":"create"}`)
	if r := f.submit(id); r.Code != 200 { // submit 不受 create 異常影響
		t.Fatalf("submit = %d", r.Code)
	}
	if got := f.faultTimes(t, "response_lost_after_commit"); got != 1 {
		t.Fatalf("create fault consumed by submit: times = %d", got)
	}
	l.wantLost(t, http.MethodPost, "/v1/cameos", "k1", []byte(`{"clip_id":"c1"}`))

	id2 := cameoOf(f.createReq("k1"))["id"].(string) // 重放同一筆（異常已扣完，正常回 200）
	if r := f.putReq(id2, jpegBytes); r.Code != 200 {
		t.Fatalf("upload = %d", r.Code)
	}
	f.arm(t, `{"name":"response_lost_after_commit","target":"submit"}`)
	if r := f.createReq("k3"); r.Code != 201 { // create 不受 submit 異常影響
		t.Fatalf("create = %d", r.Code)
	}
	if got := f.faultTimes(t, "response_lost_after_commit"); got != 1 {
		t.Fatalf("submit fault consumed by create: times = %d", got)
	}
	l.wantLost(t, http.MethodPost, "/v1/cameos/"+id2+"/submit", "", nil)
}

func TestResponseLostFailedRequestsDoNotConsume(t *testing.T) {
	f := newDebugVfx(t)
	idUp := f.createUploaded(t, "up")
	idAw := f.create(t, "aw")
	f.create(t, "third") // DAILY_LIMIT=3 用完

	f.arm(t, `{"name":"response_lost_after_commit","target":"create"}`)
	wantError(t, f.createReq("k-limit"), 429, "DAILY_LIMIT_REACHED")
	wantError(t, send(f.h, http.MethodPost, "/v1/cameos", []byte(`{"clip_id":"nope"}`), true, map[string]string{"Idempotency-Key": "k404"}), 404, "CLIP_NOT_FOUND")
	wantError(t, send(f.h, http.MethodPost, "/v1/cameos", []byte(`{"clip_id":"c1"}`), true, nil), 400, "INVALID_REQUEST")
	if got := f.faultTimes(t, "response_lost_after_commit"); got != 1 {
		t.Fatalf("create: times = %d, want 1", got)
	}
	// 402：餘額 0（額度重置後不受 429 影響）。
	if _, err := f.pool.Exec(context.Background(), `DELETE FROM cameos WHERE id NOT IN ($1, $2)`, idUp, idAw); err != nil {
		t.Fatal(err)
	}
	if _, err := f.pool.Exec(context.Background(), `UPDATE points_account SET balance = 0`); err != nil {
		t.Fatal(err)
	}
	wantError(t, f.createReq("k402"), 402, "INSUFFICIENT_POINTS")
	if got := f.faultTimes(t, "response_lost_after_commit"); got != 1 {
		t.Fatalf("create 402: times = %d, want 1", got)
	}

	f.arm(t, `{"name":"response_lost_after_commit","target":"submit"}`)
	wantError(t, f.submit(idAw), 409, "SELFIE_NOT_UPLOADED")
	wantError(t, f.submit("no-such-cameo"), 404, "CAMEO_NOT_FOUND")
	if got := f.faultTimes(t, "response_lost_after_commit"); got != 1 {
		t.Fatalf("submit: times = %d, want 1", got)
	}
}

func TestResponseLostTimesTwoTriggersTwice(t *testing.T) {
	f := newDebugVfx(t)
	l := f.live(t)
	f.arm(t, `{"name":"response_lost_after_commit","target":"create","times":2}`)
	l.wantLost(t, http.MethodPost, "/v1/cameos", "k1", []byte(`{"clip_id":"c1"}`))
	if got := f.faultTimes(t, "response_lost_after_commit"); got != 1 {
		t.Fatalf("times = %d, want 1", got)
	}
	l.wantLost(t, http.MethodPost, "/v1/cameos", "k2", []byte(`{"clip_id":"c1"}`))
	if got := f.faultTimes(t, "response_lost_after_commit"); got != 0 {
		t.Fatalf("times = %d, want 0", got)
	}
	if code, err := l.do(http.MethodPost, "/v1/cameos", "k3", []byte(`{"clip_id":"c1"}`)); err != nil || code != 201 {
		t.Fatalf("third = %d %v", code, err)
	}
	if n := f.cameoCount(t); n != 3 {
		t.Fatalf("cameos = %d", n)
	}
}

// ---------- upload_interrupted ----------

func TestUploadInterruptedCutsConnectionAndLeavesNothing(t *testing.T) {
	f := newDebugVfx(t)
	l := f.live(t)
	id := f.create(t, "k")
	f.arm(t, `{"name":"upload_interrupted","params":{"after_bytes":1024}}`)

	l.wantLost(t, http.MethodPut, "/v1/cameos/"+id+"/selfie", "", bigJPEG(3000))
	if st, _ := f.state(t, id); st != "AWAITING_UPLOAD" {
		t.Fatalf("status = %s", st)
	}
	f.wantNoFiles(t)
	if got := f.faultTimes(t, "upload_interrupted"); got != 0 {
		t.Fatalf("times = %d, want consumed", got)
	}
	content := bigJPEG(3000)
	if r := f.putReq(id, content); r.Code != 200 {
		t.Fatalf("re-upload = %d %v", r.Code, r.body)
	}
	if got, ok := f.selfie(t, id); !ok || !bytes.Equal(got, content) {
		t.Fatal("re-uploaded file mismatch")
	}
}

func TestUploadInterruptedKeepsPreviousSelfie(t *testing.T) {
	f := newDebugVfx(t)
	l := f.live(t)
	id := f.createUploaded(t, "k")
	f.arm(t, `{"name":"upload_interrupted","params":{"after_bytes":100}}`)

	l.wantLost(t, http.MethodPut, "/v1/cameos/"+id+"/selfie", "", bigJPEG(3000))
	if st, _ := f.state(t, id); st != "UPLOADED" {
		t.Fatalf("status = %s", st)
	}
	if got, ok := f.selfie(t, id); !ok || !bytes.Equal(got, jpegBytes) {
		t.Fatalf("old selfie changed: %q", got)
	}
	if names := f.dirNames(t); len(names) != 1 {
		t.Fatalf("files = %v", names)
	}
}

func TestUploadInterruptedShortContentSucceedsWithoutConsuming(t *testing.T) {
	f := newDebugVfx(t)
	id := f.create(t, "k")
	f.arm(t, `{"name":"upload_interrupted","params":{"after_bytes":1024}}`)
	if r := f.putReq(id, jpegBytes); r.Code != 200 {
		t.Fatalf("put = %d %v", r.Code, r.body)
	}
	if got := f.faultTimes(t, "upload_interrupted"); got != 1 {
		t.Fatalf("times = %d, want 1 (not consumed)", got)
	}
}

func TestUploadInterruptedNotConsumedByRejectedUploads(t *testing.T) {
	f := newDebugVfx(t)
	queued := f.queued(t, "q")
	expired := f.create(t, "e")
	f.setStatus(t, expired, "EXPIRED")
	fresh := f.create(t, "f")
	f.arm(t, `{"name":"upload_interrupted","params":{"after_bytes":1024}}`)

	wantError(t, f.putReq(queued, bigJPEG(3000)), 409, "INVALID_STATE")
	wantError(t, f.putReq(expired, bigJPEG(3000)), 410, "UPLOAD_EXPIRED")
	wantError(t, f.putReq(fresh, []byte("not a jpeg")), 400, "INVALID_SELFIE")                                    // after_bytes 大於內容
	wantError(t, f.putReq(fresh, append([]byte("GIF"), bytes.Repeat([]byte{1}, 3000)...)), 400, "INVALID_SELFIE") // 前 3 bytes 就拒絕
	if got := f.faultTimes(t, "upload_interrupted"); got != 1 {
		t.Fatalf("times = %d, want 1", got)
	}
}

// ---------- upload_expired ----------

func TestUploadExpiredOnUploadPath(t *testing.T) {
	for _, withSelfie := range []bool{false, true} {
		name := "AWAITING_UPLOAD"
		if withSelfie {
			name = "UPLOADED"
		}
		t.Run(name, func(t *testing.T) {
			f := newDebugVfx(t)
			var id string
			if withSelfie {
				id = f.createUploaded(t, "k")
			} else {
				id = f.create(t, "k")
			}
			f.arm(t, `{"name":"upload_expired"}`)
			wantError(t, f.putReq(id, jpegBytes), 410, "UPLOAD_EXPIRED")
			f.wantExpiredClean(t, id)
		})
	}
}

func (f *vfx) wantExpiredClean(t *testing.T, id string) {
	t.Helper()
	if st, ps := f.state(t, id); st != "EXPIRED" || ps != "RELEASED" {
		t.Fatalf("state = %s/%s", st, ps)
	}
	if avail, held := f.getPoints(t); avail != 100 || held != 0 {
		t.Fatalf("points = %d/%d, want 100/0", avail, held)
	}
	if got := f.remaining(t); got != 3 {
		t.Fatalf("remaining = %d, want 3", got)
	}
	if n := count(t, f.pool, "ledger_entries"); n != 0 {
		t.Fatalf("ledger = %d", n)
	}
	f.wantNoFiles(t)
	if got := f.faultTimes(t, "upload_expired"); got != 0 {
		t.Fatalf("times = %d, want consumed", got)
	}
}

func TestUploadExpiredOnSubmitPath(t *testing.T) {
	for _, withSelfie := range []bool{true, false} {
		name := "AWAITING_UPLOAD"
		if withSelfie {
			name = "UPLOADED"
		}
		t.Run(name, func(t *testing.T) {
			f := newDebugVfx(t)
			var id string
			if withSelfie {
				id = f.createUploaded(t, "k")
			} else {
				id = f.create(t, "k")
			}
			f.arm(t, `{"name":"upload_expired"}`)
			wantError(t, f.submit(id), 410, "UPLOAD_EXPIRED")
			if n := f.ledgerCount(t, "CHARGE"); n != 0 {
				t.Fatalf("CHARGE = %d", n)
			}
			if b := f.balance(t); b != 100 {
				t.Fatalf("balance = %d", b)
			}
			f.wantExpiredClean(t, id)
		})
	}
}

func TestUploadExpiredInvalidContentDoesNotExpireOrConsume(t *testing.T) {
	f := newDebugVfx(t)
	f.svc = newSvc(f.pool, f.clock, usecase.Config{DailyLimit: 3, UploadTTL: ttl, GenerationTTL: 30 * time.Minute, MaxSelfieBytes: 100}, newFiles2(t, f.dir)).WithVendor(f.vendor)
	f.h = f.handler(f.svc)
	id := f.create(t, "k")
	f.arm(t, `{"name":"upload_expired"}`)

	wantError(t, f.putReq(id, []byte("not a jpeg")), 400, "INVALID_SELFIE")
	wantError(t, f.putReq(id, bigJPEG(500)), 413, "SELFIE_TOO_LARGE")
	if st, _ := f.state(t, id); st != "AWAITING_UPLOAD" {
		t.Fatalf("status = %s", st)
	}
	if got := f.faultTimes(t, "upload_expired"); got != 1 {
		t.Fatalf("times = %d, want 1", got)
	}
	wantError(t, f.putReq(id, jpegBytes), 410, "UPLOAD_EXPIRED") // 合法上傳才觸發
	if st, _ := f.state(t, id); st != "EXPIRED" {
		t.Fatalf("status = %s", st)
	}
}

func TestUploadExpiredNotConsumedForNonUploadableStatuses(t *testing.T) {
	for _, st := range []string{"QUEUED", "SUCCEEDED", "FAILED", "EXPIRED"} {
		t.Run(st, func(t *testing.T) {
			f := newDebugVfx(t)
			id := f.createUploaded(t, "k") // 先建立再登記：直接呼叫 svc 也會經過 hook
			f.setStatus(t, id, st)
			f.arm(t, `{"name":"upload_expired"}`)
			wantPut, wantSubmit := 409, 200
			if st == "EXPIRED" {
				wantPut, wantSubmit = 410, 410
			}
			if r := f.putReq(id, jpegBytes); r.Code != wantPut {
				t.Fatalf("put = %d %v, want %d", r.Code, r.body, wantPut)
			}
			if r := f.submit(id); r.Code != wantSubmit {
				t.Fatalf("submit = %d %v, want %d", r.Code, r.body, wantSubmit)
			}
			if got := f.faultTimes(t, "upload_expired"); got != 1 {
				t.Fatalf("times = %d, want 1", got)
			}
			if got, _ := f.state(t, id); got != st {
				t.Fatalf("status became %s", got)
			}
		})
	}
}

func TestUploadExpiredOnlyAffectsTargetedCameo(t *testing.T) {
	f := newDebugVfx(t)
	a := f.create(t, "a")
	b := f.create(t, "b")
	f.arm(t, `{"name":"upload_expired"}`)
	wantError(t, f.putReq(b, jpegBytes), 410, "UPLOAD_EXPIRED")
	if st, ps := f.state(t, a); st != "AWAITING_UPLOAD" || ps != "HELD" {
		t.Fatalf("A = %s/%s", st, ps)
	}
	if st, _ := f.state(t, b); st != "EXPIRED" {
		t.Fatalf("B = %s", st)
	}
	if r := f.putReq(a, jpegBytes); r.Code != 200 {
		t.Fatalf("A upload = %d", r.Code)
	}
}

// 沒有 WithDebugAPI：不能登記異常，上傳與送出完全不受影響。
func TestFaultsHaveNoEffectWithoutDebugAPI(t *testing.T) {
	f := newFx(t, 0)
	if r := send(f.h, http.MethodPost, "/debug/faults", []byte(`{"name":"upload_expired"}`), true, nil); r.Code != 404 {
		t.Fatalf("register without debug = %d", r.Code)
	}
	id := f.create(t, "k")
	if r := f.put(id, jpegBytes); r.Code != 200 {
		t.Fatalf("put = %d", r.Code)
	}
	if r := f.submit(id); r.Code != 200 {
		t.Fatalf("submit = %d", r.Code)
	}
}

// ---------- upload_interrupted：after_bytes 的值 ----------
// 以下都用固定長度 body（bytes.Reader，帶 Content-Length）。bigJPEG(n) 長度為 n+4。

func (f *vfx) putStatus(t *testing.T, l *liveSrv, id string, body []byte) int {
	t.Helper()
	code, err := l.do(http.MethodPut, "/v1/cameos/"+id+"/selfie", "", body)
	if err != nil {
		t.Fatalf("PUT len=%d: unexpected connection error %v", len(body), err)
	}
	return code
}

func TestUploadInterruptedAfterBytesBoundary(t *testing.T) {
	cases := []struct {
		name   string
		params string
		size   int // 內容總長度
		lost   bool
	}{
		{"100 equal", `{"after_bytes":100}`, 100, false},
		{"100 plus one", `{"after_bytes":100}`, 101, true},
		{"default equal", ``, 1024, false},
		{"default plus one", ``, 1025, true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			f := newDebugVfx(t)
			l := f.live(t)
			id := f.create(t, "k")
			arm := `{"name":"upload_interrupted"}`
			if c.params != "" {
				arm = `{"name":"upload_interrupted","params":` + c.params + `}`
			}
			f.arm(t, arm)
			body := bigJPEG(c.size - 4)
			path := "/v1/cameos/" + id + "/selfie"
			if c.lost {
				l.wantLost(t, http.MethodPut, path, "", body)
				if got := f.faultTimes(t, "upload_interrupted"); got != 0 {
					t.Fatalf("times = %d, want consumed", got)
				}
				f.wantNoFiles(t)
				return
			}
			if code := f.putStatus(t, l, id, body); code != 200 {
				t.Fatalf("PUT = %d, want 200", code)
			}
			if got := f.faultTimes(t, "upload_interrupted"); got != 1 {
				t.Fatalf("times = %d, want 1 (not consumed)", got)
			}
		})
	}
}

func TestUploadInterruptedTinyAfterBytesStillValidatesContentFirst(t *testing.T) {
	for _, n := range []int{1, 2} {
		f := newDebugVfx(t)
		l := f.live(t)
		id := f.create(t, "k")
		f.arm(t, `{"name":"upload_interrupted","params":{"after_bytes":`+string(rune('0'+n))+`}}`)
		path := "/v1/cameos/" + id + "/selfie"
		gif := append([]byte("GIF89a"), bytes.Repeat([]byte{1}, 50)...)

		code, err := l.do(http.MethodPut, path, "", gif)
		if err != nil || code != 400 {
			t.Fatalf("after_bytes=%d: code=%d err=%v, want 400", n, code, err)
		}
		wantError(t, f.putReq(id, gif), 400, "INVALID_SELFIE")
		if got := f.faultTimes(t, "upload_interrupted"); got != 1 {
			t.Fatalf("after_bytes=%d: times = %d, want 1", n, got)
		}
		if st, _ := f.state(t, id); st != "AWAITING_UPLOAD" {
			t.Fatalf("after_bytes=%d: status = %s", n, st)
		}
	}
}

func TestUploadInterruptedTinyAfterBytesInterruptsValidJPEG(t *testing.T) {
	for _, n := range []int{1, 2} {
		f := newDebugVfx(t)
		l := f.live(t)
		id := f.create(t, "k")
		f.arm(t, `{"name":"upload_interrupted","params":{"after_bytes":`+string(rune('0'+n))+`}}`)

		l.wantLost(t, http.MethodPut, "/v1/cameos/"+id+"/selfie", "", bigJPEG(50))
		if got := f.faultTimes(t, "upload_interrupted"); got != 0 {
			t.Fatalf("after_bytes=%d: times = %d, want consumed", n, got)
		}
		if st, _ := f.state(t, id); st != "AWAITING_UPLOAD" {
			t.Fatalf("after_bytes=%d: status = %s", n, st)
		}
		f.wantNoFiles(t)
	}
}

func TestUploadInterruptedListShowsConfiguredAfterBytes(t *testing.T) {
	f := newDebugVfx(t)
	f.arm(t, `{"name":"upload_interrupted","params":{"after_bytes":1}}`)
	for _, e := range f.activeFaults(t) {
		m := e.(map[string]any)
		if m["name"] == "upload_interrupted" {
			if got := m["params"].(map[string]any)["after_bytes"]; got != float64(1) {
				t.Fatalf("after_bytes = %v, want 1", got)
			}
			return
		}
	}
	t.Fatal("upload_interrupted not listed")
}

// 內容恰為 3 bytes（FF D8 FF）：UploadSelfie 讀前 3 bytes 時底層回 (3, EOF)，
// 之後 WriteTemp 再讀不可被當成「已讀滿 after_bytes」而中斷。
func TestUploadInterruptedContentEqualToEffectiveLimitSucceeds(t *testing.T) {
	for _, n := range []int{1, 2, 3} {
		f := newDebugVfx(t)
		l := f.live(t)
		id := f.create(t, "k")
		f.arm(t, `{"name":"upload_interrupted","params":{"after_bytes":`+string(rune('0'+n))+`}}`)

		if code := f.putStatus(t, l, id, []byte{0xFF, 0xD8, 0xFF}); code != 200 {
			t.Fatalf("after_bytes=%d: PUT = %d, want 200", n, code)
		}
		if got := f.faultTimes(t, "upload_interrupted"); got != 1 {
			t.Fatalf("after_bytes=%d: times = %d, want 1 (not consumed)", n, got)
		}
		if st, _ := f.state(t, id); st != "UPLOADED" {
			t.Fatalf("after_bytes=%d: status = %s, want UPLOADED", n, st)
		}
	}
}

func TestUploadInterruptedOneByteOverEffectiveLimitInterrupts(t *testing.T) {
	f := newDebugVfx(t)
	l := f.live(t)
	id := f.create(t, "k")
	f.arm(t, `{"name":"upload_interrupted","params":{"after_bytes":3}}`)

	l.wantLost(t, http.MethodPut, "/v1/cameos/"+id+"/selfie", "", []byte{0xFF, 0xD8, 0xFF, 0xE0})
	if got := f.faultTimes(t, "upload_interrupted"); got != 0 {
		t.Fatalf("times = %d, want consumed", got)
	}
	if st, _ := f.state(t, id); st != "AWAITING_UPLOAD" {
		t.Fatalf("status = %s", st)
	}
	f.wantNoFiles(t)
}
