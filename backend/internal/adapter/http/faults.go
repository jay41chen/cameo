package http

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"sort"
	"strings"
	"sync"
	"unicode/utf8"
)

// 可登記的異常名稱。8.1 只負責登記，不會真的觸發。
const (
	faultResponseLost             = "response_lost_after_commit"
	faultUploadInterrupted        = "upload_interrupted"
	faultUploadExpired            = "upload_expired"
	faultVendorFailed             = "vendor_failed"
	faultVendorDuplicateCB        = "vendor_duplicate_callback"
	faultVendorNoCallback         = "vendor_no_callback"
	defaultAfterBytes             = 1024
	defaultDuplicateCount         = 3
	statusSucceeded, statusFailed = "SUCCEEDED", "FAILED"
)

// faultEntry 是一筆已登記的異常。Target 為 nil 表示沒有 target。
type faultEntry struct {
	Name   string         `json:"name"`
	Target *string        `json:"target"`
	Times  int            `json:"times"`
	Params map[string]any `json:"params"`
}

// faultRegistry 是記憶體內的異常登記，同名只留一筆（後設定的覆蓋，並排到最後）。API 重啟就清空。
type faultRegistry struct {
	mu      sync.Mutex
	entries []faultEntry
}

func cloneEntry(e faultEntry) faultEntry {
	c := e
	if e.Target != nil {
		t := *e.Target
		c.Target = &t
	}
	c.Params = make(map[string]any, len(e.Params))
	for k, v := range e.Params {
		if s, ok := v.(*string); ok && s != nil { // 不共用指標
			v = *s
		}
		c.Params[k] = v
	}
	return c
}

// isVendorFault 表示三種 vendor_* 異常；同時只允許登記其中一種。
func isVendorFault(name string) bool { return strings.HasPrefix(name, "vendor_") }

// errVendorFaultConflict 表示已登記另一種 vendor_* 異常。
var errVendorFaultConflict = invalidFault("已登記另一種 vendor_* 異常，請先 DELETE /debug/faults")

// add 登記一筆異常；同名的舊設定先移除，新的排在最後。
// 已登記不同名的 vendor_* 時，再登記另一種 vendor_* 回 errVendorFaultConflict，清單不變。
// 檢查與寫入在同一個鎖內。
func (r *faultRegistry) add(e faultEntry) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	kept := r.entries[:0:0]
	for _, old := range r.entries {
		if old.Name == e.Name {
			continue
		}
		if isVendorFault(e.Name) && isVendorFault(old.Name) {
			return errVendorFaultConflict
		}
		kept = append(kept, old)
	}
	r.entries = append(kept, cloneEntry(e))
	return nil
}

// take 在 name 與 target 都相符時扣 1 次 times 並回傳 params；歸零就移除。無 target 的異常以空字串比對。
// target 不符不扣次數。
func (r *faultRegistry) take(name, target string) (map[string]any, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	for i := range r.entries {
		e := &r.entries[i]
		if e.Name != name {
			continue
		}
		have := ""
		if e.Target != nil {
			have = *e.Target
		}
		if have != target {
			return nil, false
		}
		params := cloneEntry(*e).Params
		e.Times--
		if e.Times <= 0 {
			r.entries = append(r.entries[:i], r.entries[i+1:]...)
		}
		return params, true
	}
	return nil, false
}

// list 回傳深拷貝，依登記順序。
func (r *faultRegistry) list() []faultEntry {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make([]faultEntry, 0, len(r.entries))
	for _, e := range r.entries {
		out = append(out, cloneEntry(e))
	}
	return out
}

func (r *faultRegistry) clear() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.entries = nil
}

// faultDef 定義一種異常：是否用 target，以及 params 的驗證與預設值。
type faultDef struct {
	targets []string // 非空表示 target 必填且只能是其中之一
	params  func(raw map[string]json.RawMessage) (map[string]any, error)
}

var faultDefs = map[string]faultDef{
	faultResponseLost:      {targets: []string{"create", "submit"}, params: noParams},
	faultUploadInterrupted: {params: positiveIntParams("after_bytes", defaultAfterBytes)},
	faultUploadExpired:     {params: noParams},
	faultVendorFailed:      {params: vendorFailedParams},
	faultVendorDuplicateCB: {params: duplicateCallbackParams},
	faultVendorNoCallback:  {params: noParams},
}

func availableFaults() []string {
	names := make([]string, 0, len(faultDefs))
	for n := range faultDefs {
		names = append(names, n)
	}
	sort.Strings(names)
	return names
}

type invalidFault string

func (e invalidFault) Error() string { return string(e) }

func rejectUnknown(raw map[string]json.RawMessage, allowed ...string) error {
	for k := range raw {
		ok := false
		for _, a := range allowed {
			ok = ok || a == k
		}
		if !ok {
			return invalidFault(fmt.Sprintf("未知的參數 %q", k))
		}
	}
	return nil
}

func noParams(raw map[string]json.RawMessage) (map[string]any, error) {
	if err := rejectUnknown(raw); err != nil {
		return nil, err
	}
	return map[string]any{}, nil
}

// positiveInt 解析正整數；省略回傳 def。
func positiveInt(raw map[string]json.RawMessage, key string, def int) (int, error) {
	v, ok := raw[key]
	if !ok {
		return def, nil
	}
	var n int
	if err := json.Unmarshal(v, &n); err != nil || n < 1 {
		return 0, invalidFault(key + " 必須是正整數")
	}
	return n, nil
}

func positiveIntParams(key string, def int) func(map[string]json.RawMessage) (map[string]any, error) {
	return func(raw map[string]json.RawMessage) (map[string]any, error) {
		if err := rejectUnknown(raw, key); err != nil {
			return nil, err
		}
		n, err := positiveInt(raw, key, def)
		if err != nil {
			return nil, err
		}
		return map[string]any{key: n}, nil
	}
}

func vendorFailedParams(raw map[string]json.RawMessage) (map[string]any, error) {
	if err := rejectUnknown(raw, "reason"); err != nil {
		return nil, err
	}
	var reason *string
	if v, ok := raw["reason"]; ok {
		if err := json.Unmarshal(v, &reason); err != nil {
			return nil, invalidFault("reason 必須是字串或 null")
		}
	}
	return map[string]any{"reason": reason}, nil
}

func duplicateCallbackParams(raw map[string]json.RawMessage) (map[string]any, error) {
	if err := rejectUnknown(raw, "count", "status"); err != nil {
		return nil, err
	}
	n, err := positiveInt(raw, "count", defaultDuplicateCount)
	if err != nil {
		return nil, err
	}
	status := statusSucceeded
	if v, ok := raw["status"]; ok {
		if err := json.Unmarshal(v, &status); err != nil || (status != statusSucceeded && status != statusFailed) {
			return nil, invalidFault("status 只能是 SUCCEEDED 或 FAILED")
		}
	}
	return map[string]any{"count": n, "status": status}, nil
}

// isAbsentOrNull 表示欄位省略或為 JSON null。
func isAbsentOrNull(raw json.RawMessage) bool {
	t := bytes.TrimSpace(raw)
	return len(t) == 0 || bytes.Equal(t, []byte("null"))
}

// buildFault 驗證登記請求並套用預設值。未知名稱回 unknown=true。
// rawName、rawTarget、rawTimes 是原始 JSON 值，型別或值不合法都回 invalidFault。
func buildFault(rawName, rawTarget, rawTimes, rawParams json.RawMessage) (e faultEntry, unknown bool, err error) {
	if isAbsentOrNull(rawName) {
		return e, false, invalidFault("name 必填，且必須是字串")
	}
	var name string
	if err := json.Unmarshal(rawName, &name); err != nil {
		return e, false, invalidFault("name 必須是字串")
	}
	def, ok := faultDefs[name]
	if !ok {
		return e, true, nil
	}
	n := 1
	if !isAbsentOrNull(rawTimes) {
		if err := json.Unmarshal(rawTimes, &n); err != nil || n < 1 {
			return e, false, invalidFault("times 必須是正整數")
		}
	}
	var target *string
	if !isAbsentOrNull(rawTarget) {
		var t string
		if err := json.Unmarshal(rawTarget, &t); err != nil {
			return e, false, invalidFault("target 必須是字串")
		}
		target = &t
	}
	if len(def.targets) == 0 {
		if target != nil {
			return e, false, invalidFault("這個異常不使用 target")
		}
	} else {
		valid := false
		for _, t := range def.targets {
			valid = valid || (target != nil && *target == t)
		}
		if !valid {
			return e, false, invalidFault("target 必填，且只能是 create 或 submit")
		}
	}
	raw := map[string]json.RawMessage{}
	if t := bytes.TrimSpace(rawParams); len(t) > 0 && !bytes.Equal(t, []byte("null")) {
		if err := json.Unmarshal(t, &raw); err != nil {
			return e, false, invalidFault("params 必須是物件")
		}
	}
	params, err := def.params(raw)
	if err != nil {
		return e, false, err
	}
	return faultEntry{Name: name, Target: target, Times: n, Params: params}, false, nil
}

func (s *server) faultsBody() map[string]any {
	return map[string]any{"active": s.faults.list(), "available": availableFaults()}
}

func (s *server) debugListFaults(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, s.faultsBody())
}

func (s *server) debugAddFault(w http.ResponseWriter, r *http.Request) {
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 1<<16))
	if err != nil || !utf8.Valid(raw) {
		writeError(w, http.StatusBadRequest, "INVALID_REQUEST", "請求內容無效", false)
		return
	}
	var body struct {
		Name   json.RawMessage `json:"name"`
		Target json.RawMessage `json:"target"`
		Times  json.RawMessage `json:"times"`
		Params json.RawMessage `json:"params"`
	}
	// Unmarshal 會拒絕語法錯誤、尾端內容與多個值；頂層必須是物件（null 與陣列不行）。
	if t := bytes.TrimSpace(raw); len(t) == 0 || t[0] != '{' || json.Unmarshal(raw, &body) != nil {
		writeError(w, http.StatusBadRequest, "INVALID_REQUEST", "請求內容無效", false)
		return
	}
	e, unknown, err := buildFault(body.Name, body.Target, body.Times, body.Params)
	if unknown {
		writeError(w, http.StatusBadRequest, "UNKNOWN_FAULT", "未知的異常", false)
		return
	}
	if err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_FAULT", err.Error(), false)
		return
	}
	if err := s.faults.add(e); err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_FAULT", err.Error(), false)
		return
	}
	writeJSON(w, http.StatusOK, s.faultsBody())
}

func (s *server) debugClearFaults(w http.ResponseWriter, _ *http.Request) {
	s.faults.clear()
	w.WriteHeader(http.StatusNoContent)
}

// debugReset 先清資料，成功後才清異常；失敗時異常清單不變。不讀 request body。
func (s *server) debugReset(w http.ResponseWriter, r *http.Request) {
	if err := s.svc.ResetAll(r.Context()); err != nil {
		writeInternal(w, r, err)
		return
	}
	s.faults.clear()
	w.WriteHeader(http.StatusNoContent)
}
