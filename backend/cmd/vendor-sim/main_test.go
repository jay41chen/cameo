package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type received struct {
	body []byte
	sig  string
}

// fakeAPI 同時收 consume 與 webhook。consume 依序回 consumeResp 的每一筆，用完回 {"fault":null}。
type fakeAPI struct {
	srv *httptest.Server

	mu          sync.Mutex
	got         []received
	consumeResp []consumeReply
	consumeN    int
	auths       []string
}

type consumeReply struct {
	status int
	body   string
}

func newFakeAPI(t *testing.T, replies ...consumeReply) *fakeAPI {
	t.Helper()
	f := &fakeAPI{consumeResp: replies}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /debug/faults/consume", func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		reply := consumeReply{http.StatusOK, `{"fault":null}`}
		if f.consumeN < len(f.consumeResp) {
			reply = f.consumeResp[f.consumeN]
		}
		f.consumeN++
		f.auths = append(f.auths, r.Header.Get("Authorization"))
		f.mu.Unlock()
		w.WriteHeader(reply.status)
		_, _ = io.WriteString(w, reply.body)
	})
	mux.HandleFunc("POST /webhooks/vendor", func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		f.mu.Lock()
		f.got = append(f.got, received{b, r.Header.Get("X-Signature")})
		f.mu.Unlock()
		w.WriteHeader(http.StatusOK)
	})
	f.srv = httptest.NewServer(mux)
	t.Cleanup(f.srv.Close)
	return f
}

func (f *fakeAPI) sim(delay time.Duration) *sim {
	return newSim(config{Delay: delay, CallbackURL: f.srv.URL + "/webhooks/vendor", FaultsURL: f.srv.URL + "/debug/faults/consume", Secret: "test-secret", PublicURL: "http://vendor-sim:9000"})
}

func (f *fakeAPI) callbacks() []received {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]received(nil), f.got...)
}

func (f *fakeAPI) consumeCalls() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.consumeN
}

// newTestSim 回傳 sim 與一個收回呼的假 api；回呼寫入 got。consume 一律回沒有異常。
func newTestSim(t *testing.T, delay time.Duration) (*sim, *httptest.Server, *[]received, *sync.Mutex) {
	t.Helper()
	f := newFakeAPI(t)
	return f.sim(delay), f.srv, &f.got, &f.mu
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

func decode(t *testing.T, r received) map[string]any {
	t.Helper()
	mac := hmac.New(sha256.New, []byte("test-secret"))
	mac.Write(r.body)
	if want := hex.EncodeToString(mac.Sum(nil)); r.sig != want {
		t.Fatalf("bad signature for %s", r.body)
	}
	var m map[string]any
	if err := json.Unmarshal(r.body, &m); err != nil {
		t.Fatal(err)
	}
	return m
}

// 假 API 的 JSON 與 API handler 實際輸出的形狀一致：{"fault":{"name","params"}}。
func faultJSON(name, params string) consumeReply {
	return consumeReply{http.StatusOK, fmt.Sprintf(`{"fault":{"name":%q,"params":%s}}`, name, params)}
}

func runOne(t *testing.T, f *fakeAPI, jobs ...string) {
	t.Helper()
	s := f.sim(10 * time.Millisecond)
	for _, j := range jobs {
		if rec := post(s.handler(), `{"job_id":"`+j+`"}`); rec.Code != http.StatusAccepted {
			t.Fatalf("code = %d", rec.Code)
		}
	}
	s.wg.Wait()
	time.Sleep(100 * time.Millisecond)
}

func TestVendorFailedCallback(t *testing.T) {
	for _, c := range []struct {
		params string
		reason any
	}{{`{"reason":null}`, nil}, {`{"reason":"CONTENT_REJECTED"}`, "CONTENT_REJECTED"}} {
		f := newFakeAPI(t, faultJSON("vendor_failed", c.params))
		runOne(t, f, "job-1")
		got := f.callbacks()
		if len(got) != 1 {
			t.Fatalf("%s: callbacks = %d", c.params, len(got))
		}
		m := decode(t, got[0])
		if m["status"] != "FAILED" || m["job_id"] != "job-1" || m["video_url"] != "" || m["reason"] != c.reason {
			t.Fatalf("%s: body = %s", c.params, got[0].body)
		}
	}
}

func TestDuplicateCallbackEventIDs(t *testing.T) {
	f := newFakeAPI(t, faultJSON("vendor_duplicate_callback", `{"count":3,"status":"SUCCEEDED"}`))
	runOne(t, f, "job-1")
	got := f.callbacks()
	if len(got) != 3 {
		t.Fatalf("callbacks = %d, want 3", len(got))
	}
	var ids []string
	for _, r := range got {
		m := decode(t, r)
		if m["status"] != "SUCCEEDED" || m["video_url"] != "http://vendor-sim:9000/videos/job-1.mp4" || m["reason"] != nil {
			t.Fatalf("body = %s", r.body)
		}
		ids = append(ids, m["event_id"].(string))
	}
	if ids[0] != ids[1] || ids[2] == ids[0] {
		t.Fatalf("event_ids = %v, want first two equal, third different", ids)
	}
}

func TestDuplicateCallbackCountOne(t *testing.T) {
	f := newFakeAPI(t, faultJSON("vendor_duplicate_callback", `{"count":1,"status":"SUCCEEDED"}`))
	runOne(t, f, "job-1")
	if n := len(f.callbacks()); n != 1 {
		t.Fatalf("callbacks = %d, want 1", n)
	}
}

func TestDuplicateCallbackFailedStatus(t *testing.T) {
	f := newFakeAPI(t, faultJSON("vendor_duplicate_callback", `{"count":2,"status":"FAILED"}`))
	runOne(t, f, "job-1")
	got := f.callbacks()
	if len(got) != 2 {
		t.Fatalf("callbacks = %d", len(got))
	}
	for _, r := range got {
		m := decode(t, r)
		if m["status"] != "FAILED" || m["video_url"] != "" || m["reason"] != nil {
			t.Fatalf("body = %s", r.body)
		}
	}
}

func TestDuplicateCallbackContinuesAfterFailure(t *testing.T) {
	var mu sync.Mutex
	calls := 0
	api := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/consume" {
			_, _ = io.WriteString(w, `{"fault":{"name":"vendor_duplicate_callback","params":{"count":3,"status":"SUCCEEDED"}}}`)
			return
		}
		mu.Lock()
		calls++
		mu.Unlock()
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer api.Close()
	s := newSim(config{Delay: 10 * time.Millisecond, CallbackURL: api.URL + "/cb", FaultsURL: api.URL + "/consume", Secret: "s", PublicURL: "http://x"})
	post(s.handler(), `{"job_id":"j"}`)
	s.wg.Wait()
	mu.Lock()
	defer mu.Unlock()
	if calls != 3 {
		t.Fatalf("calls = %d, want 3", calls)
	}
}

func TestNoCallbackFault(t *testing.T) {
	f := newFakeAPI(t, faultJSON("vendor_no_callback", `{}`))
	runOne(t, f, "job-1")
	if n := len(f.callbacks()); n != 0 {
		t.Fatalf("callbacks = %d, want 0", n)
	}
}

func TestResendJobConsumesOnce(t *testing.T) {
	f := newFakeAPI(t, faultJSON("vendor_failed", `{"reason":null}`))
	runOne(t, f, "job-1", "job-1", "job-1")
	if n := f.consumeCalls(); n != 1 {
		t.Fatalf("consume calls = %d, want 1", n)
	}
	got := f.callbacks()
	if len(got) != 1 || decode(t, got[0])["status"] != "FAILED" {
		t.Fatalf("callbacks = %d", len(got))
	}
}

func TestConsumeSendsAuthorization(t *testing.T) {
	f := newFakeAPI(t)
	runOne(t, f, "job-1")
	f.mu.Lock()
	defer f.mu.Unlock()
	if !reflect.DeepEqual(f.auths, []string{"Bearer test-token"}) {
		t.Fatalf("auths = %v", f.auths)
	}
}

func TestConsumeFailureFallsBackToSucceeded(t *testing.T) {
	for name, reply := range map[string]consumeReply{
		"500":          {http.StatusInternalServerError, `{"fault":{"name":"vendor_failed","params":{}}}`},
		"bad json":     {http.StatusOK, `not json`},
		"unknown name": faultJSON("vendor_other", `{}`),
		"non-vendor":   faultJSON("upload_expired", `{}`),
	} {
		f := newFakeAPI(t, reply)
		runOne(t, f, "job-1")
		got := f.callbacks()
		if len(got) != 1 || decode(t, got[0])["status"] != "SUCCEEDED" {
			t.Errorf("%s: callbacks = %d", name, len(got))
		}
	}
}

func TestConsumeUnreachableFallsBackToSucceeded(t *testing.T) {
	f := newFakeAPI(t)
	s := f.sim(10 * time.Millisecond)
	s.cfg.FaultsURL = "http://127.0.0.1:1/debug/faults/consume"
	post(s.handler(), `{"job_id":"job-1"}`)
	s.wg.Wait()
	got := f.callbacks()
	if len(got) != 1 || decode(t, got[0])["status"] != "SUCCEEDED" {
		t.Fatalf("callbacks = %d", len(got))
	}
}

func TestConsumeEmptyURLFallsBackToSucceeded(t *testing.T) {
	f := newFakeAPI(t)
	s := f.sim(10 * time.Millisecond)
	s.cfg.FaultsURL = ""
	post(s.handler(), `{"job_id":"job-1"}`)
	s.wg.Wait()
	if n := len(f.callbacks()); n != 1 {
		t.Fatalf("callbacks = %d", n)
	}
}

func TestTwoJobsEachConsumeOnce(t *testing.T) {
	f := newFakeAPI(t, faultJSON("vendor_failed", `{"reason":"CONTENT_REJECTED"}`), consumeReply{http.StatusOK, `{"fault":null}`})
	runOne(t, f, "job-1", "job-2")
	if n := f.consumeCalls(); n != 2 {
		t.Fatalf("consume calls = %d, want 2", n)
	}
	status := map[string]any{}
	for _, r := range f.callbacks() {
		m := decode(t, r)
		status[m["job_id"].(string)] = m["status"]
	}
	if !reflect.DeepEqual(status, map[string]any{"job-1": "FAILED", "job-2": "SUCCEEDED"}) {
		t.Fatalf("status = %v", status)
	}
}

// count 不是 3 時：前 count-1 次 event_id 相同，最後一次不同。
func TestDuplicateCallbackEventIDsByCount(t *testing.T) {
	for _, count := range []int{2, 4} {
		f := newFakeAPI(t, faultJSON("vendor_duplicate_callback", fmt.Sprintf(`{"count":%d,"status":"SUCCEEDED"}`, count)))
		runOne(t, f, "job-1")
		got := f.callbacks()
		if len(got) != count {
			t.Fatalf("count=%d: callbacks = %d", count, len(got))
		}
		var ids []string
		for _, r := range got {
			ids = append(ids, decode(t, r)["event_id"].(string))
		}
		for i := 1; i < count-1; i++ {
			if ids[i] != ids[0] {
				t.Fatalf("count=%d: event_ids = %v, want first %d equal", count, ids, count-1)
			}
		}
		if ids[count-1] == ids[0] {
			t.Fatalf("count=%d: event_ids = %v, want last different", count, ids)
		}
	}
}

// consume 卡住超過 consumeTimeout：postJob 仍在有限時間內回 202，且照常送一次 SUCCEEDED。
func TestConsumeTimeoutStillAcceptsAndSucceeds(t *testing.T) {
	block := make(chan struct{})
	var cbs atomic.Int64
	api := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/consume" {
			select {
			case <-block:
			case <-r.Context().Done():
			}
			return
		}
		b, _ := io.ReadAll(r.Body)
		if strings.Contains(string(b), `"status":"SUCCEEDED"`) {
			cbs.Add(1)
		}
	}))
	t.Cleanup(api.Close)
	t.Cleanup(func() { close(block) }) // 先於 api.Close 執行，放走卡住的 handler
	s := newSim(config{Delay: time.Millisecond, CallbackURL: api.URL + "/cb", FaultsURL: api.URL + "/consume", Secret: "s", PublicURL: "http://x"})
	start := time.Now()
	rec := post(s.handler(), `{"job_id":"j"}`)
	elapsed := time.Since(start)
	if rec.Code != http.StatusAccepted {
		t.Fatalf("code = %d", rec.Code)
	}
	if elapsed < consumeTimeout || elapsed > 3*time.Second {
		t.Fatalf("postJob took %v, want about %v (< 3s)", elapsed, consumeTimeout)
	}
	s.wg.Wait()
	if n := cbs.Load(); n != 1 {
		t.Fatalf("SUCCEEDED callbacks = %d, want 1", n)
	}
}

// 同一 job_id 並行重送：consume 與回呼都只發生一次，全部回 202。
func TestConcurrentResendConsumesOnce(t *testing.T) {
	f := newFakeAPI(t, faultJSON("vendor_failed", `{"reason":null}`))
	s := f.sim(5 * time.Millisecond)
	const n = 20
	codes := make([]int, n)
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			codes[i] = post(s.handler(), `{"job_id":"j"}`).Code
		}()
	}
	wg.Wait()
	s.wg.Wait()
	for i, c := range codes {
		if c != http.StatusAccepted {
			t.Fatalf("post %d code = %d", i, c)
		}
	}
	if c := f.consumeCalls(); c != 1 {
		t.Fatalf("consume calls = %d, want 1", c)
	}
	if c := len(f.callbacks()); c != 1 {
		t.Fatalf("callbacks = %d, want 1", c)
	}
}
