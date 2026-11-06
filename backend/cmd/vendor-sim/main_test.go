package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

type received struct {
	body []byte
	sig  string
}

// newTestSim 回傳 sim 與一個收回呼的假 api；回呼寫入 got。
func newTestSim(t *testing.T, delay time.Duration) (*sim, *httptest.Server, *[]received, *sync.Mutex) {
	t.Helper()
	var (
		mu  sync.Mutex
		got []received
	)
	api := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		mu.Lock()
		got = append(got, received{b, r.Header.Get("X-Signature")})
		mu.Unlock()
		w.WriteHeader(http.StatusOK)
	}))
	t.Cleanup(api.Close)
	s := newSim(config{Delay: delay, CallbackURL: api.URL + "/webhooks/vendor", Secret: "test-secret", PublicURL: "http://vendor-sim:9000"})
	return s, api, &got, &mu
}

func post(h http.Handler, body string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodPost, "/jobs", strings.NewReader(body))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func TestCallbackAfterDelayWithValidSignature(t *testing.T) {
	s, _, got, mu := newTestSim(t, 150*time.Millisecond)
	start := time.Now()
	if rec := post(s.handler(), `{"job_id":"job-1"}`); rec.Code != http.StatusAccepted {
		t.Fatalf("code = %d", rec.Code)
	}
	mu.Lock()
	early := len(*got)
	mu.Unlock()
	if early != 0 {
		t.Fatal("callback sent before the delay")
	}
	s.wg.Wait()
	if elapsed := time.Since(start); elapsed < 150*time.Millisecond {
		t.Fatalf("callback after %v, want >= delay", elapsed)
	}
	mu.Lock()
	defer mu.Unlock()
	if len(*got) != 1 {
		t.Fatalf("callbacks = %d", len(*got))
	}
	r := (*got)[0]
	// 獨立計算簽章。
	mac := hmac.New(sha256.New, []byte("test-secret"))
	mac.Write(r.body)
	if want := hex.EncodeToString(mac.Sum(nil)); r.sig != want {
		t.Fatalf("sig = %s, want %s", r.sig, want)
	}
	var m map[string]any
	if err := json.Unmarshal(r.body, &m); err != nil {
		t.Fatal(err)
	}
	if m["job_id"] != "job-1" || m["status"] != "SUCCEEDED" || m["video_url"] != "http://vendor-sim:9000/videos/job-1.mp4" || m["reason"] != nil {
		t.Fatalf("body = %s", r.body)
	}
	if id, _ := m["event_id"].(string); len(id) < 16 {
		t.Fatalf("event_id = %q", id)
	}
}

func TestDuplicateJobOpensOneJob(t *testing.T) {
	s, _, got, mu := newTestSim(t, 50*time.Millisecond)
	for i := 0; i < 3; i++ {
		if rec := post(s.handler(), `{"job_id":"job-1"}`); rec.Code != http.StatusAccepted {
			t.Fatalf("post %d code = %d", i, rec.Code)
		}
	}
	post(s.handler(), `{"job_id":"job-2"}`)
	s.wg.Wait()
	time.Sleep(100 * time.Millisecond) // 若有第二個工作，會在這段時間內補送
	mu.Lock()
	defer mu.Unlock()
	if len(*got) != 2 {
		t.Fatalf("callbacks = %d, want 2 (one per job_id)", len(*got))
	}
}

func TestMissingJobIDIs400(t *testing.T) {
	s, _, got, mu := newTestSim(t, 10*time.Millisecond)
	for _, body := range []string{`{}`, `{"job_id":""}`, `not json`, ``} {
		if rec := post(s.handler(), body); rec.Code != http.StatusBadRequest {
			t.Errorf("body %q: code = %d", body, rec.Code)
		}
	}
	s.wg.Wait()
	mu.Lock()
	defer mu.Unlock()
	if len(*got) != 0 {
		t.Fatalf("callbacks = %d", len(*got))
	}
}

func TestCallbackFailureIsNotRetried(t *testing.T) {
	var mu sync.Mutex
	calls := 0
	api := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		mu.Lock()
		calls++
		mu.Unlock()
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer api.Close()
	s := newSim(config{Delay: 10 * time.Millisecond, CallbackURL: api.URL, Secret: "s", PublicURL: "http://x"})
	post(s.handler(), `{"job_id":"j"}`)
	s.wg.Wait()
	time.Sleep(100 * time.Millisecond)
	mu.Lock()
	defer mu.Unlock()
	if calls != 1 {
		t.Fatalf("calls = %d, want 1", calls)
	}
}
