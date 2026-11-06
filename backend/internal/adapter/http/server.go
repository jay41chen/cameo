// Package http 是 REST 適配層：路由、驗證 token、JSON 轉換。
package http

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"cameo/backend/internal/domain"
	"cameo/backend/internal/usecase"
)

const bearerToken = "Bearer test-token"

var taipei = time.FixedZone("+08:00", 8*60*60)

type errorBody struct {
	Code      string `json:"code"`
	Message   string `json:"message"`
	Retryable bool   `json:"retryable"`
}

// Handler 組出所有路由。檢查順序：先比對路由（404、405），再驗 token（401）。
func Handler(svc *usecase.Service) http.Handler {
	s := &server{svc: svc}
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, _ *http.Request) {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "找不到這個路徑", false)
	})
	handleGET(mux, "/v1/clips", s.clips)
	handleGET(mux, "/v1/points", s.points)
	handleGET(mux, "/v1/points/ledger", s.ledger)
	return mux
}

// handleGET 註冊一條只收 GET（含 HEAD）、需要 token 的路由；path 可含 {id} 等路徑參數。
// 其他方法回 405 並帶 Allow。
func handleGET(mux *http.ServeMux, path string, h http.HandlerFunc) {
	mux.Handle("GET "+path, requireAuth(h))
	mux.HandleFunc(path, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Allow", "GET, HEAD")
		writeError(w, http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", "不支援這個方法", false)
	})
}

// requireAuth 只包需要登入的路由，不是全域驗證。
func requireAuth(next http.HandlerFunc) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != bearerToken {
			writeError(w, http.StatusUnauthorized, "UNAUTHORIZED", "請先登入", false)
			return
		}
		next(w, r)
	})
}

type server struct {
	svc *usecase.Service
}

func (s *server) clips(w http.ResponseWriter, r *http.Request) {
	clips, err := s.svc.Clips(r.Context())
	if err != nil {
		writeInternal(w, r, err)
		return
	}
	type item struct {
		ID    string `json:"id"`
		Title string `json:"title"`
		Cost  int    `json:"cost"`
	}
	items := make([]item, 0, len(clips))
	for _, c := range clips {
		items = append(items, item{c.ID, c.Title, c.Cost})
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items})
}

func (s *server) points(w http.ResponseWriter, r *http.Request) {
	p, err := s.svc.Points(r.Context())
	if err != nil {
		writeInternal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, struct {
		Available int `json:"available"`
		Held      int `json:"held"`
	}{p.Available, p.Held})
}

func (s *server) ledger(w http.ResponseWriter, r *http.Request) {
	page, err := s.svc.Ledger(r.Context(), r.URL.Query().Get("cursor"))
	if errors.Is(err, domain.ErrInvalidCursor) {
		writeError(w, http.StatusBadRequest, "INVALID_CURSOR", "cursor 無效", false)
		return
	}
	if err != nil {
		writeInternal(w, r, err)
		return
	}
	type item struct {
		ID        string  `json:"id"`
		Type      string  `json:"type"`
		Amount    int     `json:"amount"`
		CameoID   *string `json:"cameo_id"`
		ClipTitle string  `json:"clip_title"`
		CreatedAt string  `json:"created_at"`
	}
	items := make([]item, 0, len(page.Items))
	for _, e := range page.Items {
		items = append(items, item{e.ID, string(e.Type), e.Amount, e.CameoID, e.ClipTitle, e.CreatedAt.In(taipei).Format(time.RFC3339)})
	}
	var next *string
	if page.NextCursor != "" {
		next = &page.NextCursor
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items, "next_cursor": next})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(v); err != nil {
		slog.Error("write response", "err", err)
	}
}

func writeError(w http.ResponseWriter, status int, code, message string, retryable bool) {
	writeJSON(w, status, errorBody{Code: code, Message: message, Retryable: retryable})
}

func writeInternal(w http.ResponseWriter, r *http.Request, err error) {
	slog.Error("request failed", "path", r.URL.Path, "err", err)
	writeError(w, http.StatusInternalServerError, "INTERNAL", "伺服器暫時發生問題，請稍後再試", true)
}
