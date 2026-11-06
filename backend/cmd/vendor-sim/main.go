// Command vendor-sim 是模擬的影片生成廠商：收工作、等一段時間後對 api 送一次成功回呼。
// 僅供模擬：不接收照片、不提供影片檔、不注入異常。
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

type config struct {
	Delay       time.Duration
	CallbackURL string
	Secret      string
	PublicURL   string // 回呼中 video_url 的 base，例如 http://vendor-sim:9000
}

type sim struct {
	cfg    config
	client *http.Client

	mu   sync.Mutex
	seen map[string]bool
	wg   sync.WaitGroup
}

func newSim(cfg config) *sim {
	return &sim{cfg: cfg, client: &http.Client{Timeout: callbackTimeout}, seen: map[string]bool{}}
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
		s.wg.Add(1)
		time.AfterFunc(s.cfg.Delay, func() {
			defer s.wg.Done()
			s.callback(body.JobID)
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

// callback 送一次 SUCCEEDED 回呼；Header X-Signature 是對原始 body 的 HMAC-SHA256 hex。
func (s *sim) callback(jobID string) {
	raw, err := json.Marshal(callbackBody{
		EventID: randomID(), JobID: jobID, Status: "SUCCEEDED",
		VideoURL: s.cfg.PublicURL + "/videos/" + jobID + ".mp4",
	})
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
	slog.Info("callback sent", "job_id", jobID)
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
