package integration_test

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"reflect"
	"testing"
	"time"

	"cameo/backend/assets"
	apihttp "cameo/backend/internal/adapter/http"
	"cameo/backend/internal/adapter/postgres"
	"cameo/backend/internal/usecase"
)

// newDebugVfx 是 newVfx 加上 DAILY_LIMIT=3 與 /debug/*、範例影片、分享。
func newDebugVfx(t *testing.T) *vfx {
	t.Helper()
	f := newVfx(t)
	f.svc = newSvc(f.pool, f.clock, usecase.Config{DailyLimit: 3, UploadTTL: ttl, GenerationTTL: 30 * time.Minute}, newFiles2(t, f.dir)).WithVendor(f.vendor)
	f.h = f.handler(f.svc)
	return f
}

func (f *vfx) handler(svc *usecase.Service) http.Handler {
	return apihttp.Handler(svc, apihttp.WithVendorWebhook([]byte(webhookSecret)), apihttp.WithSampleVideo(assets.SampleVideo),
		apihttp.WithPublicBaseURL(testBaseURL), apihttp.WithDebugAPI())
}

func (f *vfx) debugPost(path, body string) resp {
	return send(f.h, http.MethodPost, path, []byte(body), true, nil)
}

func (f *vfx) activeFaults(t *testing.T) []any {
	t.Helper()
	r := send(f.h, http.MethodGet, "/debug/faults", nil, true, nil)
	if r.Code != 200 {
		t.Fatalf("GET faults = %d", r.Code)
	}
	return r.body["active"].([]any)
}

func (f *vfx) getPoints(t *testing.T) (int, int) {
	t.Helper()
	r := send(f.h, http.MethodGet, "/v1/points", nil, true, nil)
	return int(r.body["available"].(float64)), int(r.body["held"].(float64))
}

func (f *vfx) remaining(t *testing.T) int {
	t.Helper()
	return int(send(f.h, http.MethodGet, "/v1/quota", nil, true, nil).body["remaining"].(float64))
}

// seedData 製造：已成功並分享的訂單 A、已失敗退款的訂單 B、已上傳的訂單 C（有自拍檔）、新建的訂單 D，
// A、C、D 用完 DAILY_LIMIT；另登記一個異常。回傳三個 id 與 A 的分享 token。
func (f *vfx) seedData(t *testing.T) (a, b, c, token string) {
	t.Helper()
	a = f.succeeded(t, "a")
	token = tokenOf(t, f.shareURL(t, a))
	b = f.generating(t, "b")
	if r := f.signed(failBody("e-fail-b", b, "")); r.Code != 200 {
		t.Fatalf("fail webhook = %d", r.Code)
	}
	c = f.createUploaded(t, "c")
	f.create(t, "d") // 失敗退款的 B 不占額度；A、C、D 用完 DAILY_LIMIT
	if r := f.debugPost("/debug/faults", `{"name":"upload_expired"}`); r.Code != 200 {
		t.Fatalf("register = %d", r.Code)
	}
	if _, ok := f.selfie(t, c); !ok {
		t.Fatal("setup: selfie of c missing")
	}
	if n := count(t, f.pool, "ledger_entries"); n != 3 { // A CHARGE, B CHARGE + REFUND
		t.Fatalf("setup: ledger = %d", n)
	}
	if n := count(t, f.pool, "vendor_events"); n != 2 {
		t.Fatalf("setup: vendor_events = %d", n)
	}
	if got := f.remaining(t); got != 0 {
		t.Fatalf("setup: remaining = %d, want 0", got)
	}
	return
}

func TestDebugResetClearsEverything(t *testing.T) {
	f := newDebugVfx(t)
	a, _, c, token := f.seedData(t)
	if avail, held := f.getPoints(t); avail != 70 || held != 20 {
		t.Fatalf("before: points = %d/%d", avail, held)
	}

	r := f.debugPost("/debug/reset", `ignored garbage {`) // body 不讀
	if r.Code != 204 || r.Body.Len() != 0 {
		t.Fatalf("reset = %d %q", r.Code, r.Body.String())
	}

	if avail, held := f.getPoints(t); avail != 100 || held != 0 {
		t.Fatalf("after: points = %d/%d, want 100/0", avail, held)
	}
	if got := f.remaining(t); got != 3 {
		t.Fatalf("remaining = %d, want 3", got)
	}
	if r := send(f.h, http.MethodGet, "/v1/cameos", nil, true, nil); !reflect.DeepEqual(r.body["items"], []any{}) {
		t.Fatalf("cameos = %v", r.body)
	}
	if r := send(f.h, http.MethodGet, "/v1/points/ledger", nil, true, nil); !reflect.DeepEqual(r.body["items"], []any{}) {
		t.Fatalf("ledger = %v", r.body)
	}
	if r := send(f.h, http.MethodGet, "/v1/clips", nil, true, nil); len(r.body["items"].([]any)) != 3 {
		t.Fatalf("clips = %v", r.body)
	}
	for _, table := range []string{"cameos", "ledger_entries", "vendor_events"} {
		if n := count(t, f.pool, table); n != 0 {
			t.Errorf("%s = %d", table, n)
		}
	}
	if got := f.activeFaults(t); len(got) != 0 {
		t.Fatalf("faults = %v", got)
	}
	f.wantNoFiles(t)
	if _, ok := f.selfie(t, c); ok {
		t.Fatal("selfie survived reset")
	}
	wantError(t, send(f.h, http.MethodGet, "/s/"+token+"/video", nil, false, nil), 404, "NOT_FOUND")
	wantHTML(t, send(f.h, http.MethodGet, "/s/"+token, nil, false, nil), 404)
	wantError(t, send(f.h, http.MethodGet, "/v1/cameos/"+a, nil, true, nil), 404, "CAMEO_NOT_FOUND")

	// 重複 reset 仍 204。
	if r := f.debugPost("/debug/reset", ""); r.Code != 204 {
		t.Fatalf("second reset = %d", r.Code)
	}
}

func TestDebugResetThenCreateUpToDailyLimit(t *testing.T) {
	f := newDebugVfx(t)
	f.seedData(t)
	if r := f.debugPost("/debug/reset", ""); r.Code != 204 {
		t.Fatalf("reset = %d", r.Code)
	}
	for i, key := range []string{"n1", "n2", "n3"} {
		r := call(t, f.h, http.MethodPost, "/v1/cameos", key, `{"clip_id":"c1"}`)
		if r.Code != 201 {
			t.Fatalf("create #%d = %d %v", i, r.Code, r.body)
		}
	}
	wantError(t, call(t, f.h, http.MethodPost, "/v1/cameos", "n4", `{"clip_id":"c1"}`), 429, "DAILY_LIMIT_REACHED")
}

func TestDebugResetAllowsOldEventIDWithNewOrder(t *testing.T) {
	f := newDebugVfx(t)
	f.seedData(t) // 用過 event_id e-a、e-fail-b
	if r := f.debugPost("/debug/reset", ""); r.Code != 204 {
		t.Fatalf("reset = %d", r.Code)
	}
	id := f.generating(t, "again")
	if r := f.signed(okBody("e-a", id)); r.Code != 200 {
		t.Fatalf("webhook = %d %v", r.Code, r.body)
	}
	if st, _ := f.state(t, id); st != "SUCCEEDED" {
		t.Fatalf("status = %s, old event_id was still deduplicated", st)
	}
}

func TestDebugResetLateCallbackDoesNotResurrect(t *testing.T) {
	f := newDebugVfx(t)
	_, b, g, _ := f.seedData(t) // g 是已上傳（尚未送出）的訂單
	if r := f.debugPost("/debug/reset", ""); r.Code != 204 {
		t.Fatalf("reset = %d", r.Code)
	}
	wantError(t, f.signed(okBody("e-late", g)), 404, "CAMEO_NOT_FOUND")
	wantError(t, f.signed(failBody("e-late2", b, "")), 404, "CAMEO_NOT_FOUND")
	for _, table := range []string{"cameos", "vendor_events", "ledger_entries"} {
		if n := count(t, f.pool, table); n != 0 {
			t.Errorf("%s = %d after late callbacks", table, n)
		}
	}
	if avail, held := f.getPoints(t); avail != 100 || held != 0 {
		t.Fatalf("points = %d/%d", avail, held)
	}
}

// seed_balance 由 migration 設定，reset 把餘額設回它，不寫死 100。
func TestDebugResetUsesSeedBalance(t *testing.T) {
	f := newDebugVfx(t)
	var seed, bal int
	if err := f.pool.QueryRow(context.Background(), `SELECT seed_balance, balance FROM points_account`).Scan(&seed, &bal); err != nil || seed != bal {
		t.Fatalf("fresh migration: seed_balance = %d balance = %d err = %v", seed, bal, err)
	}
	if _, err := f.pool.Exec(context.Background(), `UPDATE points_account SET seed_balance = 55, balance = 7`); err != nil {
		t.Fatal(err)
	}
	if r := f.debugPost("/debug/reset", ""); r.Code != 204 {
		t.Fatalf("reset = %d", r.Code)
	}
	if got := f.balance(t); got != 55 {
		t.Fatalf("balance = %d, want 55", got)
	}
	if avail, held := f.getPoints(t); avail != 55 || held != 0 {
		t.Fatalf("points = %d/%d", avail, held)
	}
}

type resetFailTx struct{ usecase.Tx }

// ResetData 真的做完才回錯，讓交易必須整個回滾。
func (t resetFailTx) ResetData(ctx context.Context) ([]string, error) {
	if _, err := t.Tx.ResetData(ctx); err != nil {
		return nil, err
	}
	return nil, errors.New("injected failure after ResetData")
}

type resetFailRunner struct{ usecase.TxRunner }

func (r resetFailRunner) Run(ctx context.Context, fn func(usecase.Tx) error) error {
	return r.TxRunner.Run(ctx, func(tx usecase.Tx) error { return fn(resetFailTx{tx}) })
}

func TestDebugResetFailureChangesNothing(t *testing.T) {
	cancelled, cancel := context.WithCancel(context.Background())
	cancel()
	cases := map[string]func(t *testing.T, f *vfx) resp{
		"cancelled context": func(t *testing.T, f *vfx) resp {
			req := httptest.NewRequest(http.MethodPost, "/debug/reset", nil).WithContext(cancelled)
			req.Header.Set("Authorization", "Bearer test-token")
			rec := httptest.NewRecorder()
			f.h.ServeHTTP(rec, req)
			return resp{ResponseRecorder: rec, body: decodeBody(rec)}
		},
		"failure after deleting": func(t *testing.T, f *vfx) resp {
			svc := usecase.NewService(postgres.NewQueries(f.pool), resetFailRunner{postgres.NewTxRunner(f.pool)}, f.clock,
				usecase.Config{DailyLimit: 3, UploadTTL: ttl}, newFiles2(t, f.dir))
			h := f.handler(svc)
			// 異常登記在 handler 內，用這個 handler 重新登記。
			if r := send(h, http.MethodPost, "/debug/faults", []byte(`{"name":"upload_expired"}`), true, nil); r.Code != 200 {
				t.Fatalf("register = %d", r.Code)
			}
			r := send(h, http.MethodPost, "/debug/reset", nil, true, nil)
			if got := send(h, http.MethodGet, "/debug/faults", nil, true, nil).body["active"].([]any); len(got) != 1 {
				t.Fatalf("faults = %v, want kept", got)
			}
			return r
		},
	}
	for name, run := range cases {
		t.Run(name, func(t *testing.T) {
			f := newDebugVfx(t)
			_, _, c, _ := f.seedData(t)
			before := f.balance(t)
			r := run(t, f)
			wantError(t, r, 500, "INTERNAL")
			if r.body["retryable"] != true {
				t.Fatalf("body = %v", r.body)
			}
			if n := count(t, f.pool, "cameos"); n != 4 {
				t.Errorf("cameos = %d, want 4", n)
			}
			if n := count(t, f.pool, "ledger_entries"); n != 3 {
				t.Errorf("ledger = %d, want 3", n)
			}
			if n := count(t, f.pool, "vendor_events"); n != 2 {
				t.Errorf("vendor_events = %d, want 2", n)
			}
			if got := f.balance(t); got != before {
				t.Errorf("balance = %d, want %d", got, before)
			}
			if _, ok := f.selfie(t, c); !ok {
				t.Error("selfie deleted although reset failed")
			}
			if name == "cancelled context" {
				if got := f.activeFaults(t); len(got) != 1 {
					t.Errorf("faults = %v, want kept", got)
				}
			}
		})
	}
}

func TestDebugRoutesAbsentWithoutOptionHTTP(t *testing.T) {
	f := newVfx(t) // 沒有 WithDebugAPI
	for _, r := range [][2]string{{"GET", "/debug/faults"}, {"POST", "/debug/faults"}, {"DELETE", "/debug/faults"}, {"POST", "/debug/reset"}} {
		wantError(t, send(f.h, r[0], r[1], nil, true, nil), 404, "NOT_FOUND")
	}
	if avail := f.balance(t); avail != 100 {
		t.Fatalf("balance = %d", avail)
	}
}

func TestMigration0007SeedBalanceDefault(t *testing.T) {
	pool := newDB(t)
	var seed int
	if err := pool.QueryRow(context.Background(), `SELECT seed_balance FROM points_account`).Scan(&seed); err != nil || seed != 100 {
		t.Fatalf("seed_balance = %d err = %v", seed, err)
	}
}

func decodeBody(rec *httptest.ResponseRecorder) map[string]any {
	var m map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &m)
	return m
}
