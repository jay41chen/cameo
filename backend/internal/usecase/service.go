package usecase

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"strings"
	"time"
	"unicode/utf8"

	"cameo/backend/internal/domain"
)

// DefaultLedgerPageSize 是明細每頁筆數。
const DefaultLedgerPageSize = 20

// DefaultCameoPageSize 是訂單列表每頁筆數。
const DefaultCameoPageSize = 20

// Config 是業務設定，由 cmd 從環境變數讀入。
type Config struct {
	LedgerPageSize int
	// CameoPageSize 是訂單列表每頁筆數；0 表示用 DefaultCameoPageSize。
	CameoPageSize int
	DailyLimit    int
	UploadTTL     time.Duration
	// MaxSelfieBytes 是自拍檔案上限；0 表示用 DefaultMaxSelfieBytes。
	MaxSelfieBytes int64
	// GenerationTTL 是送出後到生成完成的期限（涵蓋排隊與生成）；0 表示用 DefaultGenerationTTL。
	GenerationTTL time.Duration
}

// DefaultGenerationTTL 是生成期限的預設值。
const DefaultGenerationTTL = 30 * time.Minute

// DefaultMaxSelfieBytes 是自拍檔案大小上限的預設值（10 MiB）。
const DefaultMaxSelfieBytes = 10 << 20

type Service struct {
	q      Queries
	tx     TxRunner
	clock  Clock
	cfg    Config
	files  FileStore
	vendor VendorClient
	// uploadExpiryFault 是 debug 用的 hook：回傳 true 表示這次請求要讓訂單逾期。usecase 不知道異常登記，
	// 只在組裝時由 adapter 注入；nil 表示沒有。
	uploadExpiryFault func() bool
	// rc 是 debug 用的執行期設定：非 nil 時 dailyLimit、generationTTL 讀它，nil 時讀 cfg。
	// 只在組裝時由 adapter 注入。
	rc RuntimeConfig
}

// RuntimeConfig 提供可在執行期調整的業務設定（debug 用）。實作要能同時讀寫。
type RuntimeConfig interface {
	DailyLimit() int
	GenerationTTL() time.Duration
	// InitialPoints 回傳 reset 時要設成的餘額；nil 表示用資料庫的 seed_balance。
	InitialPoints() *int
}

func NewService(q Queries, tx TxRunner, clock Clock, cfg Config, files FileStore) *Service {
	if cfg.LedgerPageSize <= 0 {
		cfg.LedgerPageSize = DefaultLedgerPageSize
	}
	if cfg.CameoPageSize <= 0 {
		cfg.CameoPageSize = DefaultCameoPageSize
	}
	if cfg.MaxSelfieBytes <= 0 {
		cfg.MaxSelfieBytes = DefaultMaxSelfieBytes
	}
	if cfg.GenerationTTL <= 0 {
		cfg.GenerationTTL = DefaultGenerationTTL
	}
	return &Service{q: q, tx: tx, clock: clock, cfg: cfg, files: files}
}

// WithVendor 設定派工用的廠商 client；沒設定時 DispatchDue 不做事。
func (s *Service) WithVendor(v VendorClient) *Service {
	s.vendor = v
	return s
}

// SetUploadExpiryFault 設定 debug 用的逾期 hook，只在組裝時呼叫（比照 WithVendor）。
// hook 只在上傳或送出的訂單狀態允許時才會被呼叫；回傳 true 就把該訂單的 upload_expires_at 設為現在，
// 之後走真實的逾期釋放流程。
func (s *Service) SetUploadExpiryFault(fn func() bool) { s.uploadExpiryFault = fn }

// SetRuntimeConfig 設定執行期設定，只在組裝時呼叫（比照 SetUploadExpiryFault）；不呼叫就一律讀建立時的 Config。
func (s *Service) SetRuntimeConfig(rc RuntimeConfig) { s.rc = rc }

// BaseConfig 回傳正規化後的 Config 副本（預設值已補上），給 adapter 當執行期設定的初始值。
func (s *Service) BaseConfig() Config { return s.cfg }

func (s *Service) dailyLimit() int {
	if s.rc != nil {
		return s.rc.DailyLimit()
	}
	return s.cfg.DailyLimit
}

// initialPoints 回傳 reset 用的初始餘額覆寫值；nil 表示用 seed_balance。
func (s *Service) initialPoints() *int {
	if s.rc != nil {
		return s.rc.InitialPoints()
	}
	return nil
}

func (s *Service) generationTTL() time.Duration {
	if s.rc != nil {
		return s.rc.GenerationTTL()
	}
	return s.cfg.GenerationTTL
}

// applyUploadExpiryFault 在交易內、狀態確認可上傳之後、ExpireCameo 之前呼叫。
func (s *Service) applyUploadExpiryFault(ctx context.Context, tx Tx, id string, now time.Time) error {
	if s.uploadExpiryFault == nil || !s.uploadExpiryFault() {
		return nil
	}
	return tx.SetUploadExpiresAt(ctx, id, now)
}

// MaxSelfieBytes 是上傳自拍的大小上限。
func (s *Service) MaxSelfieBytes() int64 { return s.cfg.MaxSelfieBytes }

func (s *Service) Clips(ctx context.Context) ([]domain.Clip, error) {
	return s.q.ListClips(ctx)
}

// Points 回傳可用與占用點數；占用為 HELD 訂單的 cost 總和。
func (s *Service) Points(ctx context.Context) (domain.Points, error) {
	balance, held, err := s.q.BalanceAndHeld(ctx)
	if err != nil {
		return domain.Points{}, err
	}
	return domain.NewPoints(balance, held), nil
}

// LedgerPage 是一頁明細；NextCursor 為空字串表示沒有下一頁。
type LedgerPage struct {
	Items      []domain.LedgerEntry
	NextCursor string
}

// Ledger 回傳一頁明細。cursor 為空字串表示第一頁；無法解析回 domain.ErrInvalidCursor。
func (s *Service) Ledger(ctx context.Context, cursor string) (LedgerPage, error) {
	items, next, err := listPage(cursor, s.cfg.LedgerPageSize,
		func(after *domain.PageCursor, limit int) ([]domain.LedgerEntry, error) {
			return s.q.ListLedger(ctx, after, limit)
		},
		func(e domain.LedgerEntry) (time.Time, string) { return e.CreatedAt, e.ID })
	if err != nil {
		return LedgerPage{}, err
	}
	return LedgerPage{Items: items, NextCursor: next}, nil
}

// CameoPage 是一頁訂單；NextCursor 為空字串表示沒有下一頁。
type CameoPage struct {
	Items      []domain.Cameo
	NextCursor string
}

// Cameos 回傳一頁訂單列表（唯讀）。cursor 為空字串表示第一頁；無法解析回 domain.ErrInvalidCursor。
// 與 Ledger 相同，多查一筆來判斷是否有下一頁，所以剛好滿一頁時沒有 cursor。
func (s *Service) Cameos(ctx context.Context, cursor string) (CameoPage, error) {
	items, next, err := listPage(cursor, s.cfg.CameoPageSize,
		func(after *domain.PageCursor, limit int) ([]domain.Cameo, error) {
			return s.q.ListCameos(ctx, after, limit)
		},
		func(c domain.Cameo) (time.Time, string) { return c.CreatedAt, c.ID })
	if err != nil {
		return CameoPage{}, err
	}
	return CameoPage{Items: items, NextCursor: next}, nil
}

// listPage 是 Ledger／Cameos 共用的分頁流程：解 cursor、查 pageSize+1 筆、截斷到 pageSize、
// 有多的一筆才算出 NextCursor（所以剛好滿一頁時沒有 cursor）。
func listPage[T any](
	cursor string,
	pageSize int,
	list func(after *domain.PageCursor, limit int) ([]T, error),
	key func(T) (time.Time, string),
) ([]T, string, error) {
	var after *domain.PageCursor
	if cursor != "" {
		c, err := decodeCursor(cursor)
		if err != nil {
			return nil, "", err
		}
		after = &c
	}
	items, err := list(after, pageSize+1)
	if err != nil {
		return nil, "", err
	}
	if len(items) <= pageSize {
		return items, "", nil
	}
	items = items[:pageSize]
	createdAt, id := key(items[pageSize-1])
	return items, encodeCursor(createdAt, id), nil
}

type cursorPayload struct {
	T string `json:"t"`
	I string `json:"i"`
}

func encodeCursor(createdAt time.Time, id string) string {
	b, _ := json.Marshal(cursorPayload{T: createdAt.UTC().Format(time.RFC3339Nano), I: id})
	return base64.RawURLEncoding.EncodeToString(b)
}

func decodeCursor(s string) (domain.PageCursor, error) {
	raw, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil {
		return domain.PageCursor{}, domain.ErrInvalidCursor
	}
	// json.Unmarshal 會把非法 UTF-8 換成 U+FFFD 而不報錯，所以先檢查原始位元組。
	if !utf8.Valid(raw) {
		return domain.PageCursor{}, domain.ErrInvalidCursor
	}
	var p cursorPayload
	if err := json.Unmarshal(raw, &p); err != nil || p.I == "" || strings.ContainsRune(p.I, 0) {
		return domain.PageCursor{}, domain.ErrInvalidCursor
	}
	t, err := time.Parse(time.RFC3339Nano, p.T)
	if err != nil {
		return domain.PageCursor{}, domain.ErrInvalidCursor
	}
	return domain.PageCursor{CreatedAt: t, ID: p.I}, nil
}
