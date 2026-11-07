// Command vendor-sim 是模擬的影片生成廠商：收工作、等一段時間後對 api 送回呼。
// 每收到新工作會向 api 的 POST /debug/faults/consume 取一次異常（vendor_failed、vendor_duplicate_callback、
// vendor_no_callback）並照辦；取不到或查詢失敗就當沒有異常，送一次成功回呼。
// 僅供模擬：不接收照片、不提供影片檔。
package main

import (
	"bytes"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"os"
	"strconv"
	"sync"
	"time"
)

// callbackTimeout 是單次回呼的逾時；失敗只記 log，不重試（由 api 的 GENERATION_TTL 兜底）。
const callbackTimeout = 5 * time.Second

const (
	consumeTimeout = 2 * time.Second // 查詢異常的逾時；失敗當沒有異常
	debugToken     = "Bearer test-token"
)

type config struct {
	Delay       time.Duration
	CallbackURL string
	Secret      string
	PublicURL   string // 回呼中 video_url 的 base，例如 http://vendor-sim:9000
	FaultsURL   string // api 的 POST /debug/faults/consume
}

type sim struct {
	cfg    config
	client *http.Client
	fc     *http.Client // 查詢異常用，獨立逾時

	mu   sync.Mutex
	seen map[string]bool
	wg   sync.WaitGroup
}

func newSim(cfg config) *sim {
	return &sim{cfg: cfg, client: &http.Client{Timeout: callbackTimeout}, fc: &http.Client{Timeout: consumeTimeout}, seen: map[string]bool{}}
}

func (s *sim) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /jobs", s.postJob)
	return mux
}

// postJob 收工作：同一 job_id 重送回 202 且不開第二個工作（記憶體內去重）。
func (s *sim) postJob(w http.ResponseWriter, r *http.Request) {
	var body struct {
		JobID string `json:"job_id"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&body); err != nil || body.JobID == "" {
		http.Error(w, `{"code":"INVALID_REQUEST"}`, http.StatusBadRequest)
		return
	}
	s.mu.Lock()
	dup := s.seen[body.JobID]
	s.seen[body.JobID] = true
	s.mu.Unlock()
	if !dup {
		f := s.consumeFault(body.JobID)
		s.wg.Add(1)
		time.AfterFunc(s.cfg.Delay, func() {
			defer s.wg.Done()
			s.dispatch(body.JobID, f)
		})
	}
	w.WriteHeader(http.StatusAccepted)
}

type callbackBody struct {
	EventID  string  `json:"event_id"`
	JobID    string  `json:"job_id"`
	Status   string  `json:"status"`
	VideoURL string  `json:"video_url"`
	Reason   *string `json:"reason"`
}

func sign(secret string, body []byte) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(body)
	return hex.EncodeToString(mac.Sum(nil))
}

func randomID() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}

// fault 是 api 交給 vendor-sim 的一筆異常。
type fault struct {
	Name   string         `json:"name"`
	Params map[string]any `json:"params"`
}

// consumeFault 向 api 取一次異常；任何失敗（連線、逾時、非 200、壞 JSON、未知名稱）都回 nil 並記 log。
func (s *sim) consumeFault(jobID string) *fault {
	req, err := http.NewRequest(http.MethodPost, s.cfg.FaultsURL, nil)
	if err != nil {
		slog.Warn("consume fault: build request", "job_id", jobID, "err", err)
		return nil
	}
	req.Header.Set("Authorization", debugToken)
	resp, err := s.fc.Do(req)
	if err != nil {
		slog.Warn("consume fault failed", "job_id", jobID, "err", err)
		return nil
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 1<<16))
	if err != nil {
		slog.Warn("consume fault: read body", "job_id", jobID, "err", err)
		return nil
	}
	if resp.StatusCode != http.StatusOK {
		slog.Warn("consume fault rejected", "job_id", jobID, "status", resp.StatusCode)
		return nil
	}
	var out struct {
		Fault *fault `json:"fault"`
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		slog.Warn("consume fault: bad json", "job_id", jobID, "err", err)
		return nil
	}
	if out.Fault == nil {
		return nil
	}
	switch out.Fault.Name {
	case faultFailed, faultDuplicate, faultNoCallback:
		return out.Fault
	}
	slog.Warn("consume fault: unknown fault", "job_id", jobID, "name", out.Fault.Name)
	return nil
}

// defaultDuplicateCount 是 vendor_duplicate_callback 缺 count 或不合法時的次數；
// 與 API 的 defaultDuplicateCount 及規格預設一致（3）。不 import API 套件，故在此重複定義。
const defaultDuplicateCount = 3

const (
	faultFailed     = "vendor_failed"
	faultDuplicate  = "vendor_duplicate_callback"
	faultNoCallback = "vendor_no_callback"
)

// dispatch 依異常決定回呼內容；f 為 nil 表示沒有異常。
func (s *sim) dispatch(jobID string, f *fault) {
	if f == nil {
		s.send(s.succeeded(jobID, randomID()))
		return
	}
	switch f.Name {
	case faultFailed:
		var reason *string
		if r, ok := f.Params["reason"].(string); ok {
			reason = &r
		}
		s.send(callbackBody{EventID: randomID(), JobID: jobID, Status: "FAILED", Reason: reason})
	case faultDuplicate:
		count := defaultDuplicateCount
		if n, ok := f.Params["count"].(float64); ok && n >= 1 {
			count = int(n)
		}
		status, _ := f.Params["status"].(string)
		sameID := randomID()
		for i := 1; i <= count; i++ {
			id := sameID
			if i == count {
				id = randomID()
			}
			body := callbackBody{EventID: id, JobID: jobID, Status: "FAILED"}
			if status != "FAILED" {
				body = s.succeeded(jobID, id)
			}
			s.send(body)
		}
	case faultNoCallback:
		slog.Info("no callback (fault)", "job_id", jobID)
	}
}

func (s *sim) succeeded(jobID, eventID string) callbackBody {
	return callbackBody{EventID: eventID, JobID: jobID, Status: "SUCCEEDED", VideoURL: s.cfg.PublicURL + "/videos/" + jobID + ".mp4"}
}

// send 送一次回呼；Header X-Signature 是對原始 body 的 HMAC-SHA256 hex。失敗與非 2xx 只記 log，不重試。
func (s *sim) send(body callbackBody) {
	jobID := body.JobID
	raw, err := json.Marshal(body)
	if err != nil {
		slog.Error("marshal callback", "job_id", jobID, "err", err)
		return
	}
	req, err := http.NewRequest(http.MethodPost, s.cfg.CallbackURL, bytes.NewReader(raw))
	if err != nil {
		slog.Error("build callback", "job_id", jobID, "err", err)
		return
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Signature", sign(s.cfg.Secret, raw))
	resp, err := s.client.Do(req)
	if err != nil {
		slog.Error("callback failed", "job_id", jobID, "err", err)
		return
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 1<<16))
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		slog.Error("callback rejected", "job_id", jobID, "status", resp.StatusCode)
		return
	}
	slog.Info("callback sent", "job_id", jobID, "status", body.Status)
}

func main() {
	if err := run(); err != nil {
		slog.Error("vendor-sim stopped", "err", err)
		os.Exit(1)
	}
}

func run() error {
	secret := os.Getenv("VENDOR_WEBHOOK_SECRET")
	if secret == "" {
		return errors.New("VENDOR_WEBHOOK_SECRET is required")
	}
	delaySec := 20.0
	if v := os.Getenv("VENDOR_DELAY_SEC"); v != "" {
		f, err := strconv.ParseFloat(v, 64)
		if err != nil || f < 0 {
			return errors.New("invalid VENDOR_DELAY_SEC: " + v)
		}
		delaySec = f
	}
	s := newSim(config{
		Delay:       time.Duration(delaySec * float64(time.Second)),
		CallbackURL: getenv("VENDOR_CALLBACK_URL", "http://api:8080/webhooks/vendor"),
		Secret:      secret,
		PublicURL:   getenv("VENDOR_PUBLIC_URL", "http://vendor-sim:9000"),
		FaultsURL:   getenv("VENDOR_FAULTS_URL", "http://api:8080/debug/faults/consume"),
	})
	const addr = ":9000"
	slog.Info("vendor-sim listening", "addr", addr, "delay", s.cfg.Delay)
	srv := &http.Server{Addr: addr, Handler: s.handler(), ReadHeaderTimeout: 10 * time.Second}
	return srv.ListenAndServe()
}

func getenv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
