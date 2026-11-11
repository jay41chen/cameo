package http_test

import (
	"fmt"
	"net/http"
	"reflect"
	"sync"
	"testing"

	apihttp "cameo/backend/internal/adapter/http"
)

func cfgView(t *testing.T, h http.Handler) map[string]any {
	t.Helper()
	rec, body := hit(h, http.MethodGet, "/debug/config", "", true)
	if rec.Code != 200 {
		t.Fatalf("GET config = %d %s", rec.Code, rec.Body.String())
	}
	return body
}

func TestDebugConfigInitialPoints(t *testing.T) {
	h := debugHandler(apihttp.WithDebugAPI())
	if got := cfgView(t, h); got["initial_points"] != nil || !hasKey(got, "initial_points") {
		t.Fatalf("GET initial = %v", got)
	}
	rec, body := hit(h, http.MethodPut, "/debug/config", `{"initial_points":5}`, true)
	if rec.Code != 200 || body["initial_points"] != 5.0 {
		t.Fatalf("PUT 5 = %d %s", rec.Code, rec.Body.String())
	}
	rec, body = hit(h, http.MethodPut, "/debug/config", `{"initial_points":null}`, true)
	if rec.Code != 200 || body["initial_points"] != nil || !hasKey(body, "initial_points") {
		t.Fatalf("PUT null = %d %s", rec.Code, rec.Body.String())
	}
}

// PUT 只帶其他欄位時，initial_points 不可被清掉。
func TestDebugConfigInitialPointsKeptByOtherPuts(t *testing.T) {
	h := debugHandler(apihttp.WithDebugAPI())
	if rec, _ := hit(h, http.MethodPut, "/debug/config", `{"initial_points":5}`, true); rec.Code != 200 {
		t.Fatalf("PUT initial_points = %d %s", rec.Code, rec.Body.String())
	}
	for _, b := range []string{`{"daily_limit":7}`, `{"vendor_delay_sec":1.5}`, `{"generation_ttl_sec":60}`} {
		rec, body := hit(h, http.MethodPut, "/debug/config", b, true)
		if rec.Code != 200 || body["initial_points"] != 5.0 {
			t.Fatalf("PUT %s = %d %s", b, rec.Code, rec.Body.String())
		}
		if got := cfgView(t, h); got["initial_points"] != 5.0 {
			t.Fatalf("GET after PUT %s: initial_points = %v", b, got["initial_points"])
		}
	}
	got := cfgView(t, h)
	if got["initial_points"] != 5.0 || got["daily_limit"] != 7.0 {
		t.Fatalf("final GET = %v", got)
	}
}

func hasKey(m map[string]any, k string) bool { _, ok := m[k]; return ok }

func TestDebugConfigInitialPointsInvalid(t *testing.T) {
	h := debugHandler(apihttp.WithDebugAPI())
	hit(h, http.MethodPut, "/debug/config", `{"initial_points":5}`, true)
	before := cfgView(t, h)
	for _, b := range []string{
		`{"initial_points":-1}`, `{"initial_points":100001}`, `{"initial_points":5.0}`, `{"initial_points":"5"}`, `{"initial_points":true}`,
		`{"daily_limit":7,"initial_points":-1}`,
	} {
		rec, body := hit(h, http.MethodPut, "/debug/config", b, true)
		if rec.Code != 400 || body["code"] != "INVALID_CONFIG" {
			t.Fatalf("PUT %s = %d %s", b, rec.Code, rec.Body.String())
		}
		if got := cfgView(t, h); !reflect.DeepEqual(got, before) {
			t.Fatalf("PUT %s changed config: %v -> %v", b, before, got)
		}
	}
}

// 並行讀寫，搭配 -race 檢查鎖。
func TestDebugConfigConcurrentPutGet(t *testing.T) {
	h := debugHandler(apihttp.WithDebugAPI())
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(2)
		go func(i int) {
			defer wg.Done()
			for j := 0; j < 20; j++ {
				body := fmt.Sprintf(`{"initial_points":%d,"daily_limit":%d}`, i+j, 1+i)
				if j%5 == 0 {
					body = `{"initial_points":null}`
				}
				if rec, _ := hit(h, http.MethodPut, "/debug/config", body, true); rec.Code != 200 {
					t.Errorf("PUT %s = %d", body, rec.Code)
				}
			}
		}(i)
		go func() {
			defer wg.Done()
			for j := 0; j < 20; j++ {
				if rec, _ := hit(h, http.MethodGet, "/debug/config", "", true); rec.Code != 200 {
					t.Errorf("GET = %d", rec.Code)
				}
			}
		}()
	}
	wg.Wait()
}
