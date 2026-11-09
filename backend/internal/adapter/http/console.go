package http

import (
	_ "embed"
	"net/http"
)

// consoleHTML 是 /debug/console 的靜態頁（純 HTML/CSS/JS，無外部資源）。
//
//go:embed console.html
var consoleHTML string

// debugConsole 回傳內嵌的除錯控制台頁；token 寫死在頁面 JS，所以這條路由只在 debug 開啟時註冊。
func (s *server) debugConsole(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	writeHTML(w, http.StatusOK, consoleHTML)
}
