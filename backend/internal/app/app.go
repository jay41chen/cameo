// Package app 提供服務啟動與逾期掃描。
package app

import (
	"context"
	"log/slog"
	"net/http"
	"time"

	"cameo/backend/internal/usecase"
)

// Serve 以 handler 提供 HTTP 服務並啟動逾期掃描，直到 ctx 結束或服務出錯。
func Serve(ctx context.Context, svc *usecase.Service, handler http.Handler, addr string, sweepEvery time.Duration) error {
	sweepCtx, cancelSweep := context.WithCancel(ctx)
	defer cancelSweep()
	go SweepExpired(sweepCtx, svc, sweepEvery)
	srv := &http.Server{Addr: addr, Handler: handler, ReadHeaderTimeout: 10 * time.Second}

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

// SweepExpired 啟動時先掃一次，之後每隔 every 掃一次，直到 ctx 結束。
func SweepExpired(ctx context.Context, svc *usecase.Service, every time.Duration) {
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
	ids, err := svc.ExpireDue(ctx)
	if err != nil {
		if ctx.Err() == nil {
			slog.Error("expiry sweep failed", "err", err)
		}
	} else if len(ids) > 0 {
		slog.Info("expired cameos", "count", len(ids))
	}
}
