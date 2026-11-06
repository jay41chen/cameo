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

// Config 是業務設定，由 cmd 從環境變數讀入。
type Config struct {
	LedgerPageSize int
	DailyLimit     int
	UploadTTL      time.Duration
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
}

func NewService(q Queries, tx TxRunner, clock Clock, cfg Config, files FileStore) *Service {
	if cfg.LedgerPageSize <= 0 {
		cfg.LedgerPageSize = DefaultLedgerPageSize
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
	var after *domain.LedgerCursor
	if cursor != "" {
		c, err := decodeCursor(cursor)
		if err != nil {
			return LedgerPage{}, err
		}
		after = &c
	}
	items, err := s.q.ListLedger(ctx, after, s.cfg.LedgerPageSize+1)
	if err != nil {
		return LedgerPage{}, err
	}
	page := LedgerPage{Items: items}
	if len(items) > s.cfg.LedgerPageSize {
		page.Items = items[:s.cfg.LedgerPageSize]
		page.NextCursor = encodeCursor(page.Items[s.cfg.LedgerPageSize-1])
	}
	return page, nil
}

type cursorPayload struct {
	T string `json:"t"`
	I string `json:"i"`
}

func encodeCursor(e domain.LedgerEntry) string {
	b, _ := json.Marshal(cursorPayload{T: e.CreatedAt.UTC().Format(time.RFC3339Nano), I: e.ID})
	return base64.RawURLEncoding.EncodeToString(b)
}

func decodeCursor(s string) (domain.LedgerCursor, error) {
	raw, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil {
		return domain.LedgerCursor{}, domain.ErrInvalidCursor
	}
	// json.Unmarshal 會把非法 UTF-8 換成 U+FFFD 而不報錯，所以先檢查原始位元組。
	if !utf8.Valid(raw) {
		return domain.LedgerCursor{}, domain.ErrInvalidCursor
	}
	var p cursorPayload
	if err := json.Unmarshal(raw, &p); err != nil || p.I == "" || strings.ContainsRune(p.I, 0) {
		return domain.LedgerCursor{}, domain.ErrInvalidCursor
	}
	t, err := time.Parse(time.RFC3339Nano, p.T)
	if err != nil {
		return domain.LedgerCursor{}, domain.ErrInvalidCursor
	}
	return domain.LedgerCursor{CreatedAt: t, ID: p.I}, nil
}
