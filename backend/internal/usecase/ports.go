package usecase

import (
	"context"

	"cameo/backend/internal/domain"
)

// Queries 是讀取資料的 port。
type Queries interface {
	ListClips(ctx context.Context) ([]domain.Clip, error)
	// Balance 回傳點數餘額（未扣除占用）。
	Balance(ctx context.Context) (int, error)
	// ListLedger 依 (created_at, id) 由新到舊回傳最多 limit 筆；after 為 nil 表示從頭。
	ListLedger(ctx context.Context, after *domain.LedgerCursor, limit int) ([]domain.LedgerEntry, error)
}
