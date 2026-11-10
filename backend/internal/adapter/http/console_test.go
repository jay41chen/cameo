package http_test

import (
	"bytes"
	"encoding/json"
	"net/http"
	"strings"
	"testing"

	"cameo/backend/faults"
	apihttp "cameo/backend/internal/adapter/http"
)

func TestDebugConsoleServed(t *testing.T) {
	h := debugHandler(apihttp.WithDebugAPI())
	for _, method := range []string{http.MethodGet, http.MethodHead} {
		rec, _ := hit(h, method, "/debug/console", "", false) // 不帶 token 也要能開
		if rec.Code != 200 {
			t.Fatalf("%s = %d", method, rec.Code)
		}
		if ct := rec.Header().Get("Content-Type"); ct != "text/html; charset=utf-8" {
			t.Errorf("%s Content-Type = %q", method, ct)
		}
		if cc := rec.Header().Get("Cache-Control"); cc != "no-store" {
			t.Errorf("%s Cache-Control = %q", method, cc)
		}
	}
	rec, _ := hit(h, http.MethodGet, "/debug/console", "", false)
	if !strings.Contains(rec.Body.String(), "<title>Cameo 除錯控制台</title>") {
		t.Errorf("body missing title: %.80q", rec.Body.String())
	}
}

func TestDebugConsoleAbsentWithoutOption(t *testing.T) {
	h := debugHandler()
	for _, auth := range []bool{true, false} {
		rec, body := hit(h, http.MethodGet, "/debug/console", "", auth)
		assertError(t, rec, body, 404, "NOT_FOUND")
	}
}

func TestDebugConsoleMethodNotAllowed(t *testing.T) {
	h := debugHandler(apihttp.WithDebugAPI())
	rec, body := hit(h, http.MethodPost, "/debug/console", "", false)
	assertError(t, rec, body, 405, "METHOD_NOT_ALLOWED")
	if allow := rec.Header().Get("Allow"); allow != "GET, HEAD" {
		t.Errorf("Allow = %q, want %q", allow, "GET, HEAD")
	}
}

// 頁面 id="scenarios" 的 JSON 區塊必須與 faults.Load() 一致（順序、name、desc、example 語意）。
func TestDebugConsoleScenarios(t *testing.T) {
	want, err := faults.Load()
	if err != nil || len(want) == 0 {
		t.Fatalf("faults.Load() = %d scenarios, err %v", len(want), err)
	}
	rec, _ := hit(debugHandler(apihttp.WithDebugAPI()), http.MethodGet, "/debug/console", "", false)
	page := rec.Body.String()
	if strings.Contains(page, "__SCENARIOS__") {
		t.Fatal("page still contains placeholder __SCENARIOS__")
	}
	const open = `<script type="application/json" id="scenarios">`
	i := strings.Index(page, open)
	if i < 0 {
		t.Fatal("scenarios block not found")
	}
	rest := page[i+len(open):]
	j := strings.Index(rest, "</script>")
	if j < 0 {
		t.Fatal("scenarios block not closed")
	}
	var got []faults.Scenario
	if err := json.Unmarshal([]byte(rest[:j]), &got); err != nil {
		t.Fatal(err)
	}
	if len(got) != len(want) {
		t.Fatalf("page has %d scenarios, faults.Load() has %d", len(got), len(want))
	}
	compact := func(raw json.RawMessage) string {
		var buf bytes.Buffer
		if err := json.Compact(&buf, raw); err != nil {
			t.Fatalf("compact %s: %v", raw, err)
		}
		return buf.String()
	}
	for k := range want {
		if got[k].Name != want[k].Name || got[k].Desc != want[k].Desc {
			t.Errorf("#%d: page (%q, %q) != faults (%q, %q)", k, got[k].Name, got[k].Desc, want[k].Name, want[k].Desc)
		}
		if g, w := compact(got[k].Example), compact(want[k].Example); g != w {
			t.Errorf("%s: page example %s != file %s", want[k].Name, g, w)
		}
	}
}
