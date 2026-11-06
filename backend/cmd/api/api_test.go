package main

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"reflect"
	"runtime"
	"sort"
	"strings"
	"testing"
	"time"

	apihttp "cameo/backend/internal/adapter/http"
	"cameo/backend/internal/adapter/postgres"
	"cameo/backend/internal/testdb"
	"cameo/backend/internal/usecase"
)

// 組裝真實 Postgres 與 HTTP，驗證對外契約。
func newHandler(t *testing.T, limit int) (http.Handler, *testdb.Clock) {
	t.Helper()
	pool := testdb.New(t)
	clock := testdb.NewClock(time.Date(2026, 11, 6, 2, 0, 0, 0, time.UTC))
	svc := usecase.NewService(postgres.NewQueries(pool), postgres.NewTxRunner(pool), clock,
		usecase.Config{DailyLimit: limit, UploadTTL: time.Hour})
	return apihttp.Handler(svc), clock
}

type resp struct {
	*httptest.ResponseRecorder
	body map[string]any
}

func call(t *testing.T, h http.Handler, method, path, key, body string) resp {
	t.Helper()
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	req.Header.Set("Authorization", "Bearer test-token")
	if key != "" {
		req.Header.Set("Idempotency-Key", key)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	var m map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &m); err != nil {
		t.Fatalf("%s %s: not JSON: %q", method, path, rec.Body.String())
	}
	return resp{rec, m}
}

func keys(m map[string]any) []string {
	k := make([]string, 0, len(m))
	for key := range m {
		k = append(k, key)
	}
	sort.Strings(k)
	return k
}

func wantError(t *testing.T, r resp, status int, code string) {
	t.Helper()
	if r.Code != status || r.body["code"] != code || !reflect.DeepEqual(keys(r.body), []string{"code", "message", "retryable"}) {
		t.Fatalf("got %d %v, want %d %s", r.Code, r.body, status, code)
	}
}

func TestCreateCameoHTTP(t *testing.T) {
	h, _ := newHandler(t, 3)
	r := call(t, h, http.MethodPost, "/v1/cameos", "k1", `{"clip_id":"c1"}`)
	if r.Code != 201 || !reflect.DeepEqual(keys(r.body), []string{"cameo", "upload"}) {
		t.Fatalf("create: %d %v", r.Code, r.body)
	}
	cameo := r.body["cameo"].(map[string]any)
	wantKeys := []string{"clip", "created_at", "display", "failure_reason", "id", "points_state", "share", "status", "updated_at", "video_available"}
	if !reflect.DeepEqual(keys(cameo), wantKeys) {
		t.Fatalf("cameo keys = %v", keys(cameo))
	}
	if !reflect.DeepEqual(cameo["clip"], map[string]any{"id": "c1", "title": "雨夜重逢", "cost": 10.0}) ||
		cameo["status"] != "AWAITING_UPLOAD" || cameo["points_state"] != "HELD" ||
		cameo["failure_reason"] != nil || cameo["video_available"] != false ||
		!reflect.DeepEqual(cameo["share"], map[string]any{"active": false, "url": nil}) ||
		!reflect.DeepEqual(cameo["display"], map[string]any{"title": "等待上傳", "message": "已占用 10 點，送出後才扣款"}) ||
		cameo["created_at"] != "2026-11-06T10:00:00+08:00" {
		t.Fatalf("cameo = %v", cameo)
	}
	id := cameo["id"].(string)
	upload := r.body["upload"].(map[string]any)
	if upload["expires_at"] != "2026-11-06T11:00:00+08:00" || upload["url"] != "/v1/cameos/"+id+"/selfie" {
		t.Fatalf("upload = %v", upload)
	}

	again := call(t, h, http.MethodPost, "/v1/cameos", "k1", `{"clip_id":"c1"}`)
	if again.Code != 200 || !reflect.DeepEqual(again.body, r.body) {
		t.Fatalf("replay: %d %v", again.Code, again.body)
	}
	wantError(t, call(t, h, http.MethodPost, "/v1/cameos", "k1", `{"clip_id":"c2"}`), 409, "IDEMPOTENCY_CONFLICT")

	got := call(t, h, http.MethodGet, "/v1/cameos/"+id, "", "")
	if got.Code != 200 || !reflect.DeepEqual(got.body, r.body) {
		t.Fatalf("get: %d %v", got.Code, got.body)
	}
	wantError(t, call(t, h, http.MethodGet, "/v1/cameos/nope", "", ""), 404, "CAMEO_NOT_FOUND")

	p := call(t, h, http.MethodGet, "/v1/points", "", "")
	if !reflect.DeepEqual(p.body, map[string]any{"available": 90.0, "held": 10.0}) {
		t.Fatalf("points = %v", p.body)
	}
	q := call(t, h, http.MethodGet, "/v1/quota", "", "")
	if !reflect.DeepEqual(q.body, map[string]any{"remaining": 2.0, "resets_at": "2026-11-07T00:00:00+08:00", "display": "今日還可客串 2 次"}) {
		t.Fatalf("quota = %v", q.body)
	}
}

func TestCreateCameoErrorsHTTP(t *testing.T) {
	h, _ := newHandler(t, 3)
	wantError(t, call(t, h, http.MethodPost, "/v1/cameos", "", `{"clip_id":"c1"}`), 400, "INVALID_REQUEST")
	wantError(t, call(t, h, http.MethodPost, "/v1/cameos", "   ", `{"clip_id":"c1"}`), 400, "INVALID_REQUEST")
	wantError(t, call(t, h, http.MethodPost, "/v1/cameos", "k", `not json`), 400, "INVALID_REQUEST")
	wantError(t, call(t, h, http.MethodPost, "/v1/cameos", "k", `{"clip_id":"zzz"}`), 404, "CLIP_NOT_FOUND")
	for _, k := range []string{"a", "b", "c"} {
		if r := call(t, h, http.MethodPost, "/v1/cameos", k, `{"clip_id":"c1"}`); r.Code != 201 {
			t.Fatalf("create %s: %d", k, r.Code)
		}
	}
	r := call(t, h, http.MethodPost, "/v1/cameos", "d", `{"clip_id":"c1"}`)
	wantError(t, r, 429, "DAILY_LIMIT_REACHED")
	if r.body["message"] != "今日客串次數已達上限，明天再來" {
		t.Errorf("message = %v", r.body["message"])
	}
	q := call(t, h, http.MethodGet, "/v1/quota", "", "")
	if q.body["remaining"] != 0.0 || q.body["display"] != "今日客串次數已用完" {
		t.Errorf("quota = %v", q.body)
	}

	h2, _ := newHandler(t, 100)
	for i := 0; i < 10; i++ {
		key := string(rune('a' + i))
		if r := call(t, h2, http.MethodPost, "/v1/cameos", key, `{"clip_id":"c1"}`); r.Code != 201 {
			t.Fatalf("create %s: %d", key, r.Code)
		}
	}
	r = call(t, h2, http.MethodPost, "/v1/cameos", "z", `{"clip_id":"c1"}`)
	wantError(t, r, 402, "INSUFFICIENT_POINTS")
	if r.body["message"] != "點數不足" {
		t.Errorf("message = %v", r.body["message"])
	}
}

func TestExpiredDisplayHTTP(t *testing.T) {
	pool := testdb.New(t)
	clock := testdb.NewClock(time.Date(2026, 11, 6, 2, 0, 0, 0, time.UTC))
	svc := usecase.NewService(postgres.NewQueries(pool), postgres.NewTxRunner(pool), clock,
		usecase.Config{DailyLimit: 3, UploadTTL: time.Hour})
	h := apihttp.Handler(svc)
	r := call(t, h, http.MethodPost, "/v1/cameos", "k", `{"clip_id":"c1"}`)
	id := r.body["cameo"].(map[string]any)["id"].(string)
	clock.Advance(time.Hour)
	if n, err := svc.ExpireDue(t.Context()); err != nil || n != 1 {
		t.Fatalf("expire %d %v", n, err)
	}
	c := call(t, h, http.MethodGet, "/v1/cameos/"+id, "", "").body["cameo"].(map[string]any)
	if c["status"] != "EXPIRED" || c["points_state"] != "RELEASED" ||
		!reflect.DeepEqual(c["display"], map[string]any{"title": "已過期", "message": "上傳期限已過，點數沒有扣款，請重新客串。"}) {
		t.Fatalf("cameo = %v", c)
	}
}

func TestRoutingAndAuthHTTP(t *testing.T) {
	h, _ := newHandler(t, 3)
	do := func(method, path, auth string) resp {
		req := httptest.NewRequest(method, path, strings.NewReader(`{"clip_id":"c1"}`))
		if auth != "" {
			req.Header.Set("Authorization", auth)
		}
		req.Header.Set("Idempotency-Key", "k")
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		var m map[string]any
		_ = json.Unmarshal(rec.Body.Bytes(), &m)
		return resp{rec, m}
	}
	for _, c := range []struct{ method, path string }{
		{"POST", "/v1/cameos"}, {"GET", "/v1/cameos/x"}, {"GET", "/v1/quota"},
	} {
		for _, auth := range []string{"", "Bearer wrong"} {
			wantError(t, do(c.method, c.path, auth), 401, "UNAUTHORIZED")
		}
	}
	for _, c := range []struct{ method, path, allow string }{
		{"GET", "/v1/cameos", "POST"},
		{"PUT", "/v1/cameos", "POST"},
		{"DELETE", "/v1/cameos/x", "GET, HEAD"},
		{"POST", "/v1/quota", "GET, HEAD"},
	} {
		r := do(c.method, c.path, "Bearer test-token")
		wantError(t, r, 405, "METHOD_NOT_ALLOWED")
		if r.Header().Get("Allow") != c.allow {
			t.Errorf("%s %s Allow = %q, want %q", c.method, c.path, r.Header().Get("Allow"), c.allow)
		}
		// 沒帶 token 時方法不對仍是 405。
		wantError(t, do(c.method, c.path, ""), 405, "METHOD_NOT_ALLOWED")
	}
	wantError(t, do("GET", "/v1/nope", ""), 404, "NOT_FOUND")
}

func TestUploadFieldHTTP(t *testing.T) {
	pool := testdb.New(t)
	clock := testdb.NewClock(time.Date(2026, 11, 6, 2, 0, 0, 0, time.UTC))
	svc := usecase.NewService(postgres.NewQueries(pool), postgres.NewTxRunner(pool), clock,
		usecase.Config{DailyLimit: 3, UploadTTL: time.Hour})
	h := apihttp.Handler(svc)

	created := call(t, h, http.MethodPost, "/v1/cameos", "k", `{"clip_id":"c1"}`)
	id := created.body["cameo"].(map[string]any)["id"].(string)
	wantUpload := map[string]any{"url": "/v1/cameos/" + id + "/selfie", "expires_at": "2026-11-06T11:00:00+08:00"}
	if created.Code != 201 || !reflect.DeepEqual(created.body["upload"], wantUpload) {
		t.Fatalf("POST: %d %v", created.Code, created.body)
	}
	got := call(t, h, http.MethodGet, "/v1/cameos/"+id, "", "")
	if got.Code != 200 || !reflect.DeepEqual(keys(got.body), []string{"cameo", "upload"}) ||
		!reflect.DeepEqual(got.body["upload"], wantUpload) || got.body["cameo"].(map[string]any)["status"] != "AWAITING_UPLOAD" {
		t.Fatalf("GET awaiting: %d %v", got.Code, got.body)
	}

	clock.Advance(time.Hour)
	if n, err := svc.ExpireDue(context.Background()); err != nil || n != 1 {
		t.Fatalf("expire %d %v", n, err)
	}
	got = call(t, h, http.MethodGet, "/v1/cameos/"+id, "", "")
	if got.Code != 200 || !reflect.DeepEqual(keys(got.body), []string{"cameo", "upload"}) ||
		got.body["upload"] != nil || got.body["cameo"].(map[string]any)["status"] != "EXPIRED" {
		t.Fatalf("GET expired: %d %v", got.Code, got.body)
	}
	again := call(t, h, http.MethodPost, "/v1/cameos", "k", `{"clip_id":"c1"}`)
	if again.Code != 200 || !reflect.DeepEqual(keys(again.body), []string{"cameo", "upload"}) ||
		again.body["upload"] != nil || !reflect.DeepEqual(again.body["cameo"], got.body["cameo"]) {
		t.Fatalf("replay expired: %d %v", again.Code, again.body)
	}
}

func TestConflictAndLimitPrecedenceHTTP(t *testing.T) {
	h, _ := newHandler(t, 1)
	if r := call(t, h, http.MethodPost, "/v1/cameos", "k", `{"clip_id":"c1"}`); r.Code != 201 {
		t.Fatalf("create: %d", r.Code)
	}
	wantError(t, call(t, h, http.MethodPost, "/v1/cameos", "k", `{"clip_id":"nope"}`), 409, "IDEMPOTENCY_CONFLICT")

	// 額度和點數同時用完時回 429，不是 402。
	pool := testdb.New(t)
	svc := usecase.NewService(postgres.NewQueries(pool), postgres.NewTxRunner(pool),
		testdb.NewClock(time.Date(2026, 11, 6, 2, 0, 0, 0, time.UTC)), usecase.Config{DailyLimit: 1, UploadTTL: time.Hour})
	if _, err := pool.Exec(context.Background(), `UPDATE points_account SET balance = 10`); err != nil {
		t.Fatal(err)
	}
	h2 := apihttp.Handler(svc)
	if r := call(t, h2, http.MethodPost, "/v1/cameos", "a", `{"clip_id":"c1"}`); r.Code != 201 {
		t.Fatalf("create: %d", r.Code)
	}
	wantError(t, call(t, h2, http.MethodPost, "/v1/cameos", "b", `{"clip_id":"c1"}`), 429, "DAILY_LIMIT_REACHED")
}

func TestInvalidInputHTTP(t *testing.T) {
	h, _ := newHandler(t, 3)
	body := `{"clip_id":"c1"}`
	bad := []string{
		"", "   ", "\t \t",
		strings.Repeat("a", 129),
		"a b", "a\tb", "a\x00b", "a\xffb", "鍵", "a\x7fb", "a\x01b", "a\nb",
	}
	for _, k := range bad {
		r := call(t, h, http.MethodPost, "/v1/cameos", k, body)
		wantError(t, r, 400, "INVALID_REQUEST")
		if r.body["retryable"] != false {
			t.Errorf("key %q retryable = %v", k, r.body["retryable"])
		}
	}
	if n := call(t, h, http.MethodGet, "/v1/points", "", "").body["held"]; n != 0.0 {
		t.Fatalf("held = %v after invalid keys", n)
	}
	// 邊界：128 bytes 與前後空白會被去掉。
	for _, k := range []string{strings.Repeat("a", 128), "  trimmed  ", "~!"} {
		if r := call(t, h, http.MethodPost, "/v1/cameos", k, body); r.Code != 201 {
			t.Errorf("key %q: %d %v", k, r.Code, r.body)
		}
	}

	for _, id := range []string{"a%00b", "%00", "a%FFb", "%FF"} {
		wantError(t, call(t, h, http.MethodGet, "/v1/cameos/"+id, "", ""), 404, "CAMEO_NOT_FOUND")
	}
	for _, b := range []string{`{"clip_id":"a\u0000b"}`, `{"clip_id":"\u0000"}`, "{\"clip_id\":\"a\xffb\"}"} {
		wantError(t, call(t, h, http.MethodPost, "/v1/cameos", "newkey", b), 404, "CLIP_NOT_FOUND")
	}
	// 同 key 情況仍照既有順序。
	wantError(t, call(t, h, http.MethodPost, "/v1/cameos", "trimmed", `{"clip_id":"a\u0000b"}`), 409, "IDEMPOTENCY_CONFLICT")
}

// 逾期訂單：建單後把時鐘推過期限。
func expiredOrder(t *testing.T) (*usecase.Service, *testdb.Clock) {
	t.Helper()
	pool := testdb.New(t)
	clock := testdb.NewClock(time.Date(2026, 11, 6, 2, 0, 0, 0, time.UTC))
	svc := usecase.NewService(postgres.NewQueries(pool), postgres.NewTxRunner(pool), clock,
		usecase.Config{DailyLimit: 3, UploadTTL: time.Hour})
	if _, err := svc.CreateCameo(context.Background(), "k", "c1"); err != nil {
		t.Fatal(err)
	}
	clock.Advance(2 * time.Hour)
	return svc, clock
}

func heldPoints(t *testing.T, svc *usecase.Service) int {
	t.Helper()
	p, err := svc.Points(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	return p.Held
}

func waitReleased(t *testing.T, svc *usecase.Service, within time.Duration) {
	t.Helper()
	deadline := time.Now().Add(within)
	for heldPoints(t, svc) != 0 {
		if time.Now().After(deadline) {
			t.Fatalf("order not released within %v", within)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// 真實 Postgres、短 interval：ticker 掃描會釋放後來才逾期的訂單。
func TestSweepExpiredTicker(t *testing.T) {
	svc, clock := expiredOrder(t)
	clock.Set(time.Date(2026, 11, 6, 2, 30, 0, 0, time.UTC)) // 尚未逾期
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { sweepExpired(ctx, svc, 20*time.Millisecond); close(done) }()
	time.Sleep(100 * time.Millisecond)
	if h := heldPoints(t, svc); h != 10 {
		t.Fatalf("held before expiry = %d", h)
	}
	clock.Advance(2 * time.Hour)
	waitReleased(t, svc, 3*time.Second)
	cancel()
	<-done
}

// 啟動時先掃一次：interval 很長也要立刻釋放。
func TestSweepExpiredScansImmediately(t *testing.T) {
	svc, _ := expiredOrder(t)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { sweepExpired(ctx, svc, time.Hour); close(done) }()
	waitReleased(t, svc, 3*time.Second)
	cancel()
	<-done
}

// 接線測試：serve 必須啟動 sweeper（interval 很長，只靠啟動時的掃描）。
func TestServeStartsSweeper(t *testing.T) {
	svc, _ := expiredOrder(t)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- serve(ctx, svc, "127.0.0.1:0", time.Hour) }()
	waitReleased(t, svc, 3*time.Second)
	cancel()
	if err := <-done; err != nil {
		t.Fatalf("serve: %v", err)
	}
}

// port 被占用時 serve 立刻回傳錯誤，且 sweeper goroutine 要隨之結束。
func TestServeCancelsSweeperWhenListenFails(t *testing.T) {
	pool := testdb.New(t)
	svc := usecase.NewService(postgres.NewQueries(pool), postgres.NewTxRunner(pool), usecase.SystemClock{},
		usecase.Config{DailyLimit: 3, UploadTTL: time.Hour})
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()

	if err := serve(context.Background(), svc, ln.Addr().String(), time.Hour); err == nil {
		t.Fatal("serve returned nil on occupied port")
	}
	deadline := time.Now().Add(3 * time.Second)
	for {
		buf := make([]byte, 1<<20)
		buf = buf[:runtime.Stack(buf, true)]
		if !strings.Contains(string(buf), ".sweepExpired") {
			return
		}
		if time.Now().After(deadline) {
			t.Fatal("sweepExpired goroutine still running after serve returned")
		}
		time.Sleep(20 * time.Millisecond)
	}
}
