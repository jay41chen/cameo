package usecase

import (
	"context"
	"io"
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
	// BalanceAndHeld 用同一個快照回傳點數餘額（未扣除占用）與 points_state=HELD 訂單的 cost 總和。
	BalanceAndHeld(ctx context.Context) (balance, held int, err error)
	// QuotaUsed 回傳建立時間在 [from, to) 且占用額度的訂單數。
	QuotaUsed(ctx context.Context, from, to time.Time) (int, error)
	// CameoByID 找不到回 domain.ErrCameoNotFound。
	CameoByID(ctx context.Context, id string) (domain.Cameo, error)
	// ListLedger 依 (created_at, id) 由新到舊回傳最多 limit 筆；after 為 nil 表示從頭。
	ListLedger(ctx context.Context, after *domain.PageCursor, limit int) ([]domain.LedgerEntry, error)
	// ListCameos 回傳狀態為 QUEUED／GENERATING／SUCCEEDED／FAILED 的訂單，依 (created_at, id) 由新到舊，最多 limit 筆；after 為 nil 表示從頭。
	ListCameos(ctx context.Context, after *domain.PageCursor, limit int) ([]domain.Cameo, error)
	// DispatchableIDs 回傳 QUEUED 且 generation_expires_at > now 的訂單 id，created_at 由舊到新，最多 limit 筆。
	DispatchableIDs(ctx context.Context, now time.Time, limit int) ([]string, error)
	// TimedOutIDs 回傳 QUEUED／GENERATING 且 generation_expires_at <= now 的訂單 id。
	TimedOutIDs(ctx context.Context, now time.Time) ([]string, error)
	// ShareTokenExists 回傳 token 是否對應一筆 SUCCEEDED 的訂單；只回是否有效，不帶出訂單資料。
	ShareTokenExists(ctx context.Context, token string) (bool, error)
}

// VendorEvent 是已通過驗證的廠商回呼；Status 為 SUCCEEDED 或 FAILED。
// Reason 只對 FAILED 有意義；VideoURL 只對 SUCCEEDED 有意義。
type VendorEvent struct {
	EventID    string
	JobID      string
	Status     string
	VideoURL   string
	Reason     *string
	ReceivedAt time.Time
}

// VendorClient 把工作交給廠商。jobID 就是訂單 id；同一個 jobID 重送是安全的（廠商以 jobID 去重）。
// 只有廠商明確接受（2xx）才回 nil。
type VendorClient interface {
	Dispatch(ctx context.Context, jobID string) error
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
	// ExpireDue 把已逾期的 AWAITING_UPLOAD／UPLOADED 訂單轉為 EXPIRED／RELEASED，回傳被轉換的訂單 id。
	ExpireDue(ctx context.Context, now time.Time) (ids []string, err error)
	// CameoForUpdate 讀取訂單並鎖住該列（SELECT ... FOR UPDATE）。
	CameoForUpdate(ctx context.Context, id string) (c domain.Cameo, found bool, err error)
	// ExpireCameo 若該訂單為 AWAITING_UPLOAD／UPLOADED 且已逾期，轉為 EXPIRED／RELEASED 並回傳 true。
	// 逾期判斷與 ExpireDue 是同一個規則（upload_expires_at <= now）。
	ExpireCameo(ctx context.Context, id string, now time.Time) (expired bool, err error)
	// SetUploadExpiresAt 把訂單的 upload_expires_at 設為 at（debug 異常用）。
	SetUploadExpiresAt(ctx context.Context, id string, at time.Time) error
	// MarkUploaded 把訂單設為 UPLOADED 並更新 updated_at。
	MarkUploaded(ctx context.Context, id string, now time.Time) error
	// DebitAccount 從餘額扣除 amount。
	DebitAccount(ctx context.Context, amount int) error
	// InsertLedger 新增一筆點數明細；created_at 取自 e.CreatedAt。
	InsertLedger(ctx context.Context, e domain.LedgerEntry) error
	// MarkQueued 把訂單設為 QUEUED／CHARGED、寫入 generation_expires_at 並更新 updated_at。
	MarkQueued(ctx context.Context, id string, now, generationExpiresAt time.Time) error
	// MarkGenerating 只在訂單仍是 QUEUED 時轉為 GENERATING，回傳是否有轉換。
	MarkGenerating(ctx context.Context, id string, now time.Time) (changed bool, err error)
	// MarkSucceeded 把訂單設為 SUCCEEDED、存 video_url（points_state 不變）並更新 updated_at。
	MarkSucceeded(ctx context.Context, id, videoURL string, now time.Time) error
	// MarkFailed 把訂單設為 FAILED／REFUNDED、寫 failure_reason 並更新 updated_at。
	MarkFailed(ctx context.Context, id, reason string, now time.Time) error
	// SetShareToken 設定或清除（nil）分享 token 並更新 updated_at。
	SetShareToken(ctx context.Context, id string, token *string, now time.Time) error
	// CreditAccount 把 amount 加回餘額。
	CreditAccount(ctx context.Context, amount int) error
	// InsertVendorEvent 記錄廠商事件；event_id 已存在（重複）回 inserted=false，不報錯。
	InsertVendorEvent(ctx context.Context, e VendorEvent) (inserted bool, err error)
	// ResetData 清空訂單、點數明細與廠商事件，並把餘額設回 seed_balance；片段保留。回傳被刪除的訂單 id。
	ResetData(ctx context.Context) (cameoIDs []string, err error)
}

// FileStore 存放自拍檔案。暫存檔與正式檔在同一個檔案系統，Commit 是原子覆蓋。
type FileStore interface {
	// WriteTemp 把 r 的內容寫成暫存檔並回傳暫存檔名；失敗時不留下暫存檔。
	WriteTemp(ctx context.Context, r io.Reader) (tempName string, err error)
	// Commit 把暫存檔原子地移到 cameoID 的正式位置，覆蓋舊檔。
	Commit(tempName, cameoID string) error
	// Discard 刪除暫存檔；不存在不算錯。
	Discard(tempName string) error
	// Delete 刪除 cameoID 的正式檔；不存在不算錯。
	Delete(cameoID string) error
}
