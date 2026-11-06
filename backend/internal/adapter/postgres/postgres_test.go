package postgres_test

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"os"
	"testing"
	"time"

	"cameo/backend/internal/adapter/postgres"
	"cameo/backend/internal/domain"
	"cameo/backend/internal/usecase"

	"github.com/jackc/pgx/v5/pgxpool"
)

// newDB 在 TEST_DATABASE_URL 指向的資料庫中建立獨立 schema 並套用 migration，
// 測試結束後整個 schema 刪除，不碰其他資料。
func newDB(t *testing.T) *pgxpool.Pool {
	t.Helper()
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		t.Fatal("TEST_DATABASE_URL is not set; point it at a Postgres, e.g. " +
			"TEST_DATABASE_URL='postgres://cameo:cameo@127.0.0.1:5433/cameo?sslmode=disable' go test ./...")
	}
	ctx := context.Background()
	b := make([]byte, 6)
	if _, err := rand.Read(b); err != nil {
		t.Fatal(err)
	}
	schema := "t_" + hex.EncodeToString(b)

	admin, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := admin.Exec(ctx, "CREATE SCHEMA "+schema); err != nil {
		admin.Close()
		t.Fatalf("create schema: %v", err)
	}
	cfg, err := pgxpool.ParseConfig(url)
	if err != nil {
		t.Fatal(err)
	}
	cfg.ConnConfig.RuntimeParams["search_path"] = schema
	// 關閉 index scan，讓排序不靠索引順序「碰巧」正確，分頁測試才能抓到排序缺陷。僅測試用。
	cfg.ConnConfig.RuntimeParams["enable_indexscan"] = "off"
	cfg.ConnConfig.RuntimeParams["enable_indexonlyscan"] = "off"
	cfg.ConnConfig.RuntimeParams["enable_bitmapscan"] = "off"
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		pool.Close()
		if _, err := admin.Exec(ctx, "DROP SCHEMA "+schema+" CASCADE"); err != nil {
			t.Errorf("drop schema: %v", err)
		}
		admin.Close()
	})
	if err := postgres.Migrate(ctx, pool); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	return pool
}

func TestMigrateIsRepeatableAndSeedsOnce(t *testing.T) {
	pool := newDB(t)
	ctx := context.Background()
	if err := postgres.Migrate(ctx, pool); err != nil {
		t.Fatalf("second migrate: %v", err)
	}
	q := postgres.NewQueries(pool)
	clips, err := q.ListClips(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(clips) != 3 || clips[0] != (domain.Clip{ID: "c1", Title: "雨夜重逢", Cost: 10}) {
		t.Fatalf("clips = %+v", clips)
	}
	balance, err := q.Balance(ctx)
	if err != nil || balance != 100 {
		t.Fatalf("balance = %d, err = %v", balance, err)
	}
	var versions int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM schema_migrations`).Scan(&versions); err != nil || versions != 2 {
		t.Fatalf("versions = %d, err = %v", versions, err)
	}
	var ledger int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM ledger_entries`).Scan(&ledger); err != nil || ledger != 0 {
		t.Fatalf("ledger = %d, err = %v", ledger, err)
	}
}

func TestPointsAvailable(t *testing.T) {
	pool := newDB(t)
	ctx := context.Background()
	if _, err := pool.Exec(ctx, `UPDATE points_account SET balance = 70 WHERE id = 1`); err != nil {
		t.Fatal(err)
	}
	svc := usecase.NewService(postgres.NewQueries(pool), 0)
	p, err := svc.Points(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if p.Available != 70 || p.Held != 0 {
		t.Fatalf("points = %+v, want available 70 held 0", p)
	}
}

func insertEntry(t *testing.T, pool *pgxpool.Pool, id string, at time.Time) {
	t.Helper()
	_, err := pool.Exec(context.Background(),
		`INSERT INTO ledger_entries (id, type, amount, cameo_id, clip_title, created_at) VALUES ($1, 'CHARGE', 10, NULL, 'x', $2)`, id, at)
	if err != nil {
		t.Fatal(err)
	}
}

func collect(t *testing.T, svc *usecase.Service) (ids []string, pages int) {
	t.Helper()
	cursor := ""
	for {
		page, err := svc.Ledger(context.Background(), cursor)
		if err != nil {
			t.Fatal(err)
		}
		pages++
		for _, e := range page.Items {
			ids = append(ids, e.ID)
		}
		if page.NextCursor == "" {
			return ids, pages
		}
		cursor = page.NextCursor
		if pages > 20 {
			t.Fatal("too many pages")
		}
	}
}

func TestLedgerPaginationStableWithTies(t *testing.T) {
	pool := newDB(t)
	base := time.Date(2026, 11, 7, 2, 0, 0, 123456000, time.UTC)
	// 5 筆，其中 a1~a3 時間完全相同；頁大小 2 會讓同一時間跨頁。
	insertEntry(t, pool, "e5", base.Add(2*time.Minute))
	insertEntry(t, pool, "a2", base)
	insertEntry(t, pool, "a3", base)
	insertEntry(t, pool, "a1", base)
	insertEntry(t, pool, "e0", base.Add(-time.Minute))

	svc := usecase.NewService(postgres.NewQueries(pool), 2)
	ids, pages := collect(t, svc)
	want := []string{"e5", "a3", "a2", "a1", "e0"}
	if len(ids) != len(want) {
		t.Fatalf("ids = %v, want %v", ids, want)
	}
	for i := range want {
		if ids[i] != want[i] {
			t.Fatalf("ids = %v, want %v", ids, want)
		}
	}
	if pages != 3 {
		t.Fatalf("pages = %d, want 3", pages)
	}
}

func TestLedgerLastPageHasNoCursorAndExactPageSize(t *testing.T) {
	pool := newDB(t)
	base := time.Date(2026, 11, 7, 2, 0, 0, 0, time.UTC)
	insertEntry(t, pool, "a", base)
	insertEntry(t, pool, "b", base.Add(time.Second))

	svc := usecase.NewService(postgres.NewQueries(pool), 2)
	page, err := svc.Ledger(context.Background(), "")
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Items) != 2 || page.NextCursor != "" {
		t.Fatalf("page = %+v, want 2 items and no cursor", page)
	}
	empty := newDB(t)
	page, err = usecase.NewService(postgres.NewQueries(empty), 2).Ledger(context.Background(), "")
	if err != nil || len(page.Items) != 0 || page.NextCursor != "" {
		t.Fatalf("empty page = %+v, err = %v", page, err)
	}
}

func TestLedgerInvalidCursor(t *testing.T) {
	pool := newDB(t)
	svc := usecase.NewService(postgres.NewQueries(pool), 2)
	for _, c := range []string{"!!!", "bm90LWpzb24", "e30" /* {} */, "eyJ0Ijoibm9wZSIsImkiOiJ4In0" /* bad time */} {
		if _, err := svc.Ledger(context.Background(), c); err != domain.ErrInvalidCursor {
			t.Errorf("cursor %q: err = %v, want ErrInvalidCursor", c, err)
		}
	}
}
