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

// 同一路徑可同時註冊 GET 與 POST；其他方法回 405，Allow 列出全部（GET 帶 HEAD）。
func TestHandleMethodsCoexist(t *testing.T) {
	mux := http.NewServeMux()
	ok := func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"m": r.Method})
	}
	handleMethods(mux, "/p", map[string]http.HandlerFunc{http.MethodGet: ok, http.MethodPost: ok})
	handleMethods(mux, "/q", map[string]http.HandlerFunc{http.MethodPost: ok})
	do := func(method, path string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(method, path, nil)
		req.Header.Set("Authorization", bearerToken)
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		return rec
	}
	if rec := do("GET", "/p"); rec.Code != 200 {
		t.Errorf("GET /p = %d", rec.Code)
	}
	if rec := do("POST", "/p"); rec.Code != 200 {
		t.Errorf("POST /p = %d", rec.Code)
	}
	if rec := do("DELETE", "/p"); rec.Code != 405 || rec.Header().Get("Allow") != "GET, HEAD, POST" {
		t.Errorf("DELETE /p = %d Allow=%q", rec.Code, rec.Header().Get("Allow"))
	}
	if rec := do("GET", "/q"); rec.Code != 405 || rec.Header().Get("Allow") != "POST" {
		t.Errorf("GET /q = %d Allow=%q", rec.Code, rec.Header().Get("Allow"))
	}
}

// handlePublic 與 handleMethods 相同，只是不驗 token：免 Authorization 可通過，其他方法回 405。
func TestHandlePublicNoAuth(t *testing.T) {
	mux := http.NewServeMux()
	ok := func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"t": r.PathValue("t")})
	}
	handlePublic(mux, "/pub/{t}", map[string]http.HandlerFunc{http.MethodGet: ok})
	handleGET(mux, "/priv", ok)
	do := func(method, path string) *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, httptest.NewRequest(method, path, nil))
		return rec
	}
	if rec := do("GET", "/pub/abc"); rec.Code != 200 || rec.Body.String() != "{\"t\":\"abc\"}\n" {
		t.Errorf("GET public without token: %d %q", rec.Code, rec.Body.String())
	}
	if rec := do("HEAD", "/pub/abc"); rec.Code != 200 {
		t.Errorf("HEAD public without token = %d", rec.Code)
	}
	if rec := do("POST", "/pub/abc"); rec.Code != 405 || rec.Header().Get("Allow") != "GET, HEAD" {
		t.Errorf("POST public: %d Allow=%q", rec.Code, rec.Header().Get("Allow"))
	}
	if rec := do("GET", "/priv"); rec.Code != 401 {
		t.Errorf("handleGET without token = %d, want 401", rec.Code)
	}
}
