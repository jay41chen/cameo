// Package testdb 提供測試用的真實 Postgres 與可調整的時鐘。只給測試 import。
package testdb

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"os"
	"sync"
	"testing"
	"time"

	"cameo/backend/internal/adapter/postgres"

	"github.com/jackc/pgx/v5/pgxpool"
)

// New 在 TEST_DATABASE_URL 指向的資料庫中建立獨立 schema 並套用 migration，
// 測試結束後整個 schema 刪除，不碰其他資料。
func New(t *testing.T) *pgxpool.Pool {
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

// Clock 是可調整的假時鐘，實作 usecase.Clock。
type Clock struct {
	mu sync.Mutex
	t  time.Time
}

func NewClock(t time.Time) *Clock { return &Clock{t: t} }

func (c *Clock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}

func (c *Clock) Set(t time.Time) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.t = t
}

func (c *Clock) Advance(d time.Duration) { c.Set(c.Now().Add(d)) }
