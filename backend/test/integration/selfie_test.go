package integration_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"sync"
	"testing"
	"time"

	"cameo/backend/internal/adapter/filestore"
	apihttp "cameo/backend/internal/adapter/http"
	"cameo/backend/internal/adapter/postgres"
	"cameo/backend/internal/domain"
	"cameo/backend/internal/usecase"
	"cameo/backend/test/internal/testdb"

	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

// fx 是上傳與送出測試共用的環境：真實 Postgres、真實檔案目錄、HTTP handler。
type fx struct {
	pool  *pgxpool.Pool
	svc   *usecase.Service
	h     http.Handler
	clock *testdb.Clock
	dir   string
}

func newFx(t *testing.T, maxBytes int64) *fx {
	t.Helper()
	pool := newDB(t)
	dir := t.TempDir()
	files, err := filestore.New(dir)
	if err != nil {
		t.Fatal(err)
	}
	clock := testdb.NewClock(t0)
	svc := newSvc(pool, clock, usecase.Config{DailyLimit: 1000, UploadTTL: ttl, MaxSelfieBytes: maxBytes}, files)
	return &fx{pool: pool, svc: svc, h: apihttp.Handler(svc), clock: clock, dir: dir}
}

var jpegBytes = append([]byte{0xFF, 0xD8, 0xFF, 0xE0}, []byte("fake-jpeg-body")...)

func (f *fx) create(t *testing.T, key string) string {
	t.Helper()
	r, err := f.svc.CreateCameo(context.Background(), key, "c1")
	if err != nil {
		t.Fatal(err)
	}
	return r.Cameo.ID
}

func (f *fx) createUploaded(t *testing.T, key string) string {
	t.Helper()
	id := f.create(t, key)
	if _, err := f.svc.UploadSelfie(context.Background(), id, bytes.NewReader(jpegBytes)); err != nil {
		t.Fatal(err)
	}
	return id
}

func send(h http.Handler, method, path string, body []byte, auth bool, hdr map[string]string) resp {
	req := httptest.NewRequest(method, path, bytes.NewReader(body))
	if auth {
		req.Header.Set("Authorization", "Bearer test-token")
	}
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	var m map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &m)
	return resp{rec, m}
}

func (f *fx) put(id string, body []byte) resp {
	return send(f.h, http.MethodPut, "/v1/cameos/"+id+"/selfie", body, true, nil)
}

func (f *fx) submit(id string) resp {
	return send(f.h, http.MethodPost, "/v1/cameos/"+id+"/submit", nil, true, nil)
}

func (f *fx) state(t *testing.T, id string) (status, ps string) {
	t.Helper()
	if err := f.pool.QueryRow(context.Background(), `SELECT status, points_state FROM cameos WHERE id = $1`, id).Scan(&status, &ps); err != nil {
		t.Fatal(err)
	}
	return
}

func (f *fx) balance(t *testing.T) int {
	t.Helper()
	var b int
	if err := f.pool.QueryRow(context.Background(), `SELECT balance FROM points_account`).Scan(&b); err != nil {
		t.Fatal(err)
	}
	return b
}

// dirNames 回傳自拍目錄內所有檔名（含暫存檔）。
func (f *fx) dirNames(t *testing.T) []string {
	t.Helper()
	es, err := os.ReadDir(f.dir)
	if err != nil {
		t.Fatal(err)
	}
	names := []string{}
	for _, e := range es {
		names = append(names, e.Name())
	}
	return names
}

func (f *fx) selfie(t *testing.T, id string) ([]byte, bool) {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(f.dir, id+".jpg"))
	if errors.Is(err, os.ErrNotExist) {
		return nil, false
	}
	if err != nil {
		t.Fatal(err)
	}
	return b, true
}

func (f *fx) wantNoFiles(t *testing.T) {
	t.Helper()
	if names := f.dirNames(t); len(names) != 0 {
		t.Fatalf("files left: %v", names)
	}
}

func cameoOf(r resp) map[string]any { return r.body["cameo"].(map[string]any) }

func TestUploadAndSubmitFullFlowHTTP(t *testing.T) {
	f := newFx(t, 0)
	r := call(t, f.h, http.MethodPost, "/v1/cameos", "k1", `{"clip_id":"c1"}`)
	id := cameoOf(r)["id"].(string)

	up := f.put(id, jpegBytes)
	if up.Code != 200 || !reflect.DeepEqual(keys(up.body), []string{"cameo", "upload"}) || up.body["upload"] != nil {
		t.Fatalf("upload = %d %v", up.Code, up.body)
	}
	c := cameoOf(up)
	if c["status"] != "UPLOADED" || c["points_state"] != "HELD" ||
		!reflect.DeepEqual(c["display"], map[string]any{"title": "送出中", "message": "照片已上傳，尚未扣款"}) {
		t.Fatalf("uploaded cameo = %v", c)
	}
	if got, ok := f.selfie(t, id); !ok || !bytes.Equal(got, jpegBytes) {
		t.Fatalf("stored = %q %v", got, ok)
	}
	if p := points(t, f.svc); p.Available != 90 || p.Held != 10 {
		t.Fatalf("points after upload = %+v", p)
	}

	sub := f.submit(id)
	if sub.Code != 200 || !reflect.DeepEqual(keys(sub.body), []string{"cameo", "upload"}) || sub.body["upload"] != nil {
		t.Fatalf("submit = %d %v", sub.Code, sub.body)
	}
	c = cameoOf(sub)
	if c["status"] != "QUEUED" || c["points_state"] != "CHARGED" ||
		!reflect.DeepEqual(c["display"], map[string]any{"title": "排隊中", "message": "完成後會通知你"}) {
		t.Fatalf("queued cameo = %v", c)
	}
	if p := points(t, f.svc); p.Available != 90 || p.Held != 0 {
		t.Fatalf("points = %+v", p)
	}
	if b := f.balance(t); b != 90 {
		t.Fatalf("balance = %d", b)
	}
	var typ, title string
	var amount int
	var cameoID *string
	var at time.Time
	if err := f.pool.QueryRow(context.Background(), `SELECT type, amount, cameo_id, clip_title, created_at FROM ledger_entries`).
		Scan(&typ, &amount, &cameoID, &title, &at); err != nil {
		t.Fatal(err)
	}
	if typ != "CHARGE" || amount != 10 || cameoID == nil || *cameoID != id || title != "雨夜重逢" || !at.Equal(t0) {
		t.Fatalf("ledger = %s %d %v %s %v", typ, amount, cameoID, title, at)
	}
	if n := count(t, f.pool, "ledger_entries"); n != 1 {
		t.Fatalf("ledger rows = %d", n)
	}
	if st, ps := f.state(t, id); st != "QUEUED" || ps != "CHARGED" {
		t.Fatalf("state = %s/%s", st, ps)
	}
	if q := quota(t, f.svc); q.Remaining != 999 {
		t.Fatalf("quota remaining = %d", q.Remaining)
	}
	// 送出後選取的檔案仍保留，供後續生成使用。
	if _, ok := f.selfie(t, id); !ok {
		t.Fatal("selfie removed after submit")
	}
	led := call(t, f.h, http.MethodGet, "/v1/points/ledger", "", "")
	items := led.body["items"].([]any)
	if len(items) != 1 || items[0].(map[string]any)["cameo_id"] != id || items[0].(map[string]any)["type"] != "CHARGE" {
		t.Fatalf("ledger http = %v", led.body)
	}
}

func TestQuotaStillCountsAfterSubmit(t *testing.T) {
	pool := newDB(t)
	files := newFiles(t)
	svc := newSvc(pool, testdb.NewClock(t0), usecase.Config{DailyLimit: 3, UploadTTL: ttl}, files)
	ctx := context.Background()
	r, err := svc.CreateCameo(ctx, "k", "c1")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := svc.UploadSelfie(ctx, r.Cameo.ID, bytes.NewReader(jpegBytes)); err != nil {
		t.Fatal(err)
	}
	if _, err := svc.SubmitCameo(ctx, r.Cameo.ID); err != nil {
		t.Fatal(err)
	}
	if q := quota(t, svc); q.Remaining != 2 {
		t.Fatalf("quota = %+v", q)
	}
}

func TestSubmitRepeatedChargesOnce(t *testing.T) {
	f := newFx(t, 0)
	id := f.createUploaded(t, "k")
	for i := 0; i < 3; i++ {
		r := f.submit(id)
		if r.Code != 200 || cameoOf(r)["status"] != "QUEUED" {
			t.Fatalf("submit %d = %d %v", i, r.Code, r.body)
		}
	}
	if b := f.balance(t); b != 90 {
		t.Fatalf("balance = %d", b)
	}
	if n := count(t, f.pool, "ledger_entries"); n != 1 {
		t.Fatalf("ledger rows = %d", n)
	}
	// 之後的狀態也不重複扣款。
	for _, st := range []string{"GENERATING", "SUCCEEDED", "FAILED"} {
		if _, err := f.pool.Exec(context.Background(), `UPDATE cameos SET status = $1 WHERE id = $2`, st, id); err != nil {
			t.Fatal(err)
		}
		r := f.submit(id)
		if r.Code != 200 || cameoOf(r)["status"] != st {
			t.Fatalf("submit in %s = %d %v", st, r.Code, r.body)
		}
	}
	if b, n := f.balance(t), count(t, f.pool, "ledger_entries"); b != 90 || n != 1 {
		t.Fatalf("balance = %d ledger = %d", b, n)
	}
}

func TestConcurrentSubmitChargesOnce(t *testing.T) {
	f := newFx(t, 0)
	id := f.createUploaded(t, "k")
	ok, errs := runConcurrently(20, func(int) error {
		_, err := f.svc.SubmitCameo(context.Background(), id)
		return err
	})
	if ok != 20 || len(errs) != 0 {
		t.Fatalf("ok = %d errs = %v", ok, errs)
	}
	if b, n := f.balance(t), count(t, f.pool, "ledger_entries"); b != 90 || n != 1 {
		t.Fatalf("balance = %d ledger = %d", b, n)
	}
	if p := points(t, f.svc); p.Available != 90 || p.Held != 0 {
		t.Fatalf("points = %+v", p)
	}
}

// delayRunner 在讀到並鎖住訂單之後多等一下，放大「讀到狀態」到「寫入」之間的視窗，
// 讓併發測試穩定地撞上競爭。
type delayRunner struct {
	usecase.TxRunner
	d time.Duration
}

func (r delayRunner) Run(ctx context.Context, fn func(usecase.Tx) error) error {
	return r.TxRunner.Run(ctx, func(tx usecase.Tx) error { return fn(delayTx{tx, r.d}) })
}

type delayTx struct {
	usecase.Tx
	d time.Duration
}

func (t delayTx) CameoForUpdate(ctx context.Context, id string) (domain.Cameo, bool, error) {
	c, found, err := t.Tx.CameoForUpdate(ctx, id)
	time.Sleep(t.d)
	return c, found, err
}

// raceEnv 有兩個共用資料庫的 Service：actor 的時鐘在期限前（認為還沒逾期），
// sweeper 的時鐘在期限後（掃描會釋放）。
type raceEnv struct {
	fx
	actor   *usecase.Service
	sweeper *usecase.Service
}

func newRaceEnv(t *testing.T) *raceEnv {
	t.Helper()
	f := newFx(t, 0)
	files, err := filestore.New(f.dir)
	if err != nil {
		t.Fatal(err)
	}
	cfg := usecase.Config{DailyLimit: 1000, UploadTTL: ttl}
	runner := delayRunner{postgres.NewTxRunner(f.pool), 20 * time.Millisecond}
	actor := usecase.NewService(postgres.NewQueries(f.pool), runner, testdb.NewClock(t0), cfg, files)
	sweeper := usecase.NewService(postgres.NewQueries(f.pool), postgres.NewTxRunner(f.pool),
		testdb.NewClock(t0.Add(ttl+time.Second)), cfg, files)
	return &raceEnv{fx: *f, actor: actor, sweeper: sweeper}
}

func (e *raceEnv) reset(t *testing.T) {
	t.Helper()
	if _, err := e.pool.Exec(context.Background(),
		`DELETE FROM ledger_entries; DELETE FROM cameos; UPDATE points_account SET balance = 100`); err != nil {
		t.Fatal(err)
	}
	for _, n := range e.dirNames(t) {
		_ = os.Remove(filepath.Join(e.dir, n))
	}
}

func TestSubmitRacingExpirySweep(t *testing.T) {
	e := newRaceEnv(t)
	ctx := context.Background()
	var charged, released int
	for round := 0; round < 40; round++ {
		e.reset(t)
		id := e.createUploaded(t, fmt.Sprint("r", round))
		var wg sync.WaitGroup
		var subErr, sweepErr error
		var swept []string
		wg.Add(2)
		go func() {
			defer wg.Done()
			_, subErr = e.actor.SubmitCameo(ctx, id)
		}()
		go func() {
			defer wg.Done()
			time.Sleep(time.Duration(round%5) * 6 * time.Millisecond)
			swept, sweepErr = e.sweeper.ExpireDue(ctx)
		}()
		wg.Wait()
		if sweepErr != nil {
			t.Fatalf("round %d: sweep: %v", round, sweepErr)
		}
		status, ps := e.state(t, id)
		bal, ledger := e.balance(t), count(t, e.pool, "ledger_entries")
		_, hasFile := e.selfie(t, id)
		p := points(t, e.actor)
		switch {
		case status == "QUEUED" && ps == "CHARGED":
			charged++
			if subErr != nil || len(swept) != 0 || bal != 90 || ledger != 1 || !hasFile || p.Available != 90 || p.Held != 0 {
				t.Fatalf("round %d charged: subErr=%v swept=%v bal=%d ledger=%d file=%v points=%+v", round, subErr, swept, bal, ledger, hasFile, p)
			}
		case status == "EXPIRED" && ps == "RELEASED":
			released++
			if !errors.Is(subErr, domain.ErrUploadExpired) || bal != 100 || ledger != 0 || hasFile || p.Available != 100 || p.Held != 0 {
				t.Fatalf("round %d expired: subErr=%v swept=%v bal=%d ledger=%d file=%v points=%+v", round, subErr, swept, bal, ledger, hasFile, p)
			}
		default:
			t.Fatalf("round %d: unexpected terminal state %s/%s", round, status, ps)
		}
	}
	t.Logf("charged=%d released=%d", charged, released)
	if charged == 0 || released == 0 {
		t.Fatalf("race did not exercise both outcomes: charged=%d released=%d", charged, released)
	}
}

func TestUploadRacingExpirySweep(t *testing.T) {
	e := newRaceEnv(t)
	ctx := context.Background()
	for round := 0; round < 30; round++ {
		e.reset(t)
		id := e.create(t, fmt.Sprint("r", round))
		var wg sync.WaitGroup
		var upErr error
		wg.Add(2)
		go func() {
			defer wg.Done()
			_, upErr = e.actor.UploadSelfie(ctx, id, bytes.NewReader(jpegBytes))
		}()
		go func() {
			defer wg.Done()
			time.Sleep(time.Duration(round%5) * 6 * time.Millisecond)
			if _, err := e.sweeper.ExpireDue(ctx); err != nil {
				t.Errorf("sweep: %v", err)
			}
		}()
		wg.Wait()
		// 掃描的時鐘已過期限，所以不論誰先，最終都是 EXPIRED；上傳要嘛成功（之後被釋放）要嘛 410。
		status, ps := e.state(t, id)
		if status != "EXPIRED" || ps != "RELEASED" || (upErr != nil && !errors.Is(upErr, domain.ErrUploadExpired)) {
			t.Fatalf("round %d: state %s/%s upErr=%v", round, status, ps, upErr)
		}
		e.wantNoFiles(t)
		if p := points(t, e.actor); p.Available != 100 || p.Held != 0 {
			t.Fatalf("round %d points = %+v", round, p)
		}
	}
}

// 起始 UPLOADED：重新上傳與送出併發。submit 的起跑時間在 0 與 40ms 之間交替
// （delayRunner 在鎖住訂單後多持有 20ms），讓「送出先拿到鎖」與「上傳先拿到鎖」兩種順序都會出現。
//   - 送出先：訂單變 QUEUED，上傳得 ErrInvalidState（409），檔案維持舊內容。
//   - 上傳先：上傳 200，檔案是新內容，之後送出照常扣款。
//
// 不論順序都只扣一次、一筆 CHARGE、最終 QUEUED/CHARGED、沒有暫存檔。
func TestUploadRacingSubmit(t *testing.T) {
	e := newRaceEnv(t)
	ctx := context.Background()
	var submitFirst, uploadFirst int
	for round := 0; round < 30; round++ {
		e.reset(t)
		id := e.createUploaded(t, fmt.Sprint("r", round))
		fresh := append([]byte{0xFF, 0xD8, 0xFF}, []byte(fmt.Sprint("new-content-", round))...)
		var wg sync.WaitGroup
		var upErr, subErr error
		wg.Add(2)
		go func() {
			defer wg.Done()
			_, upErr = e.actor.UploadSelfie(ctx, id, bytes.NewReader(fresh))
		}()
		go func() {
			defer wg.Done()
			if round%2 == 1 {
				time.Sleep(40 * time.Millisecond)
			}
			_, subErr = e.actor.SubmitCameo(ctx, id)
		}()
		wg.Wait()

		status, ps := e.state(t, id)
		bal, ledger := e.balance(t), count(t, e.pool, "ledger_entries")
		if subErr != nil || status != "QUEUED" || ps != "CHARGED" || bal != 90 || ledger != 1 {
			t.Fatalf("round %d: subErr=%v state=%s/%s bal=%d ledger=%d", round, subErr, status, ps, bal, ledger)
		}
		var charges int
		if err := e.pool.QueryRow(ctx, `SELECT count(*) FROM ledger_entries WHERE type = 'CHARGE' AND cameo_id = $1`, id).Scan(&charges); err != nil || charges != 1 {
			t.Fatalf("round %d: CHARGE rows = %d, %v", round, charges, err)
		}
		got, ok := e.selfie(t, id)
		switch {
		case upErr == nil:
			uploadFirst++
			if !ok || !bytes.Equal(got, fresh) {
				t.Fatalf("round %d: upload 200 but file = %q", round, got)
			}
		case errors.Is(upErr, domain.ErrInvalidState):
			submitFirst++
			if !ok || !bytes.Equal(got, jpegBytes) {
				t.Fatalf("round %d: upload 409 but file = %q", round, got)
			}
		default:
			t.Fatalf("round %d: upErr = %v", round, upErr)
		}
		if names := e.dirNames(t); len(names) != 1 || names[0] != id+".jpg" {
			t.Fatalf("round %d: dir = %v", round, names)
		}
	}
	t.Logf("uploadFirst=%d submitFirst=%d", uploadFirst, submitFirst)
	if uploadFirst == 0 || submitFirst == 0 {
		t.Fatalf("race did not exercise both orders: uploadFirst=%d submitFirst=%d", uploadFirst, submitFirst)
	}
}

// 多個上傳同時打同一筆：全部 200，只留一個正式檔（內容是其中之一），沒有暫存檔。
func TestConcurrentUploadSameCameo(t *testing.T) {
	f := newFx(t, 0)
	const writers = 8
	for round := 0; round < 5; round++ {
		id := f.create(t, fmt.Sprint("k", round))
		contents := make([][]byte, writers)
		for i := range contents {
			contents[i] = append([]byte{0xFF, 0xD8, 0xFF}, bytes.Repeat([]byte{byte('a' + i)}, 1000+i*37)...)
		}
		ok, errs := runConcurrently(writers, func(i int) error {
			if r := f.put(id, contents[i]); r.Code != 200 {
				return fmt.Errorf("writer %d: %d %v", i, r.Code, r.body)
			}
			return nil
		})
		if ok != writers || len(errs) != 0 {
			t.Fatalf("round %d: ok = %d errs = %v", round, ok, errs)
		}
		got, has := f.selfie(t, id)
		match := false
		for _, c := range contents {
			match = match || bytes.Equal(got, c)
		}
		if !has || !match {
			t.Fatalf("round %d: stored file is not one of the uploads (len %d)", round, len(got))
		}
		if status, _ := f.state(t, id); status != "UPLOADED" {
			t.Fatalf("round %d: status = %s", round, status)
		}
		// 每輪新增一筆訂單，所以目錄內應恰好是 round+1 個正式檔、沒有任何暫存檔。
		if names := f.dirNames(t); len(names) != round+1 {
			t.Fatalf("round %d: dir = %v", round, names)
		}
	}
}

func TestReuploadOverwrites(t *testing.T) {
	f := newFx(t, 0)
	id := f.create(t, "k")
	second := append([]byte{0xFF, 0xD8, 0xFF}, []byte("second-and-longer-content")...)
	if r := f.put(id, jpegBytes); r.Code != 200 {
		t.Fatalf("first = %d %v", r.Code, r.body)
	}
	r := f.put(id, second)
	if r.Code != 200 || cameoOf(r)["status"] != "UPLOADED" {
		t.Fatalf("second = %d %v", r.Code, r.body)
	}
	if got, ok := f.selfie(t, id); !ok || !bytes.Equal(got, second) {
		t.Fatalf("stored = %q", got)
	}
	if names := f.dirNames(t); len(names) != 1 {
		t.Fatalf("files = %v", names)
	}
}

func TestExpiredUploadAndSubmitReleaseAndReturn410(t *testing.T) {
	const msg = "上傳期限已過，點數沒有扣款，請重新客串。"
	for _, from := range []string{"AWAITING_UPLOAD", "UPLOADED"} {
		for _, via := range []string{"upload", "submit"} {
			t.Run(from+" via "+via, func(t *testing.T) {
				f := newFx(t, 0)
				var id string
				if from == "UPLOADED" {
					id = f.createUploaded(t, "k")
				} else {
					id = f.create(t, "k")
				}
				f.clock.Set(t0.Add(ttl)) // 正好在期限：與 ExpireDue 同樣視為逾期
				var r resp
				if via == "upload" {
					r = f.put(id, jpegBytes)
				} else {
					r = f.submit(id)
				}
				wantError(t, r, 410, "UPLOAD_EXPIRED")
				if r.body["message"] != msg || r.body["retryable"] != false {
					t.Fatalf("body = %v", r.body)
				}
				if st, ps := f.state(t, id); st != "EXPIRED" || ps != "RELEASED" {
					t.Fatalf("state = %s/%s (release must be committed)", st, ps)
				}
				if p := points(t, f.svc); p.Available != 100 || p.Held != 0 {
					t.Fatalf("points = %+v", p)
				}
				if q := quota(t, f.svc); q.Remaining != 1000 {
					t.Fatalf("quota = %+v", q)
				}
				if n := count(t, f.pool, "ledger_entries"); n != 0 {
					t.Fatalf("ledger = %d", n)
				}
				f.wantNoFiles(t)
				// 已是 EXPIRED 後再來仍是 410。
				wantError(t, f.submit(id), 410, "UPLOAD_EXPIRED")
				wantError(t, f.put(id, jpegBytes), 410, "UPLOAD_EXPIRED")
				f.wantNoFiles(t)
			})
		}
	}
}

func TestSweepDeletesSelfie(t *testing.T) {
	f := newFx(t, 0)
	id := f.createUploaded(t, "k")
	f.clock.Set(t0.Add(ttl))
	ids, err := f.svc.ExpireDue(context.Background())
	if err != nil || len(ids) != 1 || ids[0] != id {
		t.Fatalf("ids = %v err = %v", ids, err)
	}
	f.wantNoFiles(t)
}

func TestNotExpiredOneSecondBeforeDeadline(t *testing.T) {
	f := newFx(t, 0)
	id := f.create(t, "k")
	f.clock.Set(t0.Add(ttl - time.Second))
	if r := f.put(id, jpegBytes); r.Code != 200 {
		t.Fatalf("put = %d %v", r.Code, r.body)
	}
	if r := f.submit(id); r.Code != 200 {
		t.Fatalf("submit = %d %v", r.Code, r.body)
	}
}

func TestUploadAndSubmitErrorsHTTP(t *testing.T) {
	f := newFx(t, 1024)
	id := f.create(t, "k")
	selfiePath := "/v1/cameos/" + id + "/selfie"

	t.Run("invalid selfie", func(t *testing.T) {
		for name, body := range map[string][]byte{
			"empty": nil, "png": []byte("\x89PNG\r\n\x1a\n"), "text": []byte("hello"), "two bytes": {0xFF, 0xD8},
		} {
			r := send(f.h, http.MethodPut, selfiePath, body, true, map[string]string{"Content-Type": "image/jpeg"})
			wantError(t, r, 400, "INVALID_SELFIE")
			if r.body["message"] != "照片格式不正確" || r.body["retryable"] != false {
				t.Fatalf("%s: body = %v", name, r.body)
			}
		}
		f.wantNoFiles(t)
		if st, _ := f.state(t, id); st != "AWAITING_UPLOAD" {
			t.Fatalf("status = %s", st)
		}
	})
	t.Run("content type is ignored", func(t *testing.T) {
		for _, ct := range []string{"", "text/plain", "image/png", "application/json"} {
			hdr := map[string]string{}
			if ct != "" {
				hdr["Content-Type"] = ct
			}
			if r := send(f.h, http.MethodPut, selfiePath, jpegBytes, true, hdr); r.Code != 200 {
				t.Fatalf("content-type %q: %d %v", ct, r.Code, r.body)
			}
		}
	})
	t.Run("too large", func(t *testing.T) {
		exact := append([]byte{0xFF, 0xD8, 0xFF}, bytes.Repeat([]byte{1}, 1024-3)...)
		if r := f.put(id, exact); r.Code != 200 {
			t.Fatalf("exact max: %d %v", r.Code, r.body)
		}
		over := append([]byte{0xFF, 0xD8, 0xFF}, bytes.Repeat([]byte{2}, 1024-2)...) // 1025 bytes
		r := f.put(id, over)
		wantError(t, r, 413, "SELFIE_TOO_LARGE")
		if r.body["message"] != "照片太大" || r.body["retryable"] != false {
			t.Fatalf("body = %v", r.body)
		}
		// 失敗的上傳不動到既有檔案，也沒有暫存檔。
		if got, _ := f.selfie(t, id); !bytes.Equal(got, exact) {
			t.Fatal("existing selfie was modified")
		}
		if names := f.dirNames(t); len(names) != 1 {
			t.Fatalf("files = %v", names)
		}
	})
	t.Run("not found", func(t *testing.T) {
		wantError(t, f.put("nope", jpegBytes), 404, "CAMEO_NOT_FOUND")
		wantError(t, f.submit("nope"), 404, "CAMEO_NOT_FOUND")
		wantError(t, f.put("a%00b", jpegBytes), 404, "CAMEO_NOT_FOUND")
		wantError(t, f.submit("a%00b"), 404, "CAMEO_NOT_FOUND")
	})
	t.Run("unauthorized", func(t *testing.T) {
		wantError(t, send(f.h, http.MethodPut, selfiePath, jpegBytes, false, nil), 401, "UNAUTHORIZED")
		wantError(t, send(f.h, http.MethodPost, "/v1/cameos/"+id+"/submit", nil, false, nil), 401, "UNAUTHORIZED")
	})
	t.Run("method not allowed", func(t *testing.T) {
		for _, c := range []struct{ method, path, allow string }{
			{http.MethodGet, selfiePath, "PUT"},
			{http.MethodPost, selfiePath, "PUT"},
			{http.MethodGet, "/v1/cameos/" + id + "/submit", "POST"},
			{http.MethodPut, "/v1/cameos/" + id + "/submit", "POST"},
		} {
			r := send(f.h, c.method, c.path, nil, true, nil)
			wantError(t, r, 405, "METHOD_NOT_ALLOWED")
			if r.Header().Get("Allow") != c.allow {
				t.Fatalf("%s %s Allow = %q", c.method, c.path, r.Header().Get("Allow"))
			}
		}
	})
	t.Run("selfie not uploaded", func(t *testing.T) {
		id2 := f.create(t, "k2")
		r := f.submit(id2)
		wantError(t, r, 409, "SELFIE_NOT_UPLOADED")
		if r.body["message"] != "請先上傳照片" || r.body["retryable"] != false {
			t.Fatalf("body = %v", r.body)
		}
		if st, ps := f.state(t, id2); st != "AWAITING_UPLOAD" || ps != "HELD" {
			t.Fatalf("state = %s/%s", st, ps)
		}
	})
	t.Run("invalid state after queued", func(t *testing.T) {
		if r := f.submit(id); r.Code != 200 {
			t.Fatalf("submit = %d %v", r.Code, r.body)
		}
		before, _ := f.selfie(t, id)
		for _, body := range [][]byte{jpegBytes, nil, []byte("junk")} { // 狀態檢查先於內容檢查
			r := f.put(id, body)
			wantError(t, r, 409, "INVALID_STATE")
			if r.body["message"] != "這筆客串目前無法上傳" || r.body["retryable"] != false {
				t.Fatalf("body = %v", r.body)
			}
		}
		for _, st := range []string{"GENERATING", "SUCCEEDED", "FAILED"} {
			if _, err := f.pool.Exec(context.Background(), `UPDATE cameos SET status = $1 WHERE id = $2`, st, id); err != nil {
				t.Fatal(err)
			}
			wantError(t, f.put(id, jpegBytes), 409, "INVALID_STATE")
		}
		if after, _ := f.selfie(t, id); !bytes.Equal(before, after) {
			t.Fatal("selfie changed")
		}
	})
}

// statusWriter 記下 handler 回應的狀態碼。
type statusWriter struct {
	http.ResponseWriter
	status int
}

func (w *statusWriter) WriteHeader(c int) { w.status = c; w.ResponseWriter.WriteHeader(c) }

func TestBodyCutShortLeavesNothingBehind(t *testing.T) {
	f := newFx(t, 0)
	done := make(chan int, 4)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sw := &statusWriter{ResponseWriter: w}
		f.h.ServeHTTP(sw, r)
		done <- sw.status
	}))
	defer srv.Close()

	cut := func(id string, declared int, sent []byte) int {
		t.Helper()
		conn, err := net.Dial("tcp", srv.Listener.Addr().String())
		if err != nil {
			t.Fatal(err)
		}
		fmt.Fprintf(conn, "PUT /v1/cameos/%s/selfie HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer test-token\r\nContent-Length: %d\r\n\r\n", id, declared)
		conn.Write(sent)
		conn.Close()
		select {
		case s := <-done:
			return s
		case <-time.After(5 * time.Second):
			t.Fatal("handler did not finish after client disconnect")
			return 0
		}
	}

	id := f.create(t, "k")
	// 已寫入內容後斷線。
	if s := cut(id, 100000, append(append([]byte{}, jpegBytes...), bytes.Repeat([]byte{7}, 50000)...)); s != 408 {
		t.Fatalf("status = %d, want 408", s)
	}
	if st, _ := f.state(t, id); st != "AWAITING_UPLOAD" {
		t.Fatalf("status = %s", st)
	}
	f.wantNoFiles(t)
	// 連檔頭都沒收完就斷線。
	if s := cut(id, 100, jpegBytes[:2]); s != 408 {
		t.Fatalf("status = %d, want 408", s)
	}
	f.wantNoFiles(t)

	// 已有舊檔時，中斷的重傳不影響舊檔與狀態。
	if r := f.put(id, jpegBytes); r.Code != 200 {
		t.Fatalf("put = %d", r.Code)
	}
	if s := cut(id, 100000, append([]byte{0xFF, 0xD8, 0xFF}, bytes.Repeat([]byte{9}, 20000)...)); s != 408 {
		t.Fatalf("status = %d, want 408", s)
	}
	if got, ok := f.selfie(t, id); !ok || !bytes.Equal(got, jpegBytes) {
		t.Fatalf("old selfie changed: %q", got)
	}
	if names := f.dirNames(t); len(names) != 1 {
		t.Fatalf("files = %v", names)
	}
	if st, _ := f.state(t, id); st != "UPLOADED" {
		t.Fatalf("status = %s", st)
	}
}

func TestBodyCutShortResponseIsRetryable(t *testing.T) {
	f := newFx(t, 0)
	id := f.create(t, "k")
	// 讀 body 失敗：以 reader 回報 ErrUnexpectedEOF 模擬（client 已不在，回應內容改在 handler 層驗證）。
	req := httptest.NewRequest(http.MethodPut, "/v1/cameos/"+id+"/selfie", &failingBody{data: jpegBytes})
	req.Header.Set("Authorization", "Bearer test-token")
	rec := httptest.NewRecorder()
	f.h.ServeHTTP(rec, req)
	var m map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &m)
	wantError(t, resp{rec, m}, 408, "UPLOAD_INTERRUPTED")
	if m["retryable"] != true {
		t.Fatalf("retryable = %v", m["retryable"])
	}
	f.wantNoFiles(t)
}

type failingBody struct {
	data []byte
	pos  int
}

func (b *failingBody) Read(p []byte) (int, error) {
	if b.pos >= len(b.data) {
		return 0, errors.New("connection reset")
	}
	n := copy(p, b.data[b.pos:])
	b.pos += n
	return n, nil
}

func TestLedgerConstraints(t *testing.T) {
	pool := newDB(t)
	ctx := context.Background()
	svc := newSvc(pool, testdb.NewClock(t0), usecase.Config{DailyLimit: 3, UploadTTL: ttl}, newFiles(t))
	r, err := svc.CreateCameo(ctx, "k", "c1")
	if err != nil {
		t.Fatal(err)
	}
	insert := func(typ string, cameoID any) error {
		_, err := pool.Exec(ctx, `INSERT INTO ledger_entries (type, amount, cameo_id, clip_title) VALUES ($1, 10, $2, 'x')`, typ, cameoID)
		return err
	}
	if err := insert("CHARGE", r.Cameo.ID); err != nil {
		t.Fatalf("first charge: %v", err)
	}
	wantPgCode := func(err error, code string) {
		t.Helper()
		var pe *pgconn.PgError
		if !errors.As(err, &pe) || pe.Code != code {
			t.Fatalf("err = %v, want pg code %s", err, code)
		}
	}
	wantPgCode(insert("CHARGE", r.Cameo.ID), "23505") // 第二筆 CHARGE：unique index
	if err := insert("REFUND", r.Cameo.ID); err != nil {
		t.Fatalf("refund after charge should be allowed: %v", err)
	}
	wantPgCode(insert("CHARGE", "no-such-cameo"), "23503") // 不存在的訂單：外鍵
	if err := insert("CHARGE", nil); err != nil {
		t.Fatalf("null cameo_id should stay allowed: %v", err)
	}
	if err := insert("CHARGE", nil); err != nil {
		t.Fatalf("multiple null cameo_id charges should be allowed: %v", err)
	}
}

// 點數餘額與占用必須取自同一個快照：送出時餘額與占用在同一個交易內同時變動，
// 所以 available 必須恆為常數；分兩次查詢就可能讀到一半的狀態。
func TestPointsSnapshotIsConsistentDuringSubmit(t *testing.T) {
	f := newFx(t, 0)
	ids := []string{f.createUploaded(t, "a"), f.createUploaded(t, "b"), f.createUploaded(t, "c")}
	stop := make(chan struct{})
	var wg sync.WaitGroup
	var bad []domain.Points
	var mu sync.Mutex
	reads := 0
	for i := 0; i < 4; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				select {
				case <-stop:
					return
				default:
				}
				p, err := f.svc.Points(context.Background())
				if err != nil {
					t.Error(err)
					return
				}
				mu.Lock()
				reads++
				if p.Available != 70 {
					bad = append(bad, p)
				}
				mu.Unlock()
			}
		}()
	}
	for round := 0; round < 20; round++ {
		for _, id := range ids {
			if _, err := f.svc.SubmitCameo(context.Background(), id); err != nil {
				t.Fatal(err)
			}
		}
		// 退回狀態再來一輪，讓讀取方有足夠機會撞上。
		if _, err := f.pool.Exec(context.Background(), `DELETE FROM ledger_entries;
			UPDATE cameos SET status = 'UPLOADED', points_state = 'HELD'; UPDATE points_account SET balance = 100`); err != nil {
			t.Fatal(err)
		}
	}
	close(stop)
	wg.Wait()
	if len(bad) > 0 {
		t.Fatalf("%d of %d reads were inconsistent, e.g. %+v", len(bad), reads, bad[0])
	}
	t.Logf("reads = %d", reads)
}

func TestPointsQueryReportsBalanceAndHeld(t *testing.T) {
	f := newFx(t, 0)
	f.create(t, "a")
	f.create(t, "b")
	if _, err := f.pool.Exec(context.Background(), `UPDATE points_account SET balance = 70`); err != nil {
		t.Fatal(err)
	}
	balance, held, err := postgres.NewQueries(f.pool).BalanceAndHeld(context.Background())
	if err != nil || balance != 70 || held != 20 {
		t.Fatalf("balance = %d held = %d err = %v", balance, held, err)
	}
	if p := points(t, f.svc); p.Available != 50 || p.Held != 20 {
		t.Fatalf("points = %+v", p)
	}
}
