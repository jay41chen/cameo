package domain

import "time"

// Points 是使用者目前的點數狀態。
type Points struct {
	Available int
	Held      int
}

// NewPoints 依餘額與占用點數計算：available = balance - held。
func NewPoints(balance, held int) Points {
	return Points{Available: balance - held, Held: held}
}

// LedgerType 是點數明細類型；占用與釋放不寫明細。
type LedgerType string

const (
	LedgerCharge LedgerType = "CHARGE"
	LedgerRefund LedgerType = "REFUND"
)

// LedgerEntry 是一筆點數明細，Amount 一律為正數。
type LedgerEntry struct {
	ID        string
	Type      LedgerType
	Amount    int
	CameoID   *string
	ClipTitle string
	CreatedAt time.Time
}

// PageCursor 標記分頁位置（點數明細與訂單列表共用）：排序為 (CreatedAt, ID) 由新到舊。
type PageCursor struct {
	CreatedAt time.Time
	ID        string
}
