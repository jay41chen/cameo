package http

import (
	"bytes"
	"encoding/json"
	"math"
	"net/http"
	"strconv"
	"sync"
	"time"

	"cameo/backend/internal/usecase"
)

// 執行期設定（debug 用）的合法範圍。
const (
	minDailyLimit, maxDailyLimit = 1, 1000
	minTTLSec, maxTTLSec         = 10, 1800
	minDelaySec, maxDelaySec     = 0.0, 600.0
	minInitPoints, maxInitPoints = 0, 100000
)

// runtimeConfig 是記憶體內、可同時讀寫的執行期設定，實作 usecase.RuntimeConfig。
// vendorDelay 為 nil 表示不覆寫（vendor-sim 用自己的 VENDOR_DELAY_SEC）；
// initialPoints 為 nil 表示不覆寫（reset 時餘額回到 seed_balance）。
type runtimeConfig struct {
	mu          sync.RWMutex
	dailyLimit  int
	ttl         time.Duration
	vendorDelay *float64
	initialPts  *int
}

func newRuntimeConfig(base usecase.Config) *runtimeConfig {
	return &runtimeConfig{dailyLimit: base.DailyLimit, ttl: base.GenerationTTL}
}

func (c *runtimeConfig) DailyLimit() int {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.dailyLimit
}

func (c *runtimeConfig) GenerationTTL() time.Duration {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.ttl
}

// VendorDelaySec 回傳目前的廠商延遲覆寫值（nil 表示不覆寫）。
func (c *runtimeConfig) VendorDelaySec() *float64 {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.vendorDelay
}

// InitialPoints 回傳目前的初始點數覆寫值的複本（nil 表示不覆寫）。
func (c *runtimeConfig) InitialPoints() *int {
	c.mu.RLock()
	defer c.mu.RUnlock()
	if c.initialPts == nil {
		return nil
	}
	v := *c.initialPts
	return &v
}

// view 在同一把讀鎖內取出完整設定。generation_ttl_sec 無條件捨去到整秒。
func (c *runtimeConfig) view() map[string]any {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.viewLocked()
}

// viewLocked 要求呼叫者已持有鎖。
func (c *runtimeConfig) viewLocked() map[string]any {
	return map[string]any{
		"daily_limit":        c.dailyLimit,
		"generation_ttl_sec": int64(c.ttl / time.Second),
		"vendor_delay_sec":   c.vendorDelay,
		"initial_points":     c.initialPts,
	}
}

// apply 在同一把寫鎖內寫入有帶的欄位並組出回應用的 view，避免回應混到並行請求的值。
func (c *runtimeConfig) apply(hasLimit bool, limit int, hasTTL bool, ttlSec int, hasDelay bool, delay *float64, hasInit bool, init *int) map[string]any {
	c.mu.Lock()
	defer c.mu.Unlock()
	if hasLimit {
		c.dailyLimit = limit
	}
	if hasTTL {
		c.ttl = time.Duration(ttlSec) * time.Second
	}
	if hasDelay {
		c.vendorDelay = delay
	}
	if hasInit {
		c.initialPts = init
	}
	return c.viewLocked()
}

func (s *server) debugGetConfig(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, s.rc.view())
}

func invalidConfig(w http.ResponseWriter, msg string) {
	writeError(w, http.StatusBadRequest, "INVALID_CONFIG", msg, false)
}

// intInRange 只接受「可選負號＋純數字」的 JSON token（3.0、3e0、字串、null 都不算整數）且在範圍內。
func intInRange(raw json.RawMessage, lo, hi int) (int, bool) {
	t := bytes.TrimSpace(raw)
	digits := t
	if len(digits) > 0 && digits[0] == '-' {
		digits = digits[1:]
	}
	if len(digits) == 0 {
		return 0, false
	}
	for _, b := range digits {
		if b < '0' || b > '9' {
			return 0, false
		}
	}
	n, err := strconv.ParseInt(string(t), 10, 64)
	if err != nil || n < int64(lo) || n > int64(hi) {
		return 0, false
	}
	return int(n), true
}

// debugPutConfig 收任意子集；全部驗過後才在同一把寫鎖內寫入，任一欄位不合法就全部不改。
func (s *server) debugPutConfig(w http.ResponseWriter, r *http.Request) {
	raw, ok := readJSONObject(w, r)
	if !ok {
		return
	}
	var fields map[string]json.RawMessage
	if json.Unmarshal(raw, &fields) != nil {
		writeError(w, http.StatusBadRequest, "INVALID_REQUEST", "請求內容無效", false)
		return
	}
	if len(fields) == 0 {
		invalidConfig(w, "至少要帶一個設定欄位")
		return
	}
	for k := range fields {
		if k != "daily_limit" && k != "generation_ttl_sec" && k != "vendor_delay_sec" && k != "initial_points" {
			invalidConfig(w, "未知的設定欄位："+k)
			return
		}
	}
	var (
		limit, ttlSec int
		delay         *float64
		initPts       *int
	)
	rawLimit, hasLimit := fields["daily_limit"]
	if hasLimit {
		var ok bool
		if limit, ok = intInRange(rawLimit, minDailyLimit, maxDailyLimit); !ok {
			invalidConfig(w, "daily_limit 必須是 1 到 1000 的整數")
			return
		}
	}
	rawTTL, hasTTL := fields["generation_ttl_sec"]
	if hasTTL {
		var ok bool
		if ttlSec, ok = intInRange(rawTTL, minTTLSec, maxTTLSec); !ok {
			invalidConfig(w, "generation_ttl_sec 必須是 10 到 1800 的整數")
			return
		}
	}
	rawDelay, hasDelay := fields["vendor_delay_sec"]
	if hasDelay && !bytes.Equal(bytes.TrimSpace(rawDelay), []byte("null")) {
		var f float64
		if json.Unmarshal(rawDelay, &f) != nil || math.IsNaN(f) || f < minDelaySec || f > maxDelaySec {
			invalidConfig(w, "vendor_delay_sec 必須是 0 到 600 的數字，或 null")
			return
		}
		if f == 0 {
			f = 0 // 清掉 -0 的負號
		}
		delay = &f
	}

	rawInit, hasInit := fields["initial_points"]
	if hasInit && !bytes.Equal(bytes.TrimSpace(rawInit), []byte("null")) {
		n, ok := intInRange(rawInit, minInitPoints, maxInitPoints)
		if !ok {
			invalidConfig(w, "initial_points 必須是 0 到 100000 的整數，或 null")
			return
		}
		initPts = &n
	}

	writeJSON(w, http.StatusOK, s.rc.apply(hasLimit, limit, hasTTL, ttlSec, hasDelay, delay, hasInit, initPts))
}
