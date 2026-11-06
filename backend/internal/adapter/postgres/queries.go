package postgres

import (
	"context"

	"cameo/backend/internal/domain"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Queries 實作 usecase.Queries。
type Queries struct {
	pool *pgxpool.Pool
}

func NewQueries(pool *pgxpool.Pool) *Queries {
	return &Queries{pool: pool}
}

func (q *Queries) ListClips(ctx context.Context) ([]domain.Clip, error) {
	rows, err := q.pool.Query(ctx, `SELECT id, title, cost FROM clips ORDER BY sort_order, id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	clips := []domain.Clip{}
	for rows.Next() {
		var c domain.Clip
		if err := rows.Scan(&c.ID, &c.Title, &c.Cost); err != nil {
			return nil, err
		}
		clips = append(clips, c)
	}
	return clips, rows.Err()
}

func (q *Queries) Balance(ctx context.Context) (int, error) {
	var balance int
	err := q.pool.QueryRow(ctx, `SELECT balance FROM points_account WHERE id = 1`).Scan(&balance)
	return balance, err
}

func (q *Queries) ListLedger(ctx context.Context, after *domain.LedgerCursor, limit int) ([]domain.LedgerEntry, error) {
	const base = `SELECT id, type, amount, cameo_id, clip_title, created_at FROM ledger_entries`
	const order = ` ORDER BY created_at DESC, id DESC LIMIT `
	var (
		query string
		args  []any
	)
	if after == nil {
		query = base + order + `$1`
		args = []any{limit}
	} else {
		query = base + ` WHERE (created_at, id) < ($1, $2)` + order + `$3`
		args = []any{after.CreatedAt, after.ID, limit}
	}
	rows, err := q.pool.Query(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	entries := []domain.LedgerEntry{}
	for rows.Next() {
		var e domain.LedgerEntry
		var typ string
		if err := rows.Scan(&e.ID, &typ, &e.Amount, &e.CameoID, &e.ClipTitle, &e.CreatedAt); err != nil {
			return nil, err
		}
		e.Type = domain.LedgerType(typ)
		entries = append(entries, e)
	}
	return entries, rows.Err()
}
