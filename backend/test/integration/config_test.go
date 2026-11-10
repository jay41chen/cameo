package integration_test

import (
	"context"
	"encoding/json"
	"net/http"
	"reflect"
	"strings"
	"testing"
	"time"

	"cameo/backend/internal/usecase"
)

func (f *vfx) cfgGet(t *testing.T) map[string]any {
	t.Helper()
	r := send(f.h, http.MethodGet, "/debug/config", nil, true, nil)
	if r.Code != 200 {
		t.Fatalf("GET config = %d %v", r.Code, r.body)
	}
	return r.body
}

func (f *vfx) cfgPut(body string) resp {
	return send(f.h, http.MethodPut, "/debug/config", []byte(body), true, nil)
}

func (f *vfx) genExpires(t *testing.T, id string) time.Time {
	t.Helper()
	var at time.Time
	if err := f.pool.QueryRow(context.Background(), `SELECT generation_expires_at FROM cameos WHERE id = $1`, id).Scan(&at); err != nil {
		t.Fatal(err)
	}
	return at
}

func wantCfg(t *testing.T, got map[string]any, limit, ttl float64, delay any) {
	t.Helper()
	want := map[string]any{"daily_limit": limit, "generation_ttl_sec": ttl, "vendor_delay_sec": delay}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("config = %v, want %v", got, want)
	}
}

func TestConfigGetInitial(t *testing.T) {
	f := newDebugVfx(t) // DailyLimit 3、GenerationTTL 30 分鐘
	wantCfg(t, f.cfgGet(t), 3, 1800, nil)
}

func TestConfigPutSubsetKeepsOthers(t *testing.T) {
	f := newDebugVfx(t)
	r := f.cfgPut(`{"generation_ttl_sec":120}`)
	if r.Code != 200 {
		t.Fatalf("put = %d %v", r.Code, r.body)
	}
	wantCfg(t, r.body, 3, 120, nil)
	wantCfg(t, f.cfgGet(t), 3, 120, nil)
	if r := f.cfgPut(`{"vendor_delay_sec":2.5}`); r.Code != 200 {
		t.Fatalf("put = %d", r.Code)
	}
	wantCfg(t, f.cfgGet(t), 3, 120, 2.5)
	if r := f.cfgPut(`{"vendor_delay_sec":null,"daily_limit":5}`); r.Code != 200 {
		t.Fatalf("put = %d", r.Code)
	}
	wantCfg(t, f.cfgGet(t), 5, 120, nil)
	// 範圍端點。
	for _, b := range []string{`{"daily_limit":1}`, `{"daily_limit":1000}`, `{"generation_ttl_sec":10}`, `{"generation_ttl_sec":1800}`, `{"vendor_delay_sec":0}`, `{"vendor_delay_sec":600}`} {
		if r := f.cfgPut(b); r.Code != 200 {
			t.Fatalf("put %s = %d %v", b, r.Code, r.body)
		}
	}
}

func TestConfigDailyLimitAffectsCreateAndQuota(t *testing.T) {
	f := newDebugVfx(t)
	f.create(t, "k1")
	f.create(t, "k2")
	if r := f.cfgPut(`{"daily_limit":2}`); r.Code != 200 {
		t.Fatalf("put = %d", r.Code)
	}
	if got := f.remaining(t); got != 0 {
		t.Fatalf("remaining = %d, want 0", got)
	}
	wantError(t, f.createReq("k3"), 429, "DAILY_LIMIT_REACHED")
	// 調低到小於已用次數：remaining 為 0，不為負。
	if r := f.cfgPut(`{"daily_limit":1}`); r.Code != 200 {
		t.Fatalf("put = %d", r.Code)
	}
	if got := f.remaining(t); got != 0 {
		t.Fatalf("remaining = %d, want 0", got)
	}
	wantError(t, f.createReq("k4"), 429, "DAILY_LIMIT_REACHED")
	// 調高後立即可建。
	if r := f.cfgPut(`{"daily_limit":4}`); r.Code != 200 {
		t.Fatalf("put = %d", r.Code)
	}
	if got := f.remaining(t); got != 2 {
		t.Fatalf("remaining = %d, want 2", got)
	}
	if r := f.createReq("k5"); r.Code != 201 {
		t.Fatalf("create = %d %v", r.Code, r.body)
	}
}

func TestConfigGenerationTTLOnlyAffectsLaterSubmits(t *testing.T) {
	f := newDebugVfx(t)
	before := f.queued(t, "before") // 預設 30 分鐘
	submitted := f.clock.Now()
	if got := f.genExpires(t, before); !got.Equal(submitted.Add(30 * time.Minute)) {
		t.Fatalf("before expires = %v", got)
	}
	if r := f.cfgPut(`{"generation_ttl_sec":120}`); r.Code != 200 {
		t.Fatalf("put = %d", r.Code)
	}
	f.clock.Advance(time.Minute)
	after := f.queued(t, "after")
	if got, want := f.genExpires(t, after), f.clock.Now().Add(120*time.Second); !got.Equal(want) {
		t.Fatalf("after expires = %v, want %v", got, want)
	}
	if got := f.genExpires(t, before); !got.Equal(submitted.Add(30 * time.Minute)) {
		t.Fatalf("before expires changed to %v", got)
	}
}

func TestConfigPutRejectsAndChangesNothing(t *testing.T) {
	f := newDebugVfx(t)
	if r := f.cfgPut(`{"vendor_delay_sec":7}`); r.Code != 200 {
		t.Fatalf("put = %d", r.Code)
	}
	invalid := []string{
		`{"daily_limit":0}`, `{"daily_limit":1001}`, `{"daily_limit":-1}`,
		`{"generation_ttl_sec":9}`, `{"generation_ttl_sec":1801}`,
		`{"vendor_delay_sec":-0.1}`, `{"vendor_delay_sec":600.5}`, `{"vendor_delay_sec":"5"}`, `{"vendor_delay_sec":true}`,
		`{}`, `{"unknown":1}`, `{"daily_limit":5,"unknown":1}`,
		`{"daily_limit":null}`, `{"generation_ttl_sec":null}`,
		`{"daily_limit":3.0}`, `{"daily_limit":3e0}`, `{"generation_ttl_sec":60.0}`, `{"daily_limit":"3"}`,
		`{"daily_limit":99999999999999999999}`,
		`{"daily_limit":5,"generation_ttl_sec":5}`,         // 多欄中一欄不合法
		`{"daily_limit":5,"vendor_delay_sec":"x"}`,         // 同上
		`{"generation_ttl_sec":60,"vendor_delay_sec":601}`, // 同上
		`{"daily_limit":5,"generation_ttl_sec":60,"vendor_delay_sec":null,"x":1}`,
	}
	for _, b := range invalid {
		wantError(t, f.cfgPut(b), 400, "INVALID_CONFIG")
		wantCfg(t, f.cfgGet(t), 3, 1800, 7.0)
	}
	for _, b := range []string{`null`, `[]`, `{"daily_limit":`, `not json`, ``, `"x"`, `{"daily_limit":5} {}`} {
		wantError(t, f.cfgPut(b), 400, "INVALID_REQUEST")
		wantCfg(t, f.cfgGet(t), 3, 1800, 7.0)
	}
}

func TestConfigRoutesAbsentWithoutDebug(t *testing.T) {
	f := newVfx(t)
	wantError(t, send(f.h, http.MethodGet, "/debug/config", nil, true, nil), 404, "NOT_FOUND")
	wantError(t, send(f.h, http.MethodPut, "/debug/config", []byte(`{"daily_limit":5}`), true, nil), 404, "NOT_FOUND")
}

func TestConfigRequiresToken(t *testing.T) {
	f := newDebugVfx(t)
	if r := send(f.h, http.MethodGet, "/debug/config", nil, false, nil); r.Code != 401 {
		t.Fatalf("no token = %d", r.Code)
	}
	if r := send(f.h, http.MethodDelete, "/debug/config", nil, true, nil); r.Code != 405 {
		t.Fatalf("DELETE = %d", r.Code)
	}
}

func TestConfigSurvivesReset(t *testing.T) {
	f := newDebugVfx(t)
	if r := f.cfgPut(`{"daily_limit":7,"generation_ttl_sec":90,"vendor_delay_sec":3}`); r.Code != 200 {
		t.Fatalf("put = %d", r.Code)
	}
	if r := f.debugPost("/debug/reset", ""); r.Code != 204 {
		t.Fatalf("reset = %d", r.Code)
	}
	wantCfg(t, f.cfgGet(t), 7, 90, 3.0)
	if got := f.remaining(t); got != 7 {
		t.Fatalf("remaining = %d, want 7", got)
	}
}

func TestConsumeCarriesVendorDelay(t *testing.T) {
	f := newDebugVfx(t)
	consume := func() map[string]any {
		r := f.debugPost("/debug/faults/consume", "")
		if r.Code != 200 {
			t.Fatalf("consume = %d", r.Code)
		}
		return r.body
	}
	// fault 為 null，延遲為 null。
	b := consume()
	if v, ok := b["vendor_delay_sec"]; !ok || v != nil || b["fault"] != nil {
		t.Fatalf("consume = %v", b)
	}
	// fault 為 null，延遲為數字。
	if r := f.cfgPut(`{"vendor_delay_sec":1.5}`); r.Code != 200 {
		t.Fatalf("put = %d", r.Code)
	}
	b = consume()
	if b["vendor_delay_sec"] != 1.5 || b["fault"] != nil {
		t.Fatalf("consume = %v", b)
	}
	// 有 fault，延遲為數字，與 fault 同層。
	if r := f.debugPost("/debug/faults", `{"name":"vendor_failed"}`); r.Code != 200 {
		t.Fatalf("register = %d", r.Code)
	}
	b = consume()
	if b["vendor_delay_sec"] != 1.5 || b["fault"] == nil || b["fault"].(map[string]any)["name"] != "vendor_failed" {
		t.Fatalf("consume = %v", b)
	}
	// 有 fault，延遲回到 null。
	if r := f.cfgPut(`{"vendor_delay_sec":null}`); r.Code != 200 {
		t.Fatalf("put = %d", r.Code)
	}
	f.debugPost("/debug/faults", `{"name":"vendor_no_callback"}`)
	b = consume()
	if v, ok := b["vendor_delay_sec"]; !ok || v != nil || b["fault"] == nil {
		t.Fatalf("consume = %v", b)
	}
}

func TestConfigNegativeZeroDelayNormalized(t *testing.T) {
	f := newDebugVfx(t)
	if r := f.cfgPut(`{"vendor_delay_sec":-0}`); r.Code != 200 {
		t.Fatalf("put = %d %v", r.Code, r.body)
	}
	r := send(f.h, http.MethodGet, "/debug/config", nil, true, nil)
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(r.Body.Bytes(), &raw); err != nil {
		t.Fatal(err)
	}
	if got := strings.TrimSpace(string(raw["vendor_delay_sec"])); got != "0" {
		t.Fatalf("vendor_delay_sec raw = %q, want 0", got)
	}
}

func TestConfigTTLTruncatedAndPartialPutKeepsIt(t *testing.T) {
	f := newVfx(t)
	f.svc = newSvc(f.pool, f.clock, usecase.Config{DailyLimit: 3, UploadTTL: ttl, GenerationTTL: time.Hour + 1500*time.Millisecond, MaxSelfieBytes: 100}, newFiles2(t, f.dir)).WithVendor(f.vendor)
	f.h = f.handler(f.svc)
	wantCfg(t, f.cfgGet(t), 3, 3601, nil)
	r := f.cfgPut(`{"daily_limit":5}`)
	if r.Code != 200 {
		t.Fatalf("put = %d %v", r.Code, r.body)
	}
	wantCfg(t, r.body, 5, 3601, nil)
	wantCfg(t, f.cfgGet(t), 5, 3601, nil)
}
