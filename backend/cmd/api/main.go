// Command api 組裝並啟動後端。
package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	apihttp "cameo/backend/internal/adapter/http"
	"cameo/backend/internal/adapter/postgres"
	"cameo/backend/internal/usecase"

	"github.com/jackc/pgx/v5/pgxpool"
)

func main() {
	if err := run(); err != nil {
		slog.Error("api stopped", "err", err)
		os.Exit(1)
	}
}

func run() error {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	dbURL := getenv("DATABASE_URL", "postgres://cameo:cameo@localhost:5433/cameo?sslmode=disable")
	addr := getenv("HTTP_ADDR", ":8080")

	pool, err := connect(ctx, dbURL)
	if err != nil {
		return err
	}
	defer pool.Close()
	if err := postgres.Migrate(ctx, pool); err != nil {
		return err
	}

	cfg := usecase.Config{
		LedgerPageSize: usecase.DefaultLedgerPageSize,
		DailyLimit:     getenvInt("DAILY_LIMIT", 3),
		UploadTTL:      getenvDuration("UPLOAD_TTL", time.Hour),
	}
	sweepEvery := getenvDuration("EXPIRY_SWEEP_INTERVAL", 30*time.Second)
	svc := usecase.NewService(postgres.NewQueries(pool), postgres.NewTxRunner(pool), usecase.SystemClock{}, cfg)
	return serve(ctx, svc, addr, sweepEvery)
}

// serve 啟動逾期掃描與 HTTP 服務，直到 ctx 結束或服務出錯。
func serve(ctx context.Context, svc *usecase.Service, addr string, sweepEvery time.Duration) error {
	sweepCtx, cancelSweep := context.WithCancel(ctx)
	defer cancelSweep()
	go sweepExpired(sweepCtx, svc, sweepEvery)
	srv := &http.Server{Addr: addr, Handler: apihttp.Handler(svc), ReadHeaderTimeout: 10 * time.Second}

	errCh := make(chan error, 1)
	go func() {
		slog.Info("listening", "addr", addr)
		errCh <- srv.ListenAndServe()
	}()
	select {
	case err := <-errCh:
		return err
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		return srv.Shutdown(shutdownCtx)
	}
}

// sweepExpired 啟動時先掃一次，之後每隔 every 掃一次，直到 ctx 結束。
func sweepExpired(ctx context.Context, svc *usecase.Service, every time.Duration) {
	sweepOnce(ctx, svc)
	t := time.NewTicker(every)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			sweepOnce(ctx, svc)
		}
	}
}

func sweepOnce(ctx context.Context, svc *usecase.Service) {
	n, err := svc.ExpireDue(ctx)
	if err != nil {
		if ctx.Err() == nil {
			slog.Error("expiry sweep failed", "err", err)
		}
	} else if n > 0 {
		slog.Info("expired cameos", "count", n)
	}
}

// connect 會重試一小段時間，避免資料庫剛啟動時失敗。
func connect(ctx context.Context, url string) (*pgxpool.Pool, error) {
	var lastErr error
	for i := 0; i < 15; i++ {
		pool, err := pgxpool.New(ctx, url)
		if err == nil {
			if err = pool.Ping(ctx); err == nil {
				return pool, nil
			}
			pool.Close()
		}
		lastErr = err
		slog.Warn("database not ready", "err", err)
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(time.Second):
		}
	}
	return nil, lastErr
}

// getenvInt 與 getenvDuration 在值無法解析或不為正時直接結束，避免靜默使用錯誤設定。
func getenvInt(key string, def int) int {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil || n <= 0 {
		slog.Error("invalid env", "key", key, "value", v)
		os.Exit(1)
	}
	return n
}

func getenvDuration(key string, def time.Duration) time.Duration {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	d, err := time.ParseDuration(v)
	if err != nil || d <= 0 {
		slog.Error("invalid env", "key", key, "value", v)
		os.Exit(1)
	}
	return d
}

func getenv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
