package http_test

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"testing"

	apihttp "cameo/backend/internal/adapter/http"
	"cameo/backend/internal/usecase"
)

func debugHandler(opts ...apihttp.Option) http.Handler {
	svc := usecase.NewService(fakeQueries{}, nil, fixedClock{}, usecase.Config{LedgerPageSize: 1, DailyLimit: 3}, nil)
	return apihttp.Handler(svc, opts...)
}

func hit(h http.Handler, method, path, body string, auth bool) (*httptest.ResponseRecorder, map[string]any) {
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	if auth {
		req.Header.Set("Authorization", "Bearer test-token")
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	var m map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &m)
	return rec, m
}

func activeOf(t *testing.T, h http.Handler) []any {
	t.Helper()
	rec, body := hit(h, http.MethodGet, "/debug/faults", "", true)
	if rec.Code != 200 {
		t.Fatalf("GET = %d %s", rec.Code, rec.Body.String())
	}
	return body["active"].([]any)
}

var allFaultNames = []string{"response_lost_after_commit", "upload_expired", "upload_interrupted", "vendor_duplicate_callback", "vendor_failed", "vendor_no_callback"}

func TestDebugFaultsEmptyList(t *testing.T) {
	h := debugHandler(apihttp.WithDebugAPI())
	rec, body := hit(h, http.MethodGet, "/debug/faults", "", true)
	if rec.Code != 200 || !reflect.DeepEqual(body["active"], []any{}) {
		t.Fatalf("GET = %d %s", rec.Code, rec.Body.String())
	}
	got := []string{}
	for _, n := range body["available"].([]any) {
		got = append(got, n.(string))
	}
	if !sort.StringsAreSorted(got) || !reflect.DeepEqual(got, allFaultNames) {
		t.Fatalf("available = %v", got)
	}
}

func TestDebugAddEachFaultWithDefaults(t *testing.T) {
	cases := []struct {
		body string
		want map[string]any
	}{
		{`{"name":"response_lost_after_commit","target":"create"}`, map[string]any{"name": "response_lost_after_commit", "target": "create", "times": 1.0, "params": map[string]any{}}},
		{`{"name":"upload_interrupted"}`, map[string]any{"name": "upload_interrupted", "target": nil, "times": 1.0, "params": map[string]any{"after_bytes": 1024.0}}},
		{`{"name":"upload_expired","times":2}`, map[string]any{"name": "upload_expired", "target": nil, "times": 2.0, "params": map[string]any{}}},
		{`{"name":"vendor_failed"}`, map[string]any{"name": "vendor_failed", "target": nil, "times": 1.0, "params": map[string]any{"reason": nil}}},
		{`{"name":"vendor_failed","params":{"reason":"CONTENT_REJECTED"}}`, map[string]any{"name": "vendor_failed", "target": nil, "times": 1.0, "params": map[string]any{"reason": "CONTENT_REJECTED"}}},
		{`{"name":"vendor_duplicate_callback","params":{}}`, map[string]any{"name": "vendor_duplicate_callback", "target": nil, "times": 1.0, "params": map[string]any{"count": 3.0, "status": "SUCCEEDED"}}},
		{`{"name":"vendor_duplicate_callback","params":{"count":5,"status":"FAILED"}}`, map[string]any{"name": "vendor_duplicate_callback", "target": nil, "times": 1.0, "params": map[string]any{"count": 5.0, "status": "FAILED"}}},
		{`{"name":"vendor_no_callback","params":null}`, map[string]any{"name": "vendor_no_callback", "target": nil, "times": 1.0, "params": map[string]any{}}},
	}
	for _, c := range cases {
		h := debugHandler(apihttp.WithDebugAPI())
		rec, body := hit(h, http.MethodPost, "/debug/faults", c.body, true)
		if rec.Code != 200 || !reflect.DeepEqual(body["active"], []any{c.want}) {
			t.Errorf("POST %s = %d %s", c.body, rec.Code, rec.Body.String())
			continue
		}
		if a := activeOf(t, h); !reflect.DeepEqual(a, []any{c.want}) {
			t.Errorf("GET after %s = %v", c.body, a)
		}
	}
}

func TestDebugAddFaultRejections(t *testing.T) {
	long := `{"name":"upload_expired","x":"` + strings.Repeat("a", 1<<16) + `"}`
	cases := []struct {
		name, body, code string
	}{
		{"unknown name", `{"name":"nope"}`, "UNKNOWN_FAULT"},
		{"missing name", `{}`, "INVALID_FAULT"},
		{"name null", `{"name":null}`, "INVALID_FAULT"},
		{"name wrong type", `{"name":5}`, "INVALID_FAULT"},
		{"times 1.5", `{"name":"upload_expired","times":1.5}`, "INVALID_FAULT"},
		{"times 1.0", `{"name":"upload_expired","times":1.0}`, "INVALID_FAULT"},
		{"times 1e2", `{"name":"upload_expired","times":1e2}`, "INVALID_FAULT"},
		{"times string", `{"name":"upload_expired","times":"1"}`, "INVALID_FAULT"},
		{"times bool", `{"name":"upload_expired","times":true}`, "INVALID_FAULT"},
		{"times overflow", `{"name":"upload_expired","times":99999999999999999999}`, "INVALID_FAULT"},
		{"target wrong type", `{"name":"response_lost_after_commit","target":5}`, "INVALID_FAULT"},
		{"target wrong type on other fault", `{"name":"upload_expired","target":5}`, "INVALID_FAULT"},
		{"target null on response_lost", `{"name":"response_lost_after_commit","target":null}`, "INVALID_FAULT"},
		{"times 0", `{"name":"upload_expired","times":0}`, "INVALID_FAULT"},
		{"times -1", `{"name":"upload_expired","times":-1}`, "INVALID_FAULT"},
		{"missing target", `{"name":"response_lost_after_commit"}`, "INVALID_FAULT"},
		{"invalid target", `{"name":"response_lost_after_commit","target":"other"}`, "INVALID_FAULT"},
		{"target on other fault", `{"name":"upload_expired","target":"create"}`, "INVALID_FAULT"},
		{"unknown param key", `{"name":"upload_interrupted","params":{"nope":1}}`, "INVALID_FAULT"},
		{"params on no-param fault", `{"name":"upload_expired","params":{"a":1}}`, "INVALID_FAULT"},
		{"params not object", `{"name":"upload_interrupted","params":[1]}`, "INVALID_FAULT"},
		{"after_bytes wrong type", `{"name":"upload_interrupted","params":{"after_bytes":"1"}}`, "INVALID_FAULT"},
		{"after_bytes 0", `{"name":"upload_interrupted","params":{"after_bytes":0}}`, "INVALID_FAULT"},
		{"after_bytes fraction", `{"name":"upload_interrupted","params":{"after_bytes":1.5}}`, "INVALID_FAULT"},
		{"reason wrong type", `{"name":"vendor_failed","params":{"reason":5}}`, "INVALID_FAULT"},
		{"times false", `{"name":"response_lost_after_commit","target":"create","times":false}`, "INVALID_FAULT"},
		{"status null", `{"name":"vendor_duplicate_callback","params":{"status":null}}`, "INVALID_FAULT"},
		{"count 0", `{"name":"vendor_duplicate_callback","params":{"count":0}}`, "INVALID_FAULT"},
		{"status invalid", `{"name":"vendor_duplicate_callback","params":{"status":"QUEUED"}}`, "INVALID_FAULT"},
		{"invalid json", `{`, "INVALID_REQUEST"},
		{"empty body", ``, "INVALID_REQUEST"},
		{"trailing garbage", `{"name":"upload_expired"} garbage`, "INVALID_REQUEST"},
		{"trailing bracket", `{"name":"upload_expired"}]`, "INVALID_REQUEST"},
		{"two json values", `{"name":"upload_expired"}{"name":"vendor_failed"}`, "INVALID_REQUEST"},
		{"whitespace over 64KB", `{"name":"upload_expired"}` + strings.Repeat(" ", 1<<16), "INVALID_REQUEST"},
		{"invalid utf8", "{\"name\":\"upload_expired\",\"x\":\"\xff\"}", "INVALID_REQUEST"},
		{"top-level array", `[{"name":"upload_expired"}]`, "INVALID_REQUEST"},
		{"top-level null", `null`, "INVALID_REQUEST"},
		{"over 64KB", long, "INVALID_REQUEST"},
	}
	for _, c := range cases {
		h := debugHandler(apihttp.WithDebugAPI())
		rec, body := hit(h, http.MethodPost, "/debug/faults", c.body, true)
		if rec.Code != 400 || body["code"] != c.code || body["retryable"] != false {
			t.Errorf("%s: %d %s, want 400 %s", c.name, rec.Code, rec.Body.String(), c.code)
		}
		if c.code == "UNKNOWN_FAULT" && body["message"] != "未知的異常" {
			t.Errorf("%s: message = %v", c.name, body["message"])
		}
		if a := activeOf(t, h); len(a) != 0 {
			t.Errorf("%s: registered %v", c.name, a)
		}
	}
}

func TestDebugAddFaultNullFields(t *testing.T) {
	h := debugHandler(apihttp.WithDebugAPI())
	rec, body := hit(h, http.MethodPost, "/debug/faults", `{"name":"upload_expired","times":null,"target":null}`, true)
	want := map[string]any{"name": "upload_expired", "target": nil, "times": 1.0, "params": map[string]any{}}
	if rec.Code != 200 || !reflect.DeepEqual(body["active"], []any{want}) {
		t.Fatalf("POST = %d %s", rec.Code, rec.Body.String())
	}
	if a := activeOf(t, h); len(a) != 1 {
		t.Fatalf("active = %v", a)
	}
}

func TestDebugSameNameOverwritesDifferentNamesCoexist(t *testing.T) {
	h := debugHandler(apihttp.WithDebugAPI())
	for _, b := range []string{
		`{"name":"response_lost_after_commit","target":"create","times":3}`,
		`{"name":"upload_expired"}`,
		`{"name":"response_lost_after_commit","target":"submit"}`,
	} {
		if rec, _ := hit(h, http.MethodPost, "/debug/faults", b, true); rec.Code != 200 {
			t.Fatalf("POST %s = %d", b, rec.Code)
		}
	}
	a := activeOf(t, h)
	if len(a) != 2 || a[0].(map[string]any)["name"] != "upload_expired" ||
		a[1].(map[string]any)["target"] != "submit" || a[1].(map[string]any)["times"] != 1.0 {
		t.Fatalf("active = %v", a)
	}
}

func TestDebugDeleteFaults(t *testing.T) {
	h := debugHandler(apihttp.WithDebugAPI())
	hit(h, http.MethodPost, "/debug/faults", `{"name":"upload_expired"}`, true)
	hit(h, http.MethodPost, "/debug/faults", `{"name":"vendor_no_callback"}`, true)
	for i := 0; i < 2; i++ {
		rec, _ := hit(h, http.MethodDelete, "/debug/faults", "", true)
		if rec.Code != 204 || rec.Body.Len() != 0 {
			t.Fatalf("DELETE #%d = %d %q", i, rec.Code, rec.Body.String())
		}
	}
	if a := activeOf(t, h); len(a) != 0 {
		t.Fatalf("active = %v", a)
	}
}

func TestDebugRoutesAbsentWithoutOption(t *testing.T) {
	h := debugHandler()
	for _, r := range [][2]string{{"GET", "/debug/faults"}, {"POST", "/debug/faults"}, {"DELETE", "/debug/faults"}, {"POST", "/debug/reset"}, {"POST", "/debug/faults/consume"}} {
		for _, auth := range []bool{true, false} {
			rec, body := hit(h, r[0], r[1], `{"name":"upload_expired"}`, auth)
			assertError(t, rec, body, 404, "NOT_FOUND")
		}
	}
}

func TestDebugRoutesNeedTokenAndMethods(t *testing.T) {
	h := debugHandler(apihttp.WithDebugAPI())
	for _, r := range [][2]string{{"GET", "/debug/faults"}, {"POST", "/debug/faults"}, {"DELETE", "/debug/faults"}, {"POST", "/debug/reset"}, {"POST", "/debug/faults/consume"}} {
		rec, body := hit(h, r[0], r[1], `{"name":"upload_expired"}`, false)
		assertError(t, rec, body, 401, "UNAUTHORIZED")
	}
	// 沒帶 token 的 POST 不可留下登記。
	if a := activeOf(t, h); len(a) != 0 {
		t.Fatalf("active = %v", a)
	}
	for _, c := range []struct{ method, path, allow string }{
		{"PUT", "/debug/faults", "DELETE, GET, HEAD, POST"},
		{"PATCH", "/debug/faults", "DELETE, GET, HEAD, POST"},
		{"GET", "/debug/faults/consume", "POST"},
		{"PUT", "/debug/faults/consume", "POST"},
		{"GET", "/debug/reset", "POST"},
		{"DELETE", "/debug/reset", "POST"},
	} {
		rec, body := hit(h, c.method, c.path, "", true)
		assertError(t, rec, body, 405, "METHOD_NOT_ALLOWED")
		if rec.Header().Get("Allow") != c.allow {
			t.Errorf("%s %s Allow = %q, want %q", c.method, c.path, rec.Header().Get("Allow"), c.allow)
		}
	}
}

// backend/faults/*.json 逐一用真實 handler 登記，且恰好涵蓋 6 種異常。
func TestFaultScenarioFiles(t *testing.T) {
	files, err := filepath.Glob("../../../faults/*.json")
	if err != nil || len(files) != 6 {
		t.Fatalf("files = %v err = %v, want 6", files, err)
	}
	seen := []string{}
	for _, f := range files {
		b, err := os.ReadFile(f)
		if err != nil {
			t.Fatal(err)
		}
		h := debugHandler(apihttp.WithDebugAPI())
		rec, body := hit(h, http.MethodPost, "/debug/faults", string(b), true)
		if rec.Code != 200 {
			t.Errorf("%s: %d %s", f, rec.Code, rec.Body.String())
			continue
		}
		active := body["active"].([]any)
		if len(active) != 1 {
			t.Errorf("%s: active = %v, want exactly 1 fault", f, active)
			continue
		}
		seen = append(seen, active[0].(map[string]any)["name"].(string))
		if entry := active[0].(map[string]any); entry["name"] == "vendor_failed" {
			if reason, present := entry["params"].(map[string]any)["reason"]; !present || reason != nil {
				t.Errorf("%s: reason = %v, want null", f, reason)
			}
		}
	}
	sort.Strings(seen)
	if !reflect.DeepEqual(seen, allFaultNames) {
		t.Fatalf("scenario names = %v, want %v", seen, allFaultNames)
	}
}

func TestDebugOnlyOneVendorFault(t *testing.T) {
	h := debugHandler(apihttp.WithDebugAPI())
	post := func(b string) (*httptest.ResponseRecorder, map[string]any) {
		return hit(h, http.MethodPost, "/debug/faults", b, true)
	}
	if rec, _ := post(`{"name":"vendor_failed"}`); rec.Code != 200 {
		t.Fatalf("first = %d", rec.Code)
	}
	before := activeOf(t, h)
	rec, body := post(`{"name":"vendor_no_callback"}`)
	assertError(t, rec, body, 400, "INVALID_FAULT")
	if !reflect.DeepEqual(activeOf(t, h), before) {
		t.Fatal("list changed after rejected vendor fault")
	}
	if rec, _ := post(`{"name":"vendor_failed","times":2}`); rec.Code != 200 {
		t.Fatalf("same-name overwrite = %d", rec.Code)
	}
	if rec, _ := post(`{"name":"upload_expired"}`); rec.Code != 200 {
		t.Fatalf("coexist = %d", rec.Code)
	}
	if a := activeOf(t, h); len(a) != 2 || a[0].(map[string]any)["times"] != 2.0 || a[1].(map[string]any)["name"] != "upload_expired" {
		t.Fatalf("active = %v", a)
	}
	hit(h, http.MethodDelete, "/debug/faults", "", true)
	if rec, _ := post(`{"name":"vendor_duplicate_callback"}`); rec.Code != 200 {
		t.Fatalf("after DELETE = %d", rec.Code)
	}
}

func consume(t *testing.T, h http.Handler) map[string]any {
	t.Helper()
	rec, body := hit(h, http.MethodPost, "/debug/faults/consume", "", true)
	if rec.Code != 200 {
		t.Fatalf("consume = %d %s", rec.Code, rec.Body.String())
	}
	return body
}

func register(t *testing.T, h http.Handler, body string) {
	t.Helper()
	if rec, _ := hit(h, http.MethodPost, "/debug/faults", body, true); rec.Code != 200 {
		t.Fatalf("POST %s = %d %s", body, rec.Code, rec.Body.String())
	}
}

func TestConsumeWithoutFaultIsNull(t *testing.T) {
	h := debugHandler(apihttp.WithDebugAPI())
	if got := consume(t, h); !reflect.DeepEqual(got, map[string]any{"fault": nil}) {
		t.Fatalf("consume = %v", got)
	}
}

func TestConsumeReturnsNameAndParams(t *testing.T) {
	for _, c := range []struct {
		body   string
		name   string
		params map[string]any
	}{
		{`{"name":"vendor_no_callback"}`, "vendor_no_callback", map[string]any{}},
		{`{"name":"vendor_failed"}`, "vendor_failed", map[string]any{"reason": nil}},
		{`{"name":"vendor_failed","params":{"reason":"CONTENT_REJECTED"}}`, "vendor_failed", map[string]any{"reason": "CONTENT_REJECTED"}},
		{`{"name":"vendor_duplicate_callback"}`, "vendor_duplicate_callback", map[string]any{"count": 3.0, "status": "SUCCEEDED"}},
		{`{"name":"vendor_duplicate_callback","params":{"count":2,"status":"FAILED"}}`, "vendor_duplicate_callback", map[string]any{"count": 2.0, "status": "FAILED"}},
	} {
		h := debugHandler(apihttp.WithDebugAPI())
		register(t, h, c.body)
		want := map[string]any{"fault": map[string]any{"name": c.name, "params": c.params}}
		if got := consume(t, h); !reflect.DeepEqual(got, want) {
			t.Errorf("%s: consume = %v, want %v", c.body, got, want)
		}
	}
}

func TestConsumeDecrementsTimes(t *testing.T) {
	h := debugHandler(apihttp.WithDebugAPI())
	register(t, h, `{"name":"vendor_failed","times":2}`)
	if got := consume(t, h)["fault"]; got == nil {
		t.Fatal("first consume is null")
	}
	if a := activeOf(t, h); len(a) != 1 || a[0].(map[string]any)["times"] != 1.0 {
		t.Fatalf("active after first = %v", a)
	}
	if got := consume(t, h)["fault"]; got == nil {
		t.Fatal("second consume is null")
	}
	if got := consume(t, h)["fault"]; got != nil {
		t.Fatalf("third consume = %v, want null", got)
	}
	if a := activeOf(t, h); len(a) != 0 {
		t.Fatalf("active = %v", a)
	}
}

func TestConsumeLeavesNonVendorFaults(t *testing.T) {
	h := debugHandler(apihttp.WithDebugAPI())
	register(t, h, `{"name":"upload_expired"}`)
	register(t, h, `{"name":"response_lost_after_commit","target":"create"}`)
	if got := consume(t, h)["fault"]; got != nil {
		t.Fatalf("consume = %v, want null", got)
	}
	if a := activeOf(t, h); len(a) != 2 || a[0].(map[string]any)["times"] != 1.0 || a[1].(map[string]any)["times"] != 1.0 {
		t.Fatalf("active = %v", a)
	}
	register(t, h, `{"name":"vendor_no_callback"}`)
	if got := consume(t, h)["fault"].(map[string]any)["name"]; got != "vendor_no_callback" {
		t.Fatalf("consume = %v", got)
	}
	if a := activeOf(t, h); len(a) != 2 {
		t.Fatalf("active = %v", a)
	}
}

func TestConsumeIgnoresBody(t *testing.T) {
	h := debugHandler(apihttp.WithDebugAPI())
	register(t, h, `{"name":"vendor_no_callback"}`)
	rec, body := hit(h, http.MethodPost, "/debug/faults/consume", `not json`, true)
	if rec.Code != 200 || body["fault"] == nil {
		t.Fatalf("consume = %d %s", rec.Code, rec.Body.String())
	}
}
