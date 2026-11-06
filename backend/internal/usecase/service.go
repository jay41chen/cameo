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
}

type Service struct {
	q     Queries
	tx    TxRunner
	clock Clock
	cfg   Config
}

func NewService(q Queries, tx TxRunner, clock Clock, cfg Config) *Service {
	if cfg.LedgerPageSize <= 0 {
		cfg.LedgerPageSize = DefaultLedgerPageSize
	}
	return &Service{q: q, tx: tx, clock: clock, cfg: cfg}
}

func (s *Service) Clips(ctx context.Context) ([]domain.Clip, error) {
	return s.q.ListClips(ctx)
}

// Points 回傳可用與占用點數；占用為 HELD 訂單的 cost 總和。
func (s *Service) Points(ctx context.Context) (domain.Points, error) {
	balance, err := s.q.Balance(ctx)
	if err != nil {
		return domain.Points{}, err
	}
	held, err := s.q.HeldPoints(ctx)
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
