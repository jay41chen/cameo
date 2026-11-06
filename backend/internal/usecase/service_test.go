package usecase_test

import (
	"context"
	"encoding/base64"
	"errors"
	"testing"
	"time"

	"cameo/backend/internal/domain"
	"cameo/backend/internal/usecase"
)

type emptyQueries struct{}

func (emptyQueries) ListClips(context.Context) ([]domain.Clip, error) { return nil, nil }
func (emptyQueries) BalanceAndHeld(context.Context) (int, int, error) { return 0, 0, nil }
func (emptyQueries) QuotaUsed(context.Context, time.Time, time.Time) (int, error) {
	return 0, nil
}
func (emptyQueries) CameoByID(context.Context, string) (domain.Cameo, error) {
	return domain.Cameo{}, domain.ErrCameoNotFound
}
func (emptyQueries) ListLedger(context.Context, *domain.LedgerCursor, int) ([]domain.LedgerEntry, error) {
	return nil, nil
}

func cursorOf(payload string) string {
	return base64.RawURLEncoding.EncodeToString([]byte(payload))
}

func TestLedgerRejectsBadCursorIDs(t *testing.T) {
	svc := usecase.NewService(emptyQueries{}, nil, nil, usecase.Config{LedgerPageSize: 20}, nil)
	cases := map[string]string{
		"NUL in id (escaped)": cursorOf(`{"t":"2026-11-07T02:00:00Z","i":"a\u0000b"}`),
		"invalid UTF-8 in id": cursorOf("{\"t\":\"2026-11-07T02:00:00Z\",\"i\":\"a\xffb\"}"),
		"empty id":            cursorOf(`{"t":"2026-11-07T02:00:00Z","i":""}`),
		"not base64":          "!!!",
		"bad time":            cursorOf(`{"t":"x","i":"a"}`),
	}
	for name, c := range cases {
		if _, err := svc.Ledger(context.Background(), c); !errors.Is(err, domain.ErrInvalidCursor) {
			t.Errorf("%s: err = %v, want ErrInvalidCursor", name, err)
		}
	}
	ok := cursorOf(`{"t":"2026-11-07T02:00:00Z","i":"a1"}`)
	if _, err := svc.Ledger(context.Background(), ok); err != nil {
		t.Errorf("valid cursor: err = %v", err)
	}
}
