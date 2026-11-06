package http

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// 測試專用路由：驗證 handleGET 支援 {id} 路徑參數，且驗證順序為路由先、token 後。
func TestHandleGETPathParam(t *testing.T) {
	mux := http.NewServeMux()
	handleGET(mux, "/v1/test/{id}", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"id": r.PathValue("id")})
	})
	do := func(method, path, auth string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(method, path, nil)
		if auth != "" {
			req.Header.Set("Authorization", auth)
		}
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		return rec
	}

	rec := do(http.MethodGet, "/v1/test/abc", bearerToken)
	if rec.Code != 200 || rec.Body.String() != "{\"id\":\"abc\"}\n" {
		t.Errorf("GET with token: %d %q", rec.Code, rec.Body.String())
	}
	if rec := do(http.MethodGet, "/v1/test/abc", ""); rec.Code != 401 {
		t.Errorf("GET without token = %d, want 401", rec.Code)
	}
	rec = do(http.MethodPost, "/v1/test/abc", "")
	if rec.Code != 405 || rec.Header().Get("Allow") != "GET, HEAD" {
		t.Errorf("POST: %d Allow=%q, want 405 with Allow", rec.Code, rec.Header().Get("Allow"))
	}
}
