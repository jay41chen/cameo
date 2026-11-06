package http_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"reflect"
	"sort"
	"testing"
	"time"

	apihttp "cameo/backend/internal/adapter/http"
	"cameo/backend/internal/domain"
	"cameo/backend/internal/usecase"
)

type fakeQueries struct{}

func (fakeQueries) ListClips(context.Context) ([]domain.Clip, error) {
	return []domain.Clip{{ID: "c1", Title: "t", Cost: 10}}, nil
}
func (fakeQueries) Balance(context.Context) (int, error) { return 100, nil }
func (fakeQueries) ListLedger(_ context.Context, after *domain.LedgerCursor, limit int) ([]domain.LedgerEntry, error) {
	if after != nil {
		return nil, nil
	}
	cameo := "cam1"
	at := time.Date(2026, 11, 7, 2, 1, 0, 0, time.UTC)
	return []domain.LedgerEntry{
		{ID: "l1", Type: domain.LedgerCharge, Amount: 10, CameoID: &cameo, ClipTitle: "t", CreatedAt: at},
		{ID: "l2", Type: domain.LedgerRefund, Amount: 10, ClipTitle: "t", CreatedAt: at},
	}, nil
}

func do(t *testing.T, method, path, auth string) (*httptest.ResponseRecorder, map[string]any) {
	t.Helper()
	h := apihttp.Handler(usecase.NewService(fakeQueries{}, 1))
	req := httptest.NewRequest(method, path, nil)
	if auth != "" {
		req.Header.Set("Authorization", auth)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	var body map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("%s %s: body is not JSON: %q", method, path, rec.Body.String())
	}
	return rec, body
}

func keys(m map[string]any) []string {
	k := make([]string, 0, len(m))
	for key := range m {
		k = append(k, key)
	}
	sort.Strings(k)
	return k
}

func assertError(t *testing.T, rec *httptest.ResponseRecorder, body map[string]any, status int, code string) {
	t.Helper()
	if rec.Code != status {
		t.Fatalf("status = %d, want %d", rec.Code, status)
	}
	if !reflect.DeepEqual(keys(body), []string{"code", "message", "retryable"}) || body["code"] != code {
		t.Fatalf("error body = %v, want code %s", body, code)
	}
}

func TestUnauthorized(t *testing.T) {
	for _, path := range []string{"/v1/clips", "/v1/points", "/v1/points/ledger"} {
		for _, auth := range []string{"", "Bearer wrong", "test-token"} {
			rec, body := do(t, http.MethodGet, path, auth)
			assertError(t, rec, body, 401, "UNAUTHORIZED")
		}
	}
}

func TestNotFoundAndMethodNotAllowed(t *testing.T) {
	rec, body := do(t, http.MethodGet, "/v1/nope", "Bearer test-token")
	assertError(t, rec, body, 404, "NOT_FOUND")
	rec, body = do(t, http.MethodPost, "/v1/clips", "Bearer test-token")
	assertError(t, rec, body, 405, "METHOD_NOT_ALLOWED")
	if rec.Header().Get("Allow") != "GET, HEAD" {
		t.Errorf("Allow = %q", rec.Header().Get("Allow"))
	}
}

func TestUnknownPathWithoutTokenIs404(t *testing.T) {
	for _, m := range []string{http.MethodGet, http.MethodPost} {
		rec, body := do(t, m, "/x", "")
		assertError(t, rec, body, 404, "NOT_FOUND")
	}
}

func TestMethodNotAllowedWithoutTokenIs405(t *testing.T) {
	rec, body := do(t, http.MethodPost, "/v1/clips", "")
	assertError(t, rec, body, 405, "METHOD_NOT_ALLOWED")
	if rec.Header().Get("Allow") != "GET, HEAD" {
		t.Errorf("Allow = %q", rec.Header().Get("Allow"))
	}
}

func TestHeadClips(t *testing.T) {
	h := apihttp.Handler(usecase.NewService(fakeQueries{}, 1))
	req := httptest.NewRequest(http.MethodHead, "/v1/clips", nil)
	req.Header.Set("Authorization", "Bearer test-token")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != 200 {
		t.Fatalf("HEAD /v1/clips = %d, want 200", rec.Code)
	}
}

func TestInvalidCursor(t *testing.T) {
	// 含 NUL 的 id（cursor 內容 {"t":"2026-11-07T02:00:00Z","i":"a\u0000b"}）。
	rec, body := do(t, http.MethodGet, "/v1/points/ledger?cursor=eyJ0IjoiMjAyNi0xMS0wN1QwMjowMDowMFoiLCJpIjoiYVx1MDAwMGIifQ", "Bearer test-token")
	assertError(t, rec, body, 400, "INVALID_CURSOR")

	rec, body = do(t, http.MethodGet, "/v1/points/ledger?cursor=garbage", "Bearer test-token")
	assertError(t, rec, body, 400, "INVALID_CURSOR")
}

func TestResponseShapes(t *testing.T) {
	const auth = "Bearer test-token"
	rec, body := do(t, http.MethodGet, "/v1/clips", auth)
	if rec.Code != 200 || !reflect.DeepEqual(keys(body), []string{"items"}) {
		t.Fatalf("clips: %d %v", rec.Code, body)
	}
	clip := body["items"].([]any)[0].(map[string]any)
	if !reflect.DeepEqual(keys(clip), []string{"cost", "id", "title"}) {
		t.Fatalf("clip keys = %v", keys(clip))
	}

	rec, body = do(t, http.MethodGet, "/v1/points", auth)
	if rec.Code != 200 || !reflect.DeepEqual(keys(body), []string{"available", "held"}) || body["available"] != 100.0 || body["held"] != 0.0 {
		t.Fatalf("points: %d %v", rec.Code, body)
	}

	rec, body = do(t, http.MethodGet, "/v1/points/ledger", auth)
	if rec.Code != 200 || !reflect.DeepEqual(keys(body), []string{"items", "next_cursor"}) {
		t.Fatalf("ledger: %d %v", rec.Code, body)
	}
	items := body["items"].([]any)
	if len(items) != 1 {
		t.Fatalf("page size 1 expected, got %d", len(items))
	}
	e := items[0].(map[string]any)
	if !reflect.DeepEqual(keys(e), []string{"amount", "cameo_id", "clip_title", "created_at", "id", "type"}) {
		t.Fatalf("entry keys = %v", keys(e))
	}
	if e["created_at"] != "2026-11-07T10:01:00+08:00" {
		t.Errorf("created_at = %v", e["created_at"])
	}
	if s, ok := body["next_cursor"].(string); !ok || s == "" {
		t.Errorf("next_cursor = %v, want non-empty string", body["next_cursor"])
	}
}

func TestLedgerNullCursorAndCameoID(t *testing.T) {
	// 不帶 cursor 取第一頁（頁大小 1 時有下一頁）；帶 cursor 取到空頁時 next_cursor 為 null。
	_, body := do(t, http.MethodGet, "/v1/points/ledger", "Bearer test-token")
	cursor := body["next_cursor"].(string)
	rec, body := do(t, http.MethodGet, "/v1/points/ledger?cursor="+cursor, "Bearer test-token")
	if rec.Code != 200 {
		t.Fatalf("status %d", rec.Code)
	}
	if v, ok := body["next_cursor"]; !ok || v != nil {
		t.Errorf("next_cursor = %v, want null", v)
	}
	if items := body["items"].([]any); len(items) != 0 {
		t.Errorf("items = %v, want empty array", items)
	}
}
