// Package app 提供服務啟動與逾期掃描。
package app

import (
	"context"
	"log/slog"
	"net/http"
	"time"

	"cameo/backend/internal/usecase"
)

// Serve 以 handler 提供 HTTP 服務並啟動上傳逾期掃描與生成派工／逾時掃描，直到 ctx 結束或服務出錯。
func Serve(ctx context.Context, svc *usecase.Service, handler http.Handler, addr string, sweepEvery, dispatchEvery time.Duration) error {
	sweepCtx, cancelSweep := context.WithCancel(ctx)
	defer cancelSweep()
	go SweepExpired(sweepCtx, svc, sweepEvery)
	go SweepGeneration(sweepCtx, svc, dispatchEvery)
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

// SweepGeneration 啟動時先掃一次，之後每隔 every 掃一次，直到 ctx 結束。
// 每個週期先 FailTimedOut 再 DispatchDue，依序執行、不另開 goroutine。
// 前提：同一時間只有一個 api 實例（不做多實例協調）。
func SweepGeneration(ctx context.Context, svc *usecase.Service, every time.Duration) {
	generationOnce(ctx, svc)
	t := time.NewTicker(every)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			generationOnce(ctx, svc)
		}
	}
}

func generationOnce(ctx context.Context, svc *usecase.Service) {
	ids, err := svc.FailTimedOut(ctx)
	if err != nil {
		if ctx.Err() == nil {
			slog.Error("generation timeout sweep failed", "err", err)
		}
	}
	if len(ids) > 0 {
		slog.Info("failed timed-out cameos", "count", len(ids))
	}
	moved, err := svc.DispatchDue(ctx)
	if err != nil {
		if ctx.Err() == nil {
			slog.Error("vendor dispatch sweep failed", "err", err)
		}
	} else if len(moved) > 0 {
		slog.Info("dispatched cameos", "count", len(moved))
	}
}
