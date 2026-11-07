package integration_test

import (
	"bytes"
	"context"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"cameo/backend/assets"
	apihttp "cameo/backend/internal/adapter/http"
	"cameo/backend/internal/adapter/postgres"
	"cameo/backend/internal/domain"
	"cameo/backend/internal/usecase"
)

const shareURLPrefix = "http://share.test/s/"

func (f *vfx) sharePut(id string, auth bool) resp {
	return send(f.h, http.MethodPut, "/v1/cameos/"+id+"/share", nil, auth, nil)
}

func (f *vfx) shareDelete(id string, auth bool) resp {
	return send(f.h, http.MethodDelete, "/v1/cameos/"+id+"/share", nil, auth, nil)
}

// shareURL 對 SUCCEEDED 訂單 PUT share，回傳回應裡的完整網址。
func (f *vfx) shareURL(t *testing.T, id string) string {
	t.Helper()
	r := f.sharePut(id, true)
	if r.Code != 200 {
		t.Fatalf("PUT share = %d %s", r.Code, r.Body.String())
	}
	share := r.body["share"].(map[string]any)
	if share["active"] != true {
		t.Fatalf("share = %v", share)
	}
	return share["url"].(string)
}

func tokenOf(t *testing.T, url string) string {
	t.Helper()
	if !strings.HasPrefix(url, shareURLPrefix) || len(url) == len(shareURLPrefix) {
		t.Fatalf("url = %q, want prefix %q", url, shareURLPrefix)
	}
	return strings.TrimPrefix(url, shareURLPrefix)
}

func (f *vfx) sharedGet(method, path string, hdr map[string]string) resp {
	return send(f.h, method, path, nil, false, hdr)
}

func wantShareHeaders(t *testing.T, r resp) {
	t.Helper()
	h := r.Header()
	if h.Get("Cache-Control") != "no-store" || h.Get("X-Robots-Tag") != "noindex" || h.Get("Referrer-Policy") != "no-referrer" {
		t.Fatalf("%d share headers = %v", r.Code, h)
	}
}

func wantHTML(t *testing.T, r resp, status int) {
	t.Helper()
	if r.Code != status || !strings.HasPrefix(r.Header().Get("Content-Type"), "text/html") {
		t.Fatalf("got %d %q, want %d text/html; body=%q", r.Code, r.Header().Get("Content-Type"), status, r.Body.String())
	}
	wantShareHeaders(t, r)
}

func TestSharePutSucceeded(t *testing.T) {
	f := newVideoVfx(t)
	id := f.succeeded(t, "k")
	r := f.sharePut(id, true)
	if r.Code != 200 || !reflect.DeepEqual(keys(r.body), []string{"share"}) {
		t.Fatalf("PUT = %d %v", r.Code, r.body)
	}
	url := r.body["share"].(map[string]any)["url"].(string)
	tokenOf(t, url) // base 的尾端 / 已去掉，不會出現 //s/
	if strings.Contains(r.Body.String(), "share_token") || strings.Contains(r.Body.String(), "video_url") {
		t.Fatalf("leaks internal fields: %s", r.Body.String())
	}
	// 重複 PUT 回同一個網址。
	if again := f.shareURL(t, id); again != url {
		t.Fatalf("second PUT url = %q, want %q", again, url)
	}
}

func TestSharePutNotShareableStates(t *testing.T) {
	f := newVideoVfx(t)
	for state, id := range f.nonSucceededCameos(t) {
		r := f.sharePut(id, true)
		wantError(t, r, 409, "NOT_SHAREABLE")
		if r.body["message"] != "這筆客串目前無法分享" || r.body["retryable"] != false {
			t.Fatalf("%s body = %v", state, r.body)
		}
		var n int
		if err := f.pool.QueryRow(context.Background(), `SELECT count(*) FROM cameos WHERE id = $1 AND share_token IS NOT NULL`, id).Scan(&n); err != nil || n != 0 {
			t.Fatalf("%s got a token: %d %v", state, n, err)
		}
		// DELETE 冪等：非 SUCCEEDED 也回 204。
		if d := f.shareDelete(id, true); d.Code != 204 || d.Body.Len() != 0 {
			t.Fatalf("%s DELETE = %d %q", state, d.Code, d.Body.String())
		}
	}
}

func TestShareNotFoundAndAuth(t *testing.T) {
	f := newVideoVfx(t)
	id := f.succeeded(t, "k")
	for _, bad := range []string{"no-such-id", "a%00b"} {
		wantError(t, f.sharePut(bad, true), 404, "CAMEO_NOT_FOUND")
		wantError(t, f.shareDelete(bad, true), 404, "CAMEO_NOT_FOUND")
	}
	wantError(t, f.sharePut(id, false), 401, "UNAUTHORIZED")
	wantError(t, f.shareDelete(id, false), 401, "UNAUTHORIZED")
	r := send(f.h, http.MethodGet, "/v1/cameos/"+id+"/share", nil, true, nil)
	wantError(t, r, 405, "METHOD_NOT_ALLOWED")
	if r.Header().Get("Allow") != "DELETE, PUT" {
		t.Fatalf("Allow = %q", r.Header().Get("Allow"))
	}
	// 沒有分享成功的 token，因此 401 沒有留下任何 token。
	var n int
	if err := f.pool.QueryRow(context.Background(), `SELECT count(*) FROM cameos WHERE share_token IS NOT NULL`).Scan(&n); err != nil || n != 0 {
		t.Fatalf("tokens = %d %v", n, err)
	}
}

func TestSharePutConcurrentSingleToken(t *testing.T) {
	f := newVideoVfx(t)
	id := f.succeeded(t, "k")
	const n = 24
	urls := make([]string, n)
	var wg sync.WaitGroup
	start := make(chan struct{}) // 讓所有請求同時出發，提高競爭機率
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			r := f.sharePut(id, true)
			if r.Code == 200 {
				urls[i] = r.body["share"].(map[string]any)["url"].(string)
			} else {
				urls[i] = "status " + strconv.Itoa(r.Code) + " " + r.Body.String()
			}
		}()
	}
	close(start)
	wg.Wait()
	for _, u := range urls {
		if u != urls[0] {
			t.Fatalf("different responses: %v", urls)
		}
	}
	tokenOf(t, urls[0])
	rows, err := f.pool.Query(context.Background(), `SELECT share_token FROM cameos WHERE share_token IS NOT NULL`)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var tokens []string
	for rows.Next() {
		var tok string
		if err := rows.Scan(&tok); err != nil {
			t.Fatal(err)
		}
		tokens = append(tokens, tok)
	}
	if len(tokens) != 1 || shareURLPrefix+tokens[0] != urls[0] {
		t.Fatalf("db tokens = %v, url = %s", tokens, urls[0])
	}
}

// pausedAfterLockTx 在 CameoForUpdate 回來後停一下，把「讀到 token 為空」到「寫入」之間的空窗拉長，
// 讓沒有鎖列的實作一定會被併發請求搶進空窗。
type pausedAfterLockTx struct{ usecase.Tx }

func (t pausedAfterLockTx) CameoForUpdate(ctx context.Context, id string) (domain.Cameo, bool, error) {
	c, found, err := t.Tx.CameoForUpdate(ctx, id)
	time.Sleep(100 * time.Millisecond)
	return c, found, err
}

type pausedRunner struct{ usecase.TxRunner }

func (r pausedRunner) Run(ctx context.Context, fn func(usecase.Tx) error) error {
	return r.TxRunner.Run(ctx, func(tx usecase.Tx) error { return fn(pausedAfterLockTx{tx}) })
}

// 即使讀取後停頓，鎖列仍讓同一訂單的併發 PUT 只產生一個 token。
func TestSharePutConcurrentWithPauseAfterLock(t *testing.T) {
	f := newVideoVfx(t)
	id := f.succeeded(t, "k")
	svc := usecase.NewService(postgres.NewQueries(f.pool), pausedRunner{postgres.NewTxRunner(f.pool)}, f.clock,
		usecase.Config{DailyLimit: 1000, UploadTTL: ttl}, newFiles(t))
	f.h = apihttp.Handler(svc, apihttp.WithSampleVideo(assets.SampleVideo), apihttp.WithPublicBaseURL(testBaseURL))
	urls := make([]string, 4)
	var wg sync.WaitGroup
	for i := range urls {
		wg.Add(1)
		go func() {
			defer wg.Done()
			urls[i] = f.sharePut(id, true).body["share"].(map[string]any)["url"].(string)
		}()
	}
	wg.Wait()
	for _, u := range urls {
		if u != urls[0] {
			t.Fatalf("different responses: %v", urls)
		}
	}
	var tok string
	if err := f.pool.QueryRow(context.Background(), `SELECT share_token FROM cameos WHERE id = $1`, id).Scan(&tok); err != nil || shareURLPrefix+tok != urls[0] {
		t.Fatalf("db token = %q %v, url = %s", tok, err, urls[0])
	}
}

func TestShareDeleteInvalidatesLinkAndReshareIssuesNewToken(t *testing.T) {
	f := newVideoVfx(t)
	id := f.succeeded(t, "k")
	oldURL := f.shareURL(t, id)
	oldTok := tokenOf(t, oldURL)
	if r := f.sharedGet(http.MethodGet, "/s/"+oldTok, nil); r.Code != 200 {
		t.Fatalf("page before delete = %d", r.Code)
	}

	if d := f.shareDelete(id, true); d.Code != 204 || d.Body.Len() != 0 {
		t.Fatalf("DELETE = %d %q", d.Code, d.Body.String())
	}
	if d := f.shareDelete(id, true); d.Code != 204 { // 重複 DELETE
		t.Fatalf("second DELETE = %d", d.Code)
	}
	wantHTML(t, f.sharedGet(http.MethodGet, "/s/"+oldTok, nil), 404)
	wantError(t, f.sharedGet(http.MethodGet, "/s/"+oldTok+"/video", nil), 404, "NOT_FOUND")

	newURL := f.shareURL(t, id)
	newTok := tokenOf(t, newURL)
	if newTok == oldTok {
		t.Fatalf("reshare reused token %q", oldTok)
	}
	// 舊連結不會復活；新連結可用。
	wantHTML(t, f.sharedGet(http.MethodGet, "/s/"+oldTok, nil), 404)
	wantError(t, f.sharedGet(http.MethodGet, "/s/"+oldTok+"/video", nil), 404, "NOT_FOUND")
	wantHTML(t, f.sharedGet(http.MethodGet, "/s/"+newTok, nil), 200)
	if r := f.sharedGet(http.MethodGet, "/s/"+newTok+"/video", nil); r.Code != 200 {
		t.Fatalf("new video = %d", r.Code)
	}
}

func TestSharePage(t *testing.T) {
	f := newVideoVfx(t)
	id := f.succeeded(t, "k")
	tok := tokenOf(t, f.shareURL(t, id))

	r := f.sharedGet(http.MethodGet, "/s/"+tok, nil) // 免 Authorization
	wantHTML(t, r, 200)
	if r.Header().Get("Content-Type") != "text/html; charset=utf-8" {
		t.Fatalf("Content-Type = %q", r.Header().Get("Content-Type"))
	}
	body := r.Body.String()
	if !strings.Contains(body, "<video controls playsinline src=\"/s/"+tok+"/video\"") {
		t.Fatalf("body = %s", body)
	}
	for _, leak := range []string{"雨夜重逢", id, "c1"} {
		if strings.Contains(body, leak) {
			t.Fatalf("page leaks %q: %s", leak, body)
		}
	}

	wantHTML(t, f.sharedGet(http.MethodGet, "/s/no-such-token", nil), 404)
	wantHTML(t, f.sharedGet(http.MethodGet, "/s/a%00b", nil), 404)
}

// 分享頁只在 SUCCEEDED 有效；token 對應的訂單不是 SUCCEEDED 一律 404（資料庫層強制）。
func TestShareTokenOfNonSucceededIsInvalid(t *testing.T) {
	f := newVideoVfx(t)
	id := f.generating(t, "k")
	if _, err := f.pool.Exec(context.Background(), `UPDATE cameos SET share_token = 'forced' WHERE id = $1`, id); err != nil {
		t.Fatal(err)
	}
	wantHTML(t, f.sharedGet(http.MethodGet, "/s/forced", nil), 404)
	wantError(t, f.sharedGet(http.MethodGet, "/s/forced/video", nil), 404, "NOT_FOUND")
}

func TestSharedVideo(t *testing.T) {
	f := newVideoVfx(t)
	id := f.succeeded(t, "k")
	tok := tokenOf(t, f.shareURL(t, id))
	path := "/s/" + tok + "/video"

	r := f.sharedGet(http.MethodGet, path, nil) // 免 Authorization
	if r.Code != 200 || r.Header().Get("Content-Type") != "video/mp4" || r.Header().Get("X-Content-Type-Options") != "nosniff" ||
		r.Header().Get("Accept-Ranges") != "bytes" || !bytes.Equal(r.Body.Bytes(), assets.SampleVideo) {
		t.Fatalf("get = %d %v len=%d", r.Code, r.Header(), r.Body.Len())
	}
	wantShareHeaders(t, r)

	r = f.sharedGet(http.MethodGet, path, map[string]string{"Range": "bytes=0-99"})
	if r.Code != 206 || r.Header().Get("Content-Range") != "bytes 0-99/265429" || !bytes.Equal(r.Body.Bytes(), assets.SampleVideo[:100]) {
		t.Fatalf("range = %d %v", r.Code, r.Header())
	}
	wantShareHeaders(t, r)

	r = f.sharedGet(http.MethodHead, path, nil)
	if r.Code != 200 || r.Header().Get("Content-Length") != strconv.Itoa(sampleVideoSize) || r.Body.Len() != 0 {
		t.Fatalf("head = %d %v", r.Code, r.Header())
	}
	wantShareHeaders(t, r)
	if h := f.sharedGet(http.MethodHead, "/s/"+tok, nil); h.Code != 200 || !strings.HasPrefix(h.Header().Get("Content-Type"), "text/html") { // httptest 不會像真實 server 丟掉 HEAD body
		t.Fatalf("page HEAD = %d", h.Code)
	}

	r = f.sharedGet(http.MethodGet, "/s/no-such-token/video", nil)
	wantError(t, r, 404, "NOT_FOUND")
	if r.body["message"] != "找不到這個分享" || r.body["retryable"] != false {
		t.Fatalf("404 body = %v", r.body)
	}
	wantShareHeaders(t, r)
	wantError(t, f.sharedGet(http.MethodGet, "/s/a%00b/video", nil), 404, "NOT_FOUND")
}

func TestShareRoutingAndMethods(t *testing.T) {
	f := newVideoVfx(t)
	tok := tokenOf(t, f.shareURL(t, f.succeeded(t, "k")))
	for _, p := range []string{"/s/" + tok, "/s/" + tok + "/video"} {
		r := send(f.h, http.MethodPost, p, nil, true, nil)
		wantError(t, r, 405, "METHOD_NOT_ALLOWED")
		if r.Header().Get("Allow") != "GET, HEAD" {
			t.Fatalf("%s Allow = %q", p, r.Header().Get("Allow"))
		}
	}
}

// 查詢出錯（已取消的 context）：頁面回 HTML 500、影片回 JSON 500，body 都不含錯誤內容。
func TestShareInternalError(t *testing.T) {
	f := newVideoVfx(t)
	tok := tokenOf(t, f.shareURL(t, f.succeeded(t, "k")))
	var logs bytes.Buffer
	old := slog.Default()
	slog.SetDefault(slog.New(slog.NewTextHandler(&logs, nil)))
	t.Cleanup(func() { slog.SetDefault(old) })
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	serve := func(path string) *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		f.h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil).WithContext(ctx))
		return rec
	}

	page := serve("/s/" + tok)
	wantHTML(t, resp{page, nil}, 500)
	if strings.Contains(page.Body.String(), "context") || strings.Contains(page.Body.String(), "canceled") || strings.Contains(page.Body.String(), "<video") {
		t.Fatalf("500 page leaks: %q", page.Body.String())
	}

	vid := serve("/s/" + tok + "/video")
	if vid.Code != 500 || vid.Header().Get("Content-Type") == "video/mp4" || bytes.Equal(vid.Body.Bytes(), assets.SampleVideo) ||
		!strings.Contains(vid.Body.String(), `"code":"INTERNAL"`) || strings.Contains(vid.Body.String(), "canceled") {
		t.Fatalf("video 500 = %d %q", vid.Code, vid.Body.String())
	}
	wantShareHeaders(t, resp{vid, nil})

	out := logs.String()
	if strings.Count(out, "request failed") != 2 || strings.Contains(out, tok) || strings.Contains(out, "path=") ||
		!strings.Contains(out, "GET /s/{token}/video") || !strings.Contains(out, "route=\"GET /s/{token}\"") {
		t.Fatalf("share error log = %q", out)
	}
}

func TestShareDeleteOneDoesNotAffectAnother(t *testing.T) {
	f := newVideoVfx(t)
	a := f.succeeded(t, "ka")
	b := f.succeeded(t, "kb")
	aTok := tokenOf(t, f.shareURL(t, a))
	bURL := f.shareURL(t, b)
	bTok := tokenOf(t, bURL)

	if d := f.shareDelete(a, true); d.Code != 204 {
		t.Fatalf("DELETE A = %d", d.Code)
	}
	wantHTML(t, f.sharedGet(http.MethodGet, "/s/"+aTok, nil), 404)
	wantHTML(t, f.sharedGet(http.MethodGet, "/s/"+bTok, nil), 200)
	r := send(f.h, http.MethodGet, "/v1/cameos/"+b, nil, true, nil)
	cam, _ := r.body["cameo"].(map[string]any)
	share, _ := cam["share"].(map[string]any)
	if r.Code != 200 || share["active"] != true || share["url"] != bURL {
		t.Fatalf("B after DELETE A = %d %v, want url %q", r.Code, r.body, bURL)
	}
}

func TestShareFieldInCameoJSON(t *testing.T) {
	f := newVideoVfx(t)
	done := f.succeeded(t, "k-done")
	queued := f.queued(t, "k-queued")
	url := f.shareURL(t, done)

	check := func(wantActive bool) {
		t.Helper()
		want := map[string]any{"active": false, "url": nil}
		if wantActive {
			want = map[string]any{"active": true, "url": url}
		}
		g := call(t, f.h, http.MethodGet, "/v1/cameos/"+done, "", "")
		if !reflect.DeepEqual(cameoOf(g)["share"], want) {
			t.Fatalf("get share = %v, want %v", cameoOf(g)["share"], want)
		}
		l := call(t, f.h, http.MethodGet, "/v1/cameos", "", "")
		for _, it := range l.body["items"].([]any) {
			c := it.(map[string]any)
			w := map[string]any{"active": false, "url": nil}
			if c["id"] == done {
				w = want
			}
			if !reflect.DeepEqual(c["share"], w) {
				t.Fatalf("list share of %v = %v, want %v", c["id"], c["share"], w)
			}
		}
		for _, r := range []resp{g, l} {
			if strings.Contains(r.Body.String(), "share_token") || strings.Contains(r.Body.String(), "video_url") {
				t.Fatalf("leaks internal fields: %s", r.Body.String())
			}
		}
	}
	check(true)
	if d := f.shareDelete(done, true); d.Code != 204 {
		t.Fatalf("DELETE = %d", d.Code)
	}
	check(false)
	_ = queued
}

func TestShareRoutesAbsentWithoutSampleVideo(t *testing.T) {
	f := newVideoVfx(t)
	tok := tokenOf(t, f.shareURL(t, f.succeeded(t, "k")))
	h := apihttp.Handler(f.svc, apihttp.WithPublicBaseURL(testBaseURL)) // 未傳 WithSampleVideo
	for _, p := range []string{"/s/" + tok, "/s/" + tok + "/video"} {
		if r := send(h, http.MethodGet, p, nil, false, nil); r.Code != 404 || r.body["code"] != "NOT_FOUND" {
			t.Fatalf("GET %s = %d %q", p, r.Code, r.Body.String())
		}
	}
}

func TestMigration0006ShareToken(t *testing.T) {
	f := newVideoVfx(t)
	a, b := f.succeeded(t, "ka"), f.succeeded(t, "kb")
	ctx := context.Background()
	if _, err := f.pool.Exec(ctx, `UPDATE cameos SET share_token = 'dup' WHERE id = $1`, a); err != nil {
		t.Fatal(err)
	}
	if _, err := f.pool.Exec(ctx, `UPDATE cameos SET share_token = 'dup' WHERE id = $1`, b); err == nil {
		t.Fatal("duplicate share_token accepted")
	}
	// NULL 可重複（未分享的訂單不互相衝突）。
	if _, err := f.pool.Exec(ctx, `UPDATE cameos SET share_token = NULL`); err != nil {
		t.Fatalf("NULL tokens conflict: %v", err)
	}
}
