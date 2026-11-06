package integration_test

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"

	"cameo/backend/internal/domain"
	"cameo/backend/internal/usecase"
	"cameo/backend/test/internal/testdb"

	"github.com/jackc/pgx/v5/pgxpool"
)

// 台北 2026-11-06 10:00。
var t0 = time.Date(2026, 11, 6, 2, 0, 0, 0, time.UTC)

const ttl = time.Hour

func setup(t *testing.T, limit int) (*pgxpool.Pool, *usecase.Service, *testdb.Clock) {
	t.Helper()
	pool := newDB(t)
	clock := testdb.NewClock(t0)
	return pool, newSvc(pool, clock, usecase.Config{DailyLimit: limit, UploadTTL: ttl}, newFiles(t)), clock
}

func count(t *testing.T, pool *pgxpool.Pool, table string) int {
	t.Helper()
	var n int
	if err := pool.QueryRow(context.Background(), "SELECT count(*) FROM "+table).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func points(t *testing.T, svc *usecase.Service) domain.Points {
	t.Helper()
	p, err := svc.Points(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	return p
}

func quota(t *testing.T, svc *usecase.Service) domain.Quota {
	t.Helper()
	q, err := svc.Quota(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	return q
}

func TestCreateHoldsPointsWithoutLedger(t *testing.T) {
	pool, svc, _ := setup(t, 3)
	ctx := context.Background()
	res, err := svc.CreateCameo(ctx, "k1", "c1")
	if err != nil || !res.Created {
		t.Fatalf("create: %+v %v", res, err)
	}
	c := res.Cameo
	if c.Status != domain.StatusAwaitingUpload || c.PointsState != domain.PointsHeld || c.Clip.Cost != 10 ||
		c.ID == "" || !c.UploadExpiresAt.Equal(t0.Add(ttl)) {
		t.Fatalf("cameo = %+v", c)
	}
	if p := points(t, svc); p.Available != 90 || p.Held != 10 {
		t.Fatalf("points = %+v", p)
	}
	if n := count(t, pool, "ledger_entries"); n != 0 {
		t.Fatalf("ledger rows = %d", n)
	}
	got, err := svc.Cameo(ctx, c.ID)
	if err != nil || got.ID != c.ID || got.Clip.Title != "雨夜重逢" {
		t.Fatalf("get = %+v %v", got, err)
	}
	if _, err := svc.Cameo(ctx, "nope"); !errors.Is(err, domain.ErrCameoNotFound) {
		t.Fatalf("get missing err = %v", err)
	}
}

func TestIdempotentReplay(t *testing.T) {
	pool, svc, clock := setup(t, 3)
	ctx := context.Background()
	first, err := svc.CreateCameo(ctx, "k1", "c1")
	if err != nil {
		t.Fatal(err)
	}
	clock.Advance(time.Minute)
	again, err := svc.CreateCameo(ctx, "k1", "c1")
	if err != nil || again.Created || again.Cameo.ID != first.Cameo.ID {
		t.Fatalf("replay = %+v %v", again, err)
	}
	if p := points(t, svc); p.Available != 90 || p.Held != 10 {
		t.Fatalf("points = %+v", p)
	}
	if q := quota(t, svc); q.Remaining != 2 {
		t.Fatalf("quota = %+v", q)
	}
	if n := count(t, pool, "cameos"); n != 1 {
		t.Fatalf("cameos = %d", n)
	}
	if _, err := svc.CreateCameo(ctx, "k1", "c2"); !errors.Is(err, domain.ErrIdempotencyConflict) {
		t.Fatalf("different clip err = %v", err)
	}
}

func TestReplayIgnoresCurrentState(t *testing.T) {
	// 額度和點數都用完、訂單已過期，同一 key 重送仍回該訂單。
	pool, svc, clock := setup(t, 1)
	ctx := context.Background()
	first, err := svc.CreateCameo(ctx, "k1", "c1")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := svc.CreateCameo(ctx, "k2", "c1"); !errors.Is(err, domain.ErrDailyLimitReached) {
		t.Fatalf("err = %v", err)
	}
	clock.Advance(2 * ttl)
	if ids, err := svc.ExpireDue(ctx); err != nil || len(ids) != 1 {
		t.Fatalf("expire = %v %v", ids, err)
	}
	again, err := svc.CreateCameo(ctx, "k1", "c1")
	if err != nil || again.Created || again.Cameo.ID != first.Cameo.ID || again.Cameo.Status != domain.StatusExpired {
		t.Fatalf("replay = %+v %v", again, err)
	}
	if n := count(t, pool, "cameos"); n != 1 {
		t.Fatalf("cameos = %d", n)
	}
}

func TestCreateRejections(t *testing.T) {
	ctx := context.Background()
	t.Run("clip not found", func(t *testing.T) {
		_, svc, _ := setup(t, 3)
		if _, err := svc.CreateCameo(ctx, "k", "nope"); !errors.Is(err, domain.ErrClipNotFound) {
			t.Fatalf("err = %v", err)
		}
	})
	t.Run("daily limit", func(t *testing.T) {
		pool, svc, _ := setup(t, 3)
		for i := 0; i < 3; i++ {
			if _, err := svc.CreateCameo(ctx, fmt.Sprint("k", i), "c1"); err != nil {
				t.Fatal(err)
			}
		}
		if _, err := svc.CreateCameo(ctx, "k3", "c1"); !errors.Is(err, domain.ErrDailyLimitReached) {
			t.Fatalf("err = %v", err)
		}
		if n := count(t, pool, "cameos"); n != 3 {
			t.Fatalf("cameos = %d", n)
		}
		if p := points(t, svc); p.Available != 70 || p.Held != 30 {
			t.Fatalf("points = %+v", p)
		}
	})
	t.Run("insufficient points", func(t *testing.T) {
		pool, svc, _ := setup(t, 10)
		if _, err := pool.Exec(ctx, `UPDATE points_account SET balance = 15`); err != nil {
			t.Fatal(err)
		}
		if _, err := svc.CreateCameo(ctx, "k0", "c1"); err != nil {
			t.Fatal(err)
		}
		if _, err := svc.CreateCameo(ctx, "k1", "c1"); !errors.Is(err, domain.ErrInsufficientPoints) {
			t.Fatalf("err = %v", err)
		}
		if p := points(t, svc); p.Available != 5 || p.Held != 10 {
			t.Fatalf("points = %+v", p)
		}
	})
}

func runConcurrently(n int, fn func(i int) error) (ok int, errs []error) {
	var wg sync.WaitGroup
	var mu sync.Mutex
	start := make(chan struct{})
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			err := fn(i)
			mu.Lock()
			defer mu.Unlock()
			if err == nil {
				ok++
			} else {
				errs = append(errs, err)
			}
		}()
	}
	close(start)
	wg.Wait()
	return ok, errs
}

func TestConcurrentSameKeyCreatesOne(t *testing.T) {
	pool, svc, _ := setup(t, 3)
	ids := make(chan string, 20)
	ok, errs := runConcurrently(20, func(int) error {
		r, err := svc.CreateCameo(context.Background(), "same", "c1")
		if err == nil {
			ids <- r.Cameo.ID
		}
		return err
	})
	close(ids)
	if ok != 20 || len(errs) != 0 {
		t.Fatalf("ok = %d errs = %v", ok, errs)
	}
	first := ""
	for id := range ids {
		if first == "" {
			first = id
		}
		if id != first {
			t.Fatalf("different ids %s vs %s", id, first)
		}
	}
	if n := count(t, pool, "cameos"); n != 1 {
		t.Fatalf("cameos = %d", n)
	}
	if p := points(t, svc); p.Held != 10 {
		t.Fatalf("points = %+v", p)
	}
}

func TestConcurrentDistinctKeysRespectLimitAndPoints(t *testing.T) {
	t.Run("daily limit", func(t *testing.T) {
		pool, svc, _ := setup(t, 3)
		ok, errs := runConcurrently(12, func(i int) error {
			_, err := svc.CreateCameo(context.Background(), fmt.Sprint("k", i), "c1")
			return err
		})
		if ok != 3 || n(t, pool) != 3 {
			t.Fatalf("ok = %d rows = %d errs = %v", ok, n(t, pool), errs)
		}
		for _, err := range errs {
			if !errors.Is(err, domain.ErrDailyLimitReached) {
				t.Fatalf("err = %v", err)
			}
		}
	})
	t.Run("points", func(t *testing.T) {
		pool, svc, _ := setup(t, 100)
		if _, err := pool.Exec(context.Background(), `UPDATE points_account SET balance = 25`); err != nil {
			t.Fatal(err)
		}
		ok, errs := runConcurrently(12, func(i int) error {
			_, err := svc.CreateCameo(context.Background(), fmt.Sprint("k", i), "c1")
			return err
		})
		if ok != 2 || n(t, pool) != 2 {
			t.Fatalf("ok = %d rows = %d errs = %v", ok, n(t, pool), errs)
		}
		for _, err := range errs {
			if !errors.Is(err, domain.ErrInsufficientPoints) {
				t.Fatalf("err = %v", err)
			}
		}
		if p := points(t, svc); p.Available != 5 || p.Held != 20 {
			t.Fatalf("points = %+v", p)
		}
	})
}

func n(t *testing.T, pool *pgxpool.Pool) int { return count(t, pool, "cameos") }

func TestExpireDue(t *testing.T) {
	pool, svc, clock := setup(t, 3)
	ctx := context.Background()
	if _, err := svc.CreateCameo(ctx, "k1", "c1"); err != nil {
		t.Fatal(err)
	}
	clock.Advance(ttl - time.Second)
	if ids, err := svc.ExpireDue(ctx); err != nil || len(ids) != 0 {
		t.Fatalf("before deadline: %v %v", ids, err)
	}
	if p := points(t, svc); p.Held != 10 {
		t.Fatalf("points = %+v", p)
	}
	clock.Advance(time.Second) // 正好到期限：upload_expires_at <= now
	if ids, err := svc.ExpireDue(ctx); err != nil || len(ids) != 1 {
		t.Fatalf("at deadline: %v %v", ids, err)
	}
	var status, ps string
	if err := pool.QueryRow(ctx, `SELECT status, points_state FROM cameos`).Scan(&status, &ps); err != nil {
		t.Fatal(err)
	}
	if status != "EXPIRED" || ps != "RELEASED" {
		t.Fatalf("state = %s/%s", status, ps)
	}
	if p := points(t, svc); p.Available != 100 || p.Held != 0 {
		t.Fatalf("points = %+v", p)
	}
	if q := quota(t, svc); q.Remaining != 3 {
		t.Fatalf("quota = %+v", q)
	}
	if n := count(t, pool, "ledger_entries"); n != 0 {
		t.Fatalf("ledger = %d", n)
	}
	var updated time.Time
	_ = pool.QueryRow(ctx, `SELECT updated_at FROM cameos`).Scan(&updated)
	clock.Advance(time.Hour)
	if ids, err := svc.ExpireDue(ctx); err != nil || len(ids) != 0 {
		t.Fatalf("second sweep: %v %v", ids, err)
	}
	var again time.Time
	_ = pool.QueryRow(ctx, `SELECT updated_at FROM cameos`).Scan(&again)
	if !again.Equal(updated) {
		t.Fatalf("second sweep modified row")
	}
}

func TestExpireDueLeavesTerminalStatesAlone(t *testing.T) {
	pool, svc, clock := setup(t, 3)
	ctx := context.Background()
	r, err := svc.CreateCameo(ctx, "k1", "c1")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, `UPDATE cameos SET status = 'FAILED', points_state = 'REFUNDED', failure_reason = 'X' WHERE id = $1`, r.Cameo.ID); err != nil {
		t.Fatal(err)
	}
	clock.Advance(2 * ttl)
	if ids, err := svc.ExpireDue(ctx); err != nil || len(ids) != 0 {
		t.Fatalf("sweep: %v %v", ids, err)
	}
}

func TestQuotaDecreasesAndResetsAtTaipeiMidnight(t *testing.T) {
	_, svc, clock := setup(t, 3)
	ctx := context.Background()
	// 台北 11/06 23:59，UTC 仍是 11/06 15:59。
	clock.Set(time.Date(2026, 11, 6, 15, 59, 0, 0, time.UTC))
	if q := quota(t, svc); q.Remaining != 3 || q.Display != "今日還可客串 3 次" {
		t.Fatalf("quota = %+v", q)
	}
	for i := 0; i < 3; i++ {
		if _, err := svc.CreateCameo(ctx, fmt.Sprint("k", i), "c1"); err != nil {
			t.Fatal(err)
		}
		if q := quota(t, svc); q.Remaining != 2-i {
			t.Fatalf("after %d: %+v", i+1, q)
		}
	}
	q := quota(t, svc)
	if q.Display != "今日客串次數已用完" || q.ResetsAt.In(domain.Taipei).Format(time.RFC3339) != "2026-11-07T00:00:00+08:00" {
		t.Fatalf("quota = %+v", q)
	}
	// 台北 11/07 00:00 整：新的一天（UTC 仍是 11/06）。
	clock.Set(time.Date(2026, 11, 6, 16, 0, 0, 0, time.UTC))
	q = quota(t, svc)
	if q.Remaining != 3 || q.ResetsAt.In(domain.Taipei).Format(time.RFC3339) != "2026-11-08T00:00:00+08:00" {
		t.Fatalf("after midnight: %+v", q)
	}
	// 台北 11/07 08:30，UTC 已是 11/07：昨天 23:59 的訂單不計入。
	clock.Set(time.Date(2026, 11, 7, 0, 30, 0, 0, time.UTC))
	if q := quota(t, svc); q.Remaining != 3 {
		t.Fatalf("next day: %+v", q)
	}
	// 回到台北 11/06 的午夜前一刻：仍算前一天。
	clock.Set(time.Date(2026, 11, 6, 15, 59, 59, 0, time.UTC))
	if q := quota(t, svc); q.Remaining != 0 {
		t.Fatalf("before midnight: %+v", q)
	}
}

func TestQuotaRestoredByExpiryAndFailure(t *testing.T) {
	pool, svc, clock := setup(t, 3)
	ctx := context.Background()
	var ids []string
	for i := 0; i < 3; i++ {
		r, err := svc.CreateCameo(ctx, fmt.Sprint("k", i), "c1")
		if err != nil {
			t.Fatal(err)
		}
		ids = append(ids, r.Cameo.ID)
	}
	// 還沒有回呼流程，直接寫入狀態模擬。
	set := func(id, status, ps string, reason any) {
		t.Helper()
		if _, err := pool.Exec(ctx, `UPDATE cameos SET status = $2, points_state = $3, failure_reason = $4 WHERE id = $1`, id, status, ps, reason); err != nil {
			t.Fatal(err)
		}
	}
	if q := quota(t, svc); q.Remaining != 0 {
		t.Fatalf("quota = %+v", q)
	}
	set(ids[0], "FAILED", "REFUNDED", "VENDOR_ERROR")
	if q := quota(t, svc); q.Remaining != 1 {
		t.Fatalf("FAILED should restore: %+v", q)
	}
	set(ids[1], "FAILED", "REFUNDED", "CONTENT_REJECTED")
	if q := quota(t, svc); q.Remaining != 1 {
		t.Fatalf("CONTENT_REJECTED should not restore: %+v", q)
	}
	set(ids[1], "SUCCEEDED", "CHARGED", nil)
	if q := quota(t, svc); q.Remaining != 1 {
		t.Fatalf("SUCCEEDED counts: %+v", q)
	}
	clock.Advance(2 * ttl)
	if _, err := svc.ExpireDue(ctx); err != nil { // 只有 ids[2] 仍在 AWAITING_UPLOAD
		t.Fatal(err)
	}
	if q := quota(t, svc); q.Remaining != 2 {
		t.Fatalf("expiry should restore: %+v", q)
	}
}

func TestExpireDueReleasesUploaded(t *testing.T) {
	pool, svc, clock := setup(t, 3)
	ctx := context.Background()
	r, err := svc.CreateCameo(ctx, "k1", "c1")
	if err != nil {
		t.Fatal(err)
	}
	// 還沒有上傳流程，直接把訂單改成 UPLOADED。
	if _, err := pool.Exec(ctx, `UPDATE cameos SET status = 'UPLOADED' WHERE id = $1`, r.Cameo.ID); err != nil {
		t.Fatal(err)
	}
	clock.Advance(ttl)
	if ids, err := svc.ExpireDue(ctx); err != nil || len(ids) != 1 {
		t.Fatalf("sweep: %v %v", ids, err)
	}
	var status, ps string
	if err := pool.QueryRow(ctx, `SELECT status, points_state FROM cameos`).Scan(&status, &ps); err != nil {
		t.Fatal(err)
	}
	if status != "EXPIRED" || ps != "RELEASED" {
		t.Fatalf("state = %s/%s", status, ps)
	}
	if p := points(t, svc); p.Available != 100 || p.Held != 0 {
		t.Fatalf("points = %+v", p)
	}
	if q := quota(t, svc); q.Remaining != 3 {
		t.Fatalf("quota = %+v", q)
	}
	if n := count(t, pool, "ledger_entries"); n != 0 {
		t.Fatalf("ledger = %d", n)
	}
}

func TestCostIsSnapshotAtCreation(t *testing.T) {
	pool, svc, _ := setup(t, 3)
	ctx := context.Background()
	first, err := svc.CreateCameo(ctx, "k1", "c1")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, `UPDATE clips SET cost = 50 WHERE id = 'c1'`); err != nil {
		t.Fatal(err)
	}
	got, err := svc.Cameo(ctx, first.Cameo.ID)
	if err != nil || got.Clip.Cost != 10 {
		t.Fatalf("get cost = %d %v", got.Clip.Cost, err)
	}
	again, err := svc.CreateCameo(ctx, "k1", "c1")
	if err != nil || again.Created || again.Cameo.Clip.Cost != 10 {
		t.Fatalf("replay = %+v %v", again, err)
	}
	if p := points(t, svc); p.Available != 90 || p.Held != 10 {
		t.Fatalf("points = %+v", p)
	}
}

func TestIdempotencyConflictBeatsClipNotFound(t *testing.T) {
	_, svc, _ := setup(t, 3)
	ctx := context.Background()
	if _, err := svc.CreateCameo(ctx, "k1", "c1"); err != nil {
		t.Fatal(err)
	}
	if _, err := svc.CreateCameo(ctx, "k1", "nope"); !errors.Is(err, domain.ErrIdempotencyConflict) {
		t.Fatalf("err = %v", err)
	}
}

func TestDailyLimitBeatsInsufficientPoints(t *testing.T) {
	pool, svc, _ := setup(t, 1)
	ctx := context.Background()
	if _, err := pool.Exec(ctx, `UPDATE points_account SET balance = 10`); err != nil {
		t.Fatal(err)
	}
	if _, err := svc.CreateCameo(ctx, "k1", "c1"); err != nil {
		t.Fatal(err)
	}
	// 額度與點數同時用完。
	if _, err := svc.CreateCameo(ctx, "k2", "c1"); !errors.Is(err, domain.ErrDailyLimitReached) {
		t.Fatalf("err = %v", err)
	}
}

func TestInvalidTextIsNotFound(t *testing.T) {
	_, svc, _ := setup(t, 3)
	ctx := context.Background()
	for _, id := range []string{"a\x00b", "\x00", "a\xffb"} {
		if _, err := svc.Cameo(ctx, id); !errors.Is(err, domain.ErrCameoNotFound) {
			t.Errorf("Cameo(%q) err = %v", id, err)
		}
		if _, err := svc.CreateCameo(ctx, "k"+fmt.Sprint(len(id)), id); !errors.Is(err, domain.ErrClipNotFound) {
			t.Errorf("CreateCameo clip %q err = %v", id, err)
		}
	}
	// 同 key 情況仍照既有順序：冪等衝突優先。
	if _, err := svc.CreateCameo(ctx, "same", "c1"); err != nil {
		t.Fatal(err)
	}
	if _, err := svc.CreateCameo(ctx, "same", "a\x00b"); !errors.Is(err, domain.ErrIdempotencyConflict) {
		t.Fatalf("same key err = %v", err)
	}
}
