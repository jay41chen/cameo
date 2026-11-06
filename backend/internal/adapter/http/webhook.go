package http

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"unicode/utf8"

	"cameo/backend/internal/domain"
	"cameo/backend/internal/usecase"
)

// maxWebhookBody 是廠商回呼 body 的大小上限。
const maxWebhookBody = 1 << 16

// validSignature 以 hmac.Equal 比對 X-Signature（hex）與對原始 body 的 HMAC-SHA256。
func validSignature(secret, body []byte, header string) bool {
	got, err := hex.DecodeString(header)
	if err != nil || len(got) == 0 {
		return false
	}
	mac := hmac.New(sha256.New, secret)
	mac.Write(body)
	return hmac.Equal(got, mac.Sum(nil))
}

// vendorWebhook 處理 POST /webhooks/vendor：先驗簽（未通過前不解析 JSON、不碰資料庫），再驗格式，再交給 usecase。
func (s *server) vendorWebhook(w http.ResponseWriter, r *http.Request) {
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxWebhookBody))
	if err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_REQUEST", "請求內容無效", false)
		return
	}
	if !validSignature(s.webhookSecret, raw, r.Header.Get("X-Signature")) {
		writeError(w, http.StatusUnauthorized, "INVALID_SIGNATURE", "簽章無效", false)
		return
	}
	var body struct {
		EventID  string  `json:"event_id"`
		JobID    string  `json:"job_id"`
		Status   string  `json:"status"`
		VideoURL string  `json:"video_url"`
		Reason   *string `json:"reason"`
	}
	// json.Unmarshal 會把非法 UTF-8 換成 U+FFFD 而不報錯，所以先檢查原始位元組（同 decodeCursor）。
	if !utf8.Valid(raw) {
		writeError(w, http.StatusBadRequest, "INVALID_REQUEST", "請求內容無效", false)
		return
	}
	if err := json.Unmarshal(raw, &body); err != nil || !validCallback(body.EventID, body.JobID, body.Status, body.VideoURL, body.Reason) {
		writeError(w, http.StatusBadRequest, "INVALID_REQUEST", "請求內容無效", false)
		return
	}
	err = s.svc.HandleVendorCallback(r.Context(), usecase.VendorEvent{
		EventID: body.EventID, JobID: body.JobID, Status: body.Status, VideoURL: body.VideoURL, Reason: body.Reason,
	})
	switch {
	case errors.Is(err, domain.ErrCameoNotFound):
		writeError(w, http.StatusNotFound, "CAMEO_NOT_FOUND", "找不到這筆客串", false)
		return
	case err != nil:
		writeInternal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, struct{}{})
}

func validCallback(eventID, jobID, status, videoURL string, reason *string) bool {
	if eventID == "" || jobID == "" || !domain.ValidText(eventID) || !domain.ValidText(jobID) || !domain.ValidText(videoURL) {
		return false
	}
	if reason != nil && !domain.ValidText(*reason) {
		return false
	}
	switch status {
	case usecase.VendorSucceeded:
		return videoURL != ""
	case usecase.VendorFailed:
		return true
	}
	return false
}
