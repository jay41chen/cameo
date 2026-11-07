package integration_test

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"

	"cameo/backend/assets"
	apihttp "cameo/backend/internal/adapter/http"
)

const (
	sampleVideoSize   = 557914
	sampleVideoSHA256 = "972000c2f9b8b3a5a537e9fb28ddca8b7f99a4cc0b25a8a57e34e02b6eb8c6f6"
)

// testBaseURL 故意帶尾端 /，驗證 WithPublicBaseURL 會去掉。
const testBaseURL = "http://share.test/"

// newVideoVfx 是 newVfx 加上範例影片路由。
func newVideoVfx(t *testing.T) *vfx {
	t.Helper()
	f := newVfx(t)
	f.h = apihttp.Handler(f.svc, apihttp.WithVendorWebhook([]byte(webhookSecret)), apihttp.WithSampleVideo(assets.SampleVideo), apihttp.WithPublicBaseURL(testBaseURL))
	return f
}

func (f *vfx) succeeded(t *testing.T, key string) string {
	t.Helper()
	id := f.generating(t, key)
	if r := f.signed(okBody("e-"+key, id)); r.Code != 200 {
		t.Fatalf("webhook = %d %s", r.Code, r.Body.String())
	}
	return id
}

func (f *vfx) video(method, id string, hdr map[string]string) resp {
	return send(f.h, method, "/v1/cameos/"+id+"/video", nil, true, hdr)
}

func TestEmbeddedSampleVideo(t *testing.T) {
	sum := sha256.Sum256(assets.SampleVideo)
	if len(assets.SampleVideo) != sampleVideoSize || hex.EncodeToString(sum[:]) != sampleVideoSHA256 {
		t.Fatalf("embedded video len=%d sha256=%x", len(assets.SampleVideo), sum)
	}
}

func TestVideoServedForSucceeded(t *testing.T) {
	f := newVideoVfx(t)
	id := f.succeeded(t, "k")

	r := f.video(http.MethodGet, id, nil)
	if r.Code != 200 || r.Header().Get("Content-Type") != "video/mp4" || r.Header().Get("Accept-Ranges") != "bytes" ||
		r.Header().Get("X-Content-Type-Options") != "nosniff" || r.Header().Get("Content-Length") != strconv.Itoa(sampleVideoSize) {
		t.Fatalf("get = %d %v", r.Code, r.Header())
	}
	if r.Header().Get("ETag") != "" || r.Header().Get("Last-Modified") != "" {
		t.Fatalf("unexpected validators: %v", r.Header())
	}
	if !bytes.Equal(r.Body.Bytes(), assets.SampleVideo) {
		t.Fatalf("body differs: %d bytes", r.Body.Len())
	}
}

func TestVideoRange(t *testing.T) {
	f := newVideoVfx(t)
	id := f.succeeded(t, "k")

	r := f.video(http.MethodGet, id, map[string]string{"Range": "bytes=0-99"})
	if r.Code != 206 || r.Header().Get("Content-Range") != "bytes 0-99/557914" || !bytes.Equal(r.Body.Bytes(), assets.SampleVideo[:100]) {
		t.Fatalf("range = %d %v len=%d", r.Code, r.Header(), r.Body.Len())
	}
	r = f.video(http.MethodGet, id, map[string]string{"Range": "bytes=557900-"})
	if r.Code != 206 || !bytes.Equal(r.Body.Bytes(), assets.SampleVideo[557900:]) {
		t.Fatalf("tail range = %d %v", r.Code, r.Header())
	}
	r = f.video(http.MethodGet, id, map[string]string{"Range": "bytes=999999999-"})
	if r.Code != 416 {
		t.Fatalf("invalid range = %d, want 416", r.Code)
	}
}

func TestVideoHead(t *testing.T) {
	f := newVideoVfx(t)
	id := f.succeeded(t, "k")
	r := f.video(http.MethodHead, id, nil)
	if r.Code != 200 || r.Header().Get("Content-Length") != strconv.Itoa(sampleVideoSize) || r.Header().Get("Content-Type") != "video/mp4" || r.Body.Len() != 0 {
		t.Fatalf("head = %d %v body=%d", r.Code, r.Header(), r.Body.Len())
	}
}

func TestVideoRoutingAndAuth(t *testing.T) {
	f := newVideoVfx(t)
	id := f.succeeded(t, "k")
	path := "/v1/cameos/" + id + "/video"

	wantError(t, send(f.h, http.MethodGet, path, nil, false, nil), 401, "UNAUTHORIZED")
	r := send(f.h, http.MethodPost, path, nil, true, nil)
	wantError(t, r, 405, "METHOD_NOT_ALLOWED")
	if r.Header().Get("Allow") != "GET, HEAD" {
		t.Fatalf("Allow = %q", r.Header().Get("Allow"))
	}
	wantError(t, f.video(http.MethodGet, "no-such-id", nil), 404, "CAMEO_NOT_FOUND")
	wantError(t, f.video(http.MethodGet, "a%00b", nil), 404, "CAMEO_NOT_FOUND")
}

// nonSucceededCameos 建立每種非 SUCCEEDED 狀態各一筆訂單，key 是狀態名。
func (f *vfx) nonSucceededCameos(t *testing.T) map[string]string {
	t.Helper()
	ids := map[string]string{}
	// 先讓一筆逾期，其他訂單在時鐘推進後才建立，才不會一起逾期。
	expired := f.create(t, "k-exp")
	f.clock.Advance(2 * ttl)
	if _, err := f.svc.ExpireDue(context.Background()); err != nil {
		t.Fatal(err)
	}
	ids["EXPIRED"] = expired
	ids["AWAITING_UPLOAD"] = f.create(t, "k-await")
	ids["UPLOADED"] = f.createUploaded(t, "k-up")
	ids["GENERATING"] = f.generating(t, "k-gen")
	failed := f.generating(t, "k-fail")
	if r := f.signed(failBody("e-fail", failed, "")); r.Code != 200 {
		t.Fatalf("webhook = %d %s", r.Code, r.Body.String())
	}
	ids["FAILED"] = failed
	// generating 會派工所有到期訂單，QUEUED 要最後建立。
	ids["QUEUED"] = f.queued(t, "k-queued")
	return ids
}

func TestVideoNotReadyForOtherStates(t *testing.T) {
	f := newVideoVfx(t)
	ids := f.nonSucceededCameos(t)

	for want, id := range ids {
		if st, _ := f.state(t, id); st != want {
			t.Fatalf("setup: %s is %s", want, st)
		}
		for _, m := range []string{http.MethodGet, http.MethodHead} {
			r := f.video(m, id, nil)
			if m == http.MethodHead {
				if r.Code != 404 {
					t.Fatalf("%s HEAD = %d", want, r.Code)
				}
				continue
			}
			wantError(t, r, 404, "VIDEO_NOT_READY")
			if r.body["message"] != "影片尚未完成" || r.body["retryable"] != false {
				t.Fatalf("%s body = %v", want, r.body)
			}
		}
	}
}

func TestVideoAvailableInCameoJSON(t *testing.T) {
	f := newVideoVfx(t)
	done := f.succeeded(t, "k-done")
	queued := f.queued(t, "k-queued")

	for id, want := range map[string]bool{done: true, queued: false} {
		g := call(t, f.h, http.MethodGet, "/v1/cameos/"+id, "", "")
		if cameoOf(g)["video_available"] != want || strings.Contains(g.Body.String(), "video_url") {
			t.Fatalf("get %s = %s", id, g.Body.String())
		}
	}
	l := call(t, f.h, http.MethodGet, "/v1/cameos", "", "")
	if strings.Contains(l.Body.String(), "video_url") {
		t.Fatalf("list leaks video_url: %s", l.Body.String())
	}
	got := map[string]bool{}
	for _, it := range l.body["items"].([]any) {
		c := it.(map[string]any)
		got[c["id"].(string)] = c["video_available"].(bool)
	}
	if len(got) != 2 || got[done] != true || got[queued] != false {
		t.Fatalf("list video_available = %v", got)
	}
}

func TestVideoRouteAbsentWithoutSampleVideo(t *testing.T) {
	f := newVfx(t) // handler 未傳 WithSampleVideo
	id := f.succeeded(t, "k")
	for _, v := range [][]byte{nil, {}} {
		h := apihttp.Handler(f.svc, apihttp.WithSampleVideo(v))
		for _, m := range []string{http.MethodGet, http.MethodHead, http.MethodPost} {
			if r := send(h, m, "/v1/cameos/"+id+"/video", nil, true, nil); r.Code != 404 {
				t.Fatalf("%s without video = %d", m, r.Code)
			}
		}
	}
	if r := send(f.h, http.MethodGet, "/v1/cameos/"+id+"/video", nil, true, nil); r.Code != 404 || r.body["code"] != "NOT_FOUND" {
		t.Fatalf("got %d %v", r.Code, r.body)
	}
}

// 資料庫查詢出錯（這裡用已取消的 context）時回 500 INTERNAL，不能送出影片。
func TestVideoInternalErrorDoesNotServeVideo(t *testing.T) {
	f := newVideoVfx(t)
	id := f.succeeded(t, "k")

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	req := httptest.NewRequest(http.MethodGet, "/v1/cameos/"+id+"/video", nil).WithContext(ctx)
	req.Header.Set("Authorization", "Bearer test-token")
	rec := httptest.NewRecorder()
	f.h.ServeHTTP(rec, req)

	if rec.Code != 500 {
		t.Fatalf("status = %d, want 500; body len %d", rec.Code, rec.Body.Len())
	}
	var m map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &m); err != nil {
		t.Fatalf("not JSON: %q", rec.Body.String())
	}
	if m["code"] != "INTERNAL" || m["retryable"] != true {
		t.Fatalf("body = %v", m)
	}
	if rec.Header().Get("Content-Type") == "video/mp4" || bytes.Equal(rec.Body.Bytes(), assets.SampleVideo) {
		t.Fatalf("video served on error: %v", rec.Header())
	}
}
