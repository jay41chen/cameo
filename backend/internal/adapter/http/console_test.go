package http_test

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"

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

// 頁面 id="scenarios" 的 JSON 區塊必須與 backend/faults/*.json 一一對應、內容逐字一致（防止兩邊漂移）。
// 頁面以 JSON.stringify(example) 填入 textarea；example 為無空白緊湊 JSON，key 順序保留，
// 故用 RawMessage 比對原始位元組即等同 textarea 顯示的字串。
func TestDebugConsoleEmbedsFaultFiles(t *testing.T) {
	files, err := filepath.Glob("../../../faults/*.json")
	if err != nil || len(files) != 6 {
		t.Fatalf("files = %v err = %v, want 6", files, err)
	}
	rec, _ := hit(debugHandler(apihttp.WithDebugAPI()), http.MethodGet, "/debug/console", "", false)
	page := rec.Body.String()
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
	var list []struct {
		Name    string          `json:"name"`
		Example json.RawMessage `json:"example"`
	}
	if err := json.Unmarshal([]byte(rest[:j]), &list); err != nil {
		t.Fatal(err)
	}
	if len(list) != 6 {
		t.Fatalf("scenarios = %d, want 6", len(list))
	}
	byName := map[string]string{}
	for _, s := range list {
		if _, dup := byName[s.Name]; dup {
			t.Errorf("duplicate scenario name %q", s.Name)
		}
		byName[s.Name] = string(s.Example)
	}
	fileNames := map[string]bool{}
	for _, f := range files {
		name := strings.TrimSuffix(filepath.Base(f), ".json")
		fileNames[name] = true
		got, ok := byName[name]
		if !ok {
			t.Errorf("%s: no scenario named %q in console page", f, name)
			continue
		}
		b, err := os.ReadFile(f)
		if err != nil {
			t.Fatal(err)
		}
		if want := strings.TrimSpace(string(b)); got != want {
			t.Errorf("%s: page example %q != file %q", f, got, want)
		}
	}
	for name := range byName {
		if !fileNames[name] {
			t.Errorf("scenario %q has no faults/%s.json", name, name)
		}
	}
}
