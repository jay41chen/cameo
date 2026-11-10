package http

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"

	"cameo/backend/faults"
)

// consoleHTML 是 /debug/console 的靜態頁範本（純 HTML/CSS/JS，無外部資源）。
//
//go:embed console.html
var consoleHTML string

const scenariosPlaceholder = "__SCENARIOS__"

// mustRenderConsole 把 backend/faults 的情境填進範本。
// 只在 debug 開啟時產生（Handler 註冊 /debug/console 時呼叫一次）；資料為內嵌建置期內容，壞掉時啟動即失敗。
func mustRenderConsole(tmpl string) string {
	list, err := faults.Load()
	if err != nil {
		panic(fmt.Sprintf("console: load fault scenarios: %v", err))
	}
	// encoding/json 預設會轉義 < > &，可安全放進 <script>。
	b, err := json.Marshal(list)
	if err != nil {
		panic(fmt.Sprintf("console: marshal fault scenarios: %v", err))
	}
	if n := strings.Count(tmpl, scenariosPlaceholder); n != 1 {
		panic(fmt.Sprintf("console: placeholder %s appears %d times, want 1", scenariosPlaceholder, n))
	}
	return strings.Replace(tmpl, scenariosPlaceholder, string(b), 1)
}

// debugConsole 回傳內嵌的除錯控制台頁；token 寫死在頁面 JS，所以這條路由只在 debug 開啟時註冊。
func (s *server) debugConsole(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	writeHTML(w, http.StatusOK, s.consolePage)
}
