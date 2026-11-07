// Command api 組裝並啟動後端。
package main

import (
	"context"
	"errors"
	"log/slog"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"cameo/backend/assets"
	"cameo/backend/internal/adapter/filestore"
	apihttp "cameo/backend/internal/adapter/http"
	"cameo/backend/internal/adapter/postgres"
	"cameo/backend/internal/adapter/vendor"
	"cameo/backend/internal/app"
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

	webhookSecret := os.Getenv("VENDOR_WEBHOOK_SECRET")
	if webhookSecret == "" {
		return errors.New("VENDOR_WEBHOOK_SECRET is required")
	}

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
		CameoPageSize:  usecase.DefaultCameoPageSize,
		DailyLimit:     getenvInt("DAILY_LIMIT", 3),
		UploadTTL:      getenvDuration("UPLOAD_TTL", time.Hour),
		MaxSelfieBytes: int64(getenvInt("MAX_SELFIE_BYTES", usecase.DefaultMaxSelfieBytes)),
		GenerationTTL:  getenvDuration("GENERATION_TTL", usecase.DefaultGenerationTTL),
	}
	files, err := filestore.New(getenv("SELFIE_DIR", "/data/selfies"))
	if err != nil {
		return err
	}
	sweepEvery := getenvDuration("EXPIRY_SWEEP_INTERVAL", 30*time.Second)
	dispatchEvery := getenvDuration("DISPATCH_SWEEP_INTERVAL", 2*time.Second)
	svc := usecase.NewService(postgres.NewQueries(pool), postgres.NewTxRunner(pool), usecase.SystemClock{}, cfg, files).
		WithVendor(vendor.NewClient(getenv("VENDOR_URL", "http://localhost:9000")))
	return app.Serve(ctx, svc, apihttp.Handler(svc, apihttp.WithVendorWebhook([]byte(webhookSecret)), apihttp.WithSampleVideo(assets.SampleVideo), apihttp.WithPublicBaseURL(getenv("PUBLIC_BASE_URL", "http://localhost:8080"))), addr, sweepEvery, dispatchEvery)
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
