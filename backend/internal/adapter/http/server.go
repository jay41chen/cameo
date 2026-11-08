// Package http 是 REST 適配層：路由、驗證 token、JSON 轉換。
package http

import (
	"bytes"
	"encoding/json"
	"errors"
	"html/template"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"

	"cameo/backend/internal/domain"
	"cameo/backend/internal/usecase"
)

const bearerToken = "Bearer test-token"

var taipei = domain.Taipei

type errorBody struct {
	Code      string `json:"code"`
	Message   string `json:"message"`
	Retryable bool   `json:"retryable"`
}

// Option 調整 Handler 的組裝。
type Option func(*server)

// WithVendorWebhook 啟用 POST /webhooks/vendor，並以 secret 驗證 X-Signature（HMAC-SHA256）。
// secret 為空時不註冊這條路由（回 404），避免用空密鑰驗簽。
func WithVendorWebhook(secret []byte) Option {
	return func(s *server) { s.webhookSecret = secret }
}

// WithSampleVideo 啟用 GET /v1/cameos/{id}/video，所有 SUCCEEDED 訂單都回這支影片。
// video 為空時不註冊這條路由（回 404）。
func WithSampleVideo(video []byte) Option {
	return func(s *server) { s.sampleVideo = video }
}

// WithPublicBaseURL 設定分享網址的 base（例如 http://localhost:8080）；尾端的 / 會去掉。
func WithPublicBaseURL(base string) Option {
	return func(s *server) { s.publicBaseURL = strings.TrimRight(base, "/") }
}

// WithDebugAPI 註冊 /debug/*（異常登記與重設）；不傳時這些路徑回一般 404。
func WithDebugAPI() Option {
	return func(s *server) { s.debug = true }
}

// Handler 組出所有路由。檢查順序：先比對路由（404、405），再驗 token（401）。
// 廠商 webhook 不用 token，改以簽章驗證。
func Handler(svc *usecase.Service, opts ...Option) http.Handler {
	s := &server{svc: svc}
	for _, o := range opts {
		o(s)
	}
	if s.debug {
		svc.SetUploadExpiryFault(func() bool { _, ok := s.faults.take(faultUploadExpired, ""); return ok })
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, _ *http.Request) {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "找不到這個路徑", false)
	})
	handleGET(mux, "/v1/clips", s.clips)
	handleGET(mux, "/v1/points", s.points)
	handleGET(mux, "/v1/points/ledger", s.ledger)
	handleGET(mux, "/v1/quota", s.quota)
	handleMethods(mux, "/v1/cameos", map[string]http.HandlerFunc{http.MethodGet: s.listCameos, http.MethodPost: s.createCameo})
	handleMethods(mux, "/v1/cameos/{id}", map[string]http.HandlerFunc{http.MethodGet: s.getCameo})
	handleMethods(mux, "/v1/cameos/{id}/selfie", map[string]http.HandlerFunc{http.MethodPut: s.putSelfie})
	handleMethods(mux, "/v1/cameos/{id}/submit", map[string]http.HandlerFunc{http.MethodPost: s.submitCameo})
	if len(s.sampleVideo) > 0 {
		handleMethods(mux, "/v1/cameos/{id}/video", map[string]http.HandlerFunc{http.MethodGet: s.cameoVideo})
	}
	handleMethods(mux, "/v1/cameos/{id}/share", map[string]http.HandlerFunc{http.MethodPut: s.shareCameo, http.MethodDelete: s.unshareCameo})
	if len(s.sampleVideo) > 0 {
		// 分享頁與分享影片免登入，以 token 為憑。
		handlePublic(mux, "/s/{token}", map[string]http.HandlerFunc{http.MethodGet: s.sharePage})
		handlePublic(mux, "/s/{token}/video", map[string]http.HandlerFunc{http.MethodGet: s.sharedVideo})
	}
	if s.debug {
		handleMethods(mux, "/debug/faults", map[string]http.HandlerFunc{http.MethodGet: s.debugListFaults, http.MethodPost: s.debugAddFault, http.MethodDelete: s.debugClearFaults})
		handleMethods(mux, "/debug/faults/consume", map[string]http.HandlerFunc{http.MethodPost: s.debugConsumeFault})
		handleMethods(mux, "/debug/reset", map[string]http.HandlerFunc{http.MethodPost: s.debugReset})
	}
	if len(s.webhookSecret) > 0 {
		mux.HandleFunc("POST /webhooks/vendor", s.vendorWebhook)
		mux.HandleFunc("/webhooks/vendor", func(w http.ResponseWriter, _ *http.Request) {
			w.Header().Set("Allow", http.MethodPost)
			writeError(w, http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", "不支援這個方法", false)
		})
	}
	return mux
}

// handleGET 註冊一條只收 GET（含 HEAD）、需要 token 的路由；path 可含 {id} 等路徑參數。
// 其他方法回 405 並帶 Allow。
func handleGET(mux *http.ServeMux, path string, h http.HandlerFunc) {
	handleMethods(mux, path, map[string]http.HandlerFunc{http.MethodGet: h})
}

// handleMethods 在同一路徑註冊多個方法（都需要 token）。其他方法回 405 並帶 Allow；
// 有 GET 時 Allow 也列出 HEAD。
func handleMethods(mux *http.ServeMux, path string, handlers map[string]http.HandlerFunc) {
	registerMethods(mux, path, handlers, requireAuth)
}

// handlePublic 與 handleMethods 相同，但不驗 token（分享頁以 token 為憑）。
func handlePublic(mux *http.ServeMux, path string, handlers map[string]http.HandlerFunc) {
	registerMethods(mux, path, handlers, func(h http.HandlerFunc) http.Handler { return h })
}

func registerMethods(mux *http.ServeMux, path string, handlers map[string]http.HandlerFunc, wrap func(http.HandlerFunc) http.Handler) {
	methods := make([]string, 0, len(handlers))
	for m, h := range handlers {
		mux.Handle(m+" "+path, wrap(h))
		methods = append(methods, m)
		if m == http.MethodGet {
			methods = append(methods, http.MethodHead)
		}
	}
	sort.Strings(methods)
	allow := strings.Join(methods, ", ")
	mux.HandleFunc(path, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Allow", allow)
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
	svc           *usecase.Service
	webhookSecret []byte
	sampleVideo   []byte
	publicBaseURL string
	debug         bool
	faults        faultRegistry
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

func (s *server) listCameos(w http.ResponseWriter, r *http.Request) {
	page, err := s.svc.Cameos(r.Context(), r.URL.Query().Get("cursor"))
	if errors.Is(err, domain.ErrInvalidCursor) {
		writeError(w, http.StatusBadRequest, "INVALID_CURSOR", "cursor 無效", false)
		return
	}
	if err != nil {
		writeInternal(w, r, err)
		return
	}
	items := make([]cameoJSON, 0, len(page.Items))
	for _, c := range page.Items {
		items = append(items, s.toCameoJSON(c))
	}
	var next *string
	if page.NextCursor != "" {
		next = &page.NextCursor
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items, "next_cursor": next})
}

func (s *server) quota(w http.ResponseWriter, r *http.Request) {
	q, err := s.svc.Quota(r.Context())
	if err != nil {
		writeInternal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, struct {
		Remaining int    `json:"remaining"`
		ResetsAt  string `json:"resets_at"`
		Display   string `json:"display"`
	}{q.Remaining, q.ResetsAt.In(taipei).Format(time.RFC3339), q.Display})
}

type cameoJSON struct {
	ID   string `json:"id"`
	Clip struct {
		ID    string `json:"id"`
		Title string `json:"title"`
		Cost  int    `json:"cost"`
	} `json:"clip"`
	Status  string `json:"status"`
	Display struct {
		Title   string `json:"title"`
		Message string `json:"message"`
	} `json:"display"`
	PointsState    string  `json:"points_state"`
	FailureReason  *string `json:"failure_reason"`
	VideoAvailable bool    `json:"video_available"`
	Share          struct {
		Active bool    `json:"active"`
		URL    *string `json:"url"`
	} `json:"share"`
	CreatedAt string `json:"created_at"`
	UpdatedAt string `json:"updated_at"`
}

// cameoResponse 是 POST 與 GET 訂單共用的回應：upload 只在 AWAITING_UPLOAD 時有值，其餘為 null。
type cameoResponse struct {
	Cameo  cameoJSON   `json:"cameo"`
	Upload *uploadJSON `json:"upload"`
}

type uploadJSON struct {
	URL       string `json:"url"`
	ExpiresAt string `json:"expires_at"`
}

func (s *server) toCameoResponse(c domain.Cameo) cameoResponse {
	resp := cameoResponse{Cameo: s.toCameoJSON(c)}
	if c.Status == domain.StatusAwaitingUpload {
		resp.Upload = &uploadJSON{
			URL:       "/v1/cameos/" + c.ID + "/selfie",
			ExpiresAt: c.UploadExpiresAt.In(taipei).Format(time.RFC3339),
		}
	}
	return resp
}

// validIdempotencyKey 只接受去除前後 ASCII 空白（空格、Tab）後 1 到 128 bytes、且皆為 0x21–0x7E 的可見 ASCII。
func validIdempotencyKey(key string) bool {
	if key == "" || len(key) > 128 {
		return false
	}
	for i := 0; i < len(key); i++ {
		if key[i] < 0x21 || key[i] > 0x7E {
			return false
		}
	}
	return true
}

func (s *server) toCameoJSON(c domain.Cameo) cameoJSON {
	var j cameoJSON
	j.ID = c.ID
	j.Clip.ID, j.Clip.Title, j.Clip.Cost = c.Clip.ID, c.Clip.Title, c.Clip.Cost
	j.Status = c.Status
	d := c.Display()
	j.Display.Title, j.Display.Message = d.Title, d.Message
	j.PointsState = c.PointsState
	j.FailureReason = c.FailureReason
	j.VideoAvailable = c.VideoAvailable()
	if c.ShareToken != nil {
		u := shareURL(s.publicBaseURL, *c.ShareToken)
		j.Share.Active, j.Share.URL = true, &u
	}
	j.CreatedAt = c.CreatedAt.In(taipei).Format(time.RFC3339)
	j.UpdatedAt = c.UpdatedAt.In(taipei).Format(time.RFC3339)
	return j
}

// shareURL 組出分享網址：base（去掉尾端 /）+ /s/ + token。
func shareURL(base, token string) string {
	return strings.TrimRight(base, "/") + "/s/" + token
}

// validDebugBase 檢查 debug_base_url 是否為 http/https 的 origin：
// 有 host，沒有 userinfo、query、fragment，path 只能是空或 /。
func validDebugBase(raw string) bool {
	if strings.Contains(raw, "#") { // 空 fragment（http://h#）解析後看不出來，只能看原字串
		return false
	}
	u, err := url.Parse(raw)
	if err != nil {
		return false
	}
	return (u.Scheme == "http" || u.Scheme == "https") && u.Hostname() != "" && u.User == nil &&
		u.RawQuery == "" && !u.ForceQuery && u.Fragment == "" && u.RawFragment == "" &&
		(u.Path == "" || u.Path == "/") && u.RawPath == "" && u.Opaque == ""
}

func (s *server) createCameo(w http.ResponseWriter, r *http.Request) {
	key := strings.Trim(r.Header.Get("Idempotency-Key"), " \t")
	if !validIdempotencyKey(key) {
		writeError(w, http.StatusBadRequest, "INVALID_REQUEST", "Idempotency-Key 缺少或格式無效", false)
		return
	}
	var body struct {
		ClipID string `json:"clip_id"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_REQUEST", "請求內容無效", false)
		return
	}
	res, err := s.svc.CreateCameo(r.Context(), key, body.ClipID)
	switch {
	case errors.Is(err, domain.ErrIdempotencyConflict):
		writeError(w, http.StatusConflict, "IDEMPOTENCY_CONFLICT", "同一個請求編號不能用於不同片段", false)
		return
	case errors.Is(err, domain.ErrClipNotFound):
		writeError(w, http.StatusNotFound, "CLIP_NOT_FOUND", "找不到這個片段", false)
		return
	case errors.Is(err, domain.ErrDailyLimitReached):
		writeError(w, http.StatusTooManyRequests, "DAILY_LIMIT_REACHED", "今日客串次數已達上限，明天再來", false)
		return
	case errors.Is(err, domain.ErrInsufficientPoints):
		writeError(w, http.StatusPaymentRequired, "INSUFFICIENT_POINTS", "點數不足", false)
		return
	case err != nil:
		writeInternal(w, r, err)
		return
	}
	s.loseResponseIfArmed("create")
	status := http.StatusOK
	if res.Created {
		status = http.StatusCreated
	}
	writeJSON(w, status, s.toCameoResponse(res.Cameo))
}

func (s *server) getCameo(w http.ResponseWriter, r *http.Request) {
	c, err := s.svc.Cameo(r.Context(), r.PathValue("id"))
	if writeCameoError(w, err) {
		return
	}
	if err != nil {
		writeInternal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, s.toCameoResponse(c))
}

// cameoVideo 回固定範例影片，Range 與 HEAD 由 http.ServeContent 處理；不設 ETag／Last-Modified。
func (s *server) cameoVideo(w http.ResponseWriter, r *http.Request) {
	err := s.svc.CameoVideo(r.Context(), r.PathValue("id"))
	switch {
	case errors.Is(err, domain.ErrVideoNotReady):
		writeError(w, http.StatusNotFound, "VIDEO_NOT_READY", "影片尚未完成", false)
		return
	case writeCameoError(w, err):
		return
	case err != nil:
		writeInternal(w, r, err)
		return
	}
	s.serveVideo(w, r)
}

// serveVideo 送出範例影片；Range 與 HEAD 由 http.ServeContent 處理。
func (s *server) serveVideo(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "video/mp4")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	http.ServeContent(w, r, "", time.Time{}, bytes.NewReader(s.sampleVideo))
}

// shareCameo 建立分享（冪等）；成功一律 200，不讀 request body。
// debug 開啟時可用 query debug_base_url 改變回應網址的 base（不存資料庫）；未開啟時忽略。
func (s *server) shareCameo(w http.ResponseWriter, r *http.Request) {
	base := s.publicBaseURL
	if s.debug {
		// r.URL.Query() 會丟掉解析失敗的項目；ParseQuery 有 err 一律 400（即使有部分結果）。
		// query 有任何語法錯誤（含 ; 分隔、不合法的 % 編碼）都回 400，因為出錯時無法確定是否帶了 debug_base_url。
		q, qerr := url.ParseQuery(r.URL.RawQuery)
		vals, ok := q["debug_base_url"]
		if qerr != nil || (ok && (len(vals) != 1 || !validDebugBase(vals[0]))) {
			writeError(w, http.StatusBadRequest, "INVALID_REQUEST", "debug_base_url 必須是 http 或 https 的 origin", false)
			return
		}
		if ok {
			base = vals[0]
		}
	}
	c, err := s.svc.ShareCameo(r.Context(), r.PathValue("id"))
	switch {
	case errors.Is(err, domain.ErrNotShareable):
		writeError(w, http.StatusConflict, "NOT_SHAREABLE", "這筆客串目前無法分享", false)
		return
	case writeCameoError(w, err):
		return
	case err != nil:
		writeInternal(w, r, err)
		return
	}
	share := s.toCameoJSON(c).Share
	if c.ShareToken != nil {
		u := shareURL(base, *c.ShareToken)
		share.URL = &u
	}
	writeJSON(w, http.StatusOK, map[string]any{"share": share})
}

// unshareCameo 取消分享，冪等，成功回 204。
func (s *server) unshareCameo(w http.ResponseWriter, r *http.Request) {
	err := s.svc.UnshareCameo(r.Context(), r.PathValue("id"))
	switch {
	case writeCameoError(w, err):
		return
	case err != nil:
		writeInternal(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// setShareHeaders 是 /s/* 所有回應共用的 header：不快取、不被搜尋引擎收錄、不外洩 Referer（網址含 token）。
func setShareHeaders(w http.ResponseWriter) {
	h := w.Header()
	h.Set("Cache-Control", "no-store")
	h.Set("X-Robots-Tag", "noindex")
	h.Set("Referrer-Policy", "no-referrer")
}

var sharePageTmpl = template.Must(template.New("share").Parse(`<!doctype html>
<html lang="zh-Hant"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>客串短片</title></head>
<body><video controls playsinline src="/s/{{.}}/video" style="max-width:100%"></video></body></html>
`))

func writeHTML(w http.ResponseWriter, status int, body string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(status)
	_, _ = w.Write([]byte(body))
}

// sharePage 是免登入的分享網頁，只放影片，不顯示任何訂單資料。無效 token 回簡短 HTML 404。
func (s *server) sharePage(w http.ResponseWriter, r *http.Request) {
	setShareHeaders(w)
	token := r.PathValue("token")
	ok, err := s.svc.SharedVideoAvailable(r.Context(), token)
	switch {
	case err != nil:
		slog.Error("request failed", "route", r.Pattern, "err", err) // 不記 path：含分享 token
		writeHTML(w, http.StatusInternalServerError, "<!doctype html><meta charset=\"utf-8\"><title>發生問題</title><p>伺服器暫時發生問題，請稍後再試。")
	case !ok:
		writeHTML(w, http.StatusNotFound, "<!doctype html><meta charset=\"utf-8\"><title>找不到</title><p>找不到這個分享。")
	default:
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		if err := sharePageTmpl.Execute(w, token); err != nil {
			slog.Error("write share page", "err", err)
		}
	}
}

// sharedVideo 是免登入的分享影片；無效 token 回 JSON 404。
func (s *server) sharedVideo(w http.ResponseWriter, r *http.Request) {
	setShareHeaders(w)
	ok, err := s.svc.SharedVideoAvailable(r.Context(), r.PathValue("token"))
	switch {
	case err != nil:
		slog.Error("request failed", "route", r.Pattern, "err", err) // 不記 path：含分享 token
		writeInternalResponse(w)
	case !ok:
		writeError(w, http.StatusNotFound, "NOT_FOUND", "找不到這個分享", false)
	default:
		s.serveVideo(w, r)
	}
}

// putSelfie 只看內容（前 3 bytes 是否為 JPEG），不看 Content-Type。
func (s *server) putSelfie(w http.ResponseWriter, r *http.Request) {
	var body io.Reader = http.MaxBytesReader(w, r.Body, s.svc.MaxSelfieBytes())
	var cut *interruptingReader
	if s.debug {
		if cut = s.wrapUploadFault(body); cut != nil {
			body = cut
		}
	}
	c, err := s.svc.UploadSelfie(r.Context(), r.PathValue("id"), body)
	if cut != nil && cut.tripped {
		panic(http.ErrAbortHandler) // upload_interrupted：不寫回應；暫存檔已由 UploadSelfie 丟棄
	}
	var tooLarge *http.MaxBytesError
	var readErr *usecase.BodyReadError
	switch {
	case errors.As(err, &tooLarge):
		writeError(w, http.StatusRequestEntityTooLarge, "SELFIE_TOO_LARGE", "照片太大", false)
		return
	case errors.Is(err, domain.ErrInvalidSelfie):
		writeError(w, http.StatusBadRequest, "INVALID_SELFIE", "照片格式不正確", false)
		return
	case errors.As(err, &readErr):
		slog.Warn("upload body read failed", "path", r.URL.Path, "err", err)
		writeError(w, http.StatusRequestTimeout, "UPLOAD_INTERRUPTED", "上傳中斷，請重試", true)
		return
	case errors.Is(err, domain.ErrInvalidState):
		writeError(w, http.StatusConflict, "INVALID_STATE", "這筆客串目前無法上傳", false)
		return
	case writeCameoError(w, err):
		return
	case err != nil:
		writeInternal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, s.toCameoResponse(c))
}

func (s *server) submitCameo(w http.ResponseWriter, r *http.Request) {
	c, err := s.svc.SubmitCameo(r.Context(), r.PathValue("id"))
	switch {
	case errors.Is(err, domain.ErrSelfieNotUploaded):
		writeError(w, http.StatusConflict, "SELFIE_NOT_UPLOADED", "請先上傳照片", false)
		return
	case writeCameoError(w, err):
		return
	case err != nil:
		writeInternal(w, r, err)
		return
	}
	s.loseResponseIfArmed("submit")
	writeJSON(w, http.StatusOK, s.toCameoResponse(c))
}

// writeCameoError 處理上傳與送出共有的錯誤；有寫出回應時回傳 true。
func writeCameoError(w http.ResponseWriter, err error) bool {
	switch {
	case errors.Is(err, domain.ErrCameoNotFound):
		writeError(w, http.StatusNotFound, "CAMEO_NOT_FOUND", "找不到這筆客串", false)
	case errors.Is(err, domain.ErrUploadExpired):
		writeError(w, http.StatusGone, "UPLOAD_EXPIRED", domain.UploadExpiredMessage, false)
	default:
		return false
	}
	return true
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
	writeInternalResponse(w)
}

func writeInternalResponse(w http.ResponseWriter) {
	writeError(w, http.StatusInternalServerError, "INTERNAL", "伺服器暫時發生問題，請稍後再試", true)
}
