package usecase

import (
	"context"
	"time"

	"cameo/backend/internal/domain"
)

// Clock 讓時間可在測試中注入。
type Clock interface {
	Now() time.Time
}

// Queries 是讀取資料的 port。
type Queries interface {
	ListClips(ctx context.Context) ([]domain.Clip, error)
	// Balance 回傳點數餘額（未扣除占用）。
	Balance(ctx context.Context) (int, error)
	// HeldPoints 回傳 points_state=HELD 訂單的 cost 總和。
	HeldPoints(ctx context.Context) (int, error)
	// QuotaUsed 回傳建立時間在 [from, to) 且占用額度的訂單數。
	QuotaUsed(ctx context.Context, from, to time.Time) (int, error)
	// CameoByID 找不到回 domain.ErrCameoNotFound。
	CameoByID(ctx context.Context, id string) (domain.Cameo, error)
	// ListLedger 依 (created_at, id) 由新到舊回傳最多 limit 筆；after 為 nil 表示從頭。
	ListLedger(ctx context.Context, after *domain.LedgerCursor, limit int) ([]domain.LedgerEntry, error)
}

// TxRunner 在單一資料庫交易內執行 fn；fn 回傳錯誤則回滾。
type TxRunner interface {
	Run(ctx context.Context, fn func(tx Tx) error) error
}

// Tx 是交易內可用的操作。
type Tx interface {
	// LockAccount 鎖住使用者的 points_account（FOR UPDATE）並回傳餘額，讓同一使用者的建單序列化。
	LockAccount(ctx context.Context) (balance int, err error)
	CameoByKey(ctx context.Context, key string) (c domain.Cameo, found bool, err error)
	Clip(ctx context.Context, id string) (c domain.Clip, found bool, err error)
	HeldPoints(ctx context.Context) (int, error)
	QuotaUsed(ctx context.Context, from, to time.Time) (int, error)
	// InsertCameo 新增訂單並回傳資料庫產生的 id。
	InsertCameo(ctx context.Context, c domain.Cameo) (id string, err error)
	// ExpireDue 把已逾期的 AWAITING_UPLOAD／UPLOADED 訂單轉為 EXPIRED／RELEASED，回傳筆數。
	ExpireDue(ctx context.Context, now time.Time) (int, error)
}
