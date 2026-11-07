package usecase_test

import (
	"bytes"
	"context"
	"errors"
	"testing"

	"cameo/backend/internal/domain"
)

// hook 為 nil 時行為不變（既有測試未設 hook 即涵蓋；這裡再明確驗一次）。
func TestUploadExpiryFaultNilKeepsBehavior(t *testing.T) {
	e := newEnv(domain.StatusUploaded)
	if _, err := e.svc.SubmitCameo(context.Background(), "c-1"); err != nil || e.db.cameo.Status != domain.StatusQueued {
		t.Fatalf("err = %v status = %s", err, e.db.cameo.Status)
	}
}

// hook 只在狀態允許（AWAITING_UPLOAD／UPLOADED）時才被呼叫；其他狀態不呼叫（因而不消耗）。
func TestUploadExpiryFaultOnlyCalledWhenStatusAllows(t *testing.T) {
	ctx := context.Background()
	for _, tc := range []struct {
		status string
		calls  int
	}{
		{domain.StatusAwaitingUpload, 1}, {domain.StatusUploaded, 1},
		{domain.StatusExpired, 0}, {domain.StatusQueued, 0}, {domain.StatusGenerating, 0},
		{domain.StatusSucceeded, 0}, {domain.StatusFailed, 0},
	} {
		for _, path := range []string{"upload", "submit"} {
			t.Run(tc.status+"/"+path, func(t *testing.T) {
				e := newEnv(tc.status)
				calls := 0
				e.svc.SetUploadExpiryFault(func() bool { calls++; return false })
				if path == "upload" {
					_, _ = e.svc.UploadSelfie(ctx, "c-1", bytes.NewReader(jpeg))
				} else {
					_, _ = e.svc.SubmitCameo(ctx, "c-1")
				}
				if calls != tc.calls {
					t.Fatalf("hook calls = %d, want %d", calls, tc.calls)
				}
			})
		}
	}
}

// hook 回 true：走真實的逾期釋放（ExpireCameo），回 ErrUploadExpired。
func TestUploadExpiryFaultTrueExpiresThroughRealPath(t *testing.T) {
	ctx := context.Background()
	for _, st := range []string{domain.StatusAwaitingUpload, domain.StatusUploaded} {
		t.Run("upload "+st, func(t *testing.T) {
			e := newEnv(st)
			e.svc.SetUploadExpiryFault(func() bool { return true })
			_, err := e.svc.UploadSelfie(ctx, "c-1", bytes.NewReader(jpeg))
			if !errors.Is(err, domain.ErrUploadExpired) || e.db.cameo.Status != domain.StatusExpired || e.db.cameo.PointsState != domain.PointsReleased {
				t.Fatalf("err = %v cameo = %+v", err, e.db.cameo)
			}
			if len(e.files.temps) != 0 {
				t.Fatalf("temps left: %v", e.files.temps)
			}
		})
		t.Run("submit "+st, func(t *testing.T) {
			e := newEnv(st)
			e.svc.SetUploadExpiryFault(func() bool { return true })
			_, err := e.svc.SubmitCameo(ctx, "c-1")
			if !errors.Is(err, domain.ErrUploadExpired) || e.db.cameo.Status != domain.StatusExpired || e.db.balance != 100 || len(e.db.ledger) != 0 {
				t.Fatalf("err = %v cameo = %+v balance = %d ledger = %v", err, e.db.cameo, e.db.balance, e.db.ledger)
			}
		})
	}
}

// 內容不合法時在讀 body 階段就回錯，不進交易，hook 不被呼叫。
func TestUploadExpiryFaultNotCalledForInvalidContent(t *testing.T) {
	e := newEnv(domain.StatusAwaitingUpload)
	calls := 0
	e.svc.SetUploadExpiryFault(func() bool { calls++; return true })
	if _, err := e.svc.UploadSelfie(context.Background(), "c-1", bytes.NewReader([]byte("not a jpeg"))); !errors.Is(err, domain.ErrInvalidSelfie) {
		t.Fatalf("err = %v", err)
	}
	if calls != 0 || e.db.cameo.Status != domain.StatusAwaitingUpload {
		t.Fatalf("calls = %d status = %s", calls, e.db.cameo.Status)
	}
}
