package http

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"unicode/utf8"
)

// readJSONObject 讀取最多 64KB 的請求內容，確認是 UTF-8、語法正確（含無尾端內容、不是多個值）的 JSON 物件。
// 失敗時自己寫 400 INVALID_REQUEST 並回傳 false。
func readJSONObject(w http.ResponseWriter, r *http.Request) ([]byte, bool) {
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 1<<16))
	if err == nil && utf8.Valid(raw) {
		if t := bytes.TrimSpace(raw); len(t) > 0 && t[0] == '{' && json.Valid(raw) {
			return raw, true
		}
	}
	writeError(w, http.StatusBadRequest, "INVALID_REQUEST", "請求內容無效", false)
	return nil, false
}
