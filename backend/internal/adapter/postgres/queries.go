package postgres

import (
	"context"
	"errors"
	"time"

	"cameo/backend/internal/domain"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

// userID 是唯一的測試使用者（points_account.id）。
const userID = 1

// dbtx 是連線池與交易共同的查詢介面。
type dbtx interface {
	Query(ctx context.Context, sql string, args ...any) (pgx.Rows, error)
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
	Exec(ctx context.Context, sql string, args ...any) (pgconn.CommandTag, error)
}

// store 放連線池與交易共用的 SQL。
type store struct {
	db dbtx
}

// Queries 實作 usecase.Queries。
type Queries struct {
	store
}

func NewQueries(pool *pgxpool.Pool) *Queries {
	return &Queries{store{db: pool}}
}

const cameoSelect = `SELECT c.id, c.idempotency_key, c.clip_id, cl.title, c.cost, c.status, c.points_state,
	c.failure_reason, c.upload_expires_at, c.generation_expires_at, c.video_url, c.created_at, c.updated_at
	FROM cameos c JOIN clips cl ON cl.id = c.clip_id`

func scanCameo(row pgx.Row) (domain.Cameo, error) {
	var c domain.Cameo
	err := row.Scan(&c.ID, &c.IdempotencyKey, &c.Clip.ID, &c.Clip.Title, &c.Clip.Cost, &c.Status,
		&c.PointsState, &c.FailureReason, &c.UploadExpiresAt, &c.GenerationExpiresAt, &c.VideoURL, &c.CreatedAt, &c.UpdatedAt)
	return c, err
}

func (s store) HeldPoints(ctx context.Context) (int, error) {
	var held int
	err := s.db.QueryRow(ctx,
		`SELECT COALESCE(SUM(cost), 0)::int FROM cameos WHERE user_id = $1 AND points_state = 'HELD'`, userID).Scan(&held)
	return held, err
}

// QuotaUsed 不計 EXPIRED，也不計 FAILED 且原因不是 CONTENT_REJECTED 的訂單（額度已退還）。
func (s store) QuotaUsed(ctx context.Context, from, to time.Time) (int, error) {
	var n int
	err := s.db.QueryRow(ctx, `SELECT count(*)::int FROM cameos
		WHERE user_id = $1 AND created_at >= $2 AND created_at < $3
		  AND status <> 'EXPIRED'
		  AND NOT (status = 'FAILED' AND failure_reason IS DISTINCT FROM 'CONTENT_REJECTED')`,
		userID, from, to).Scan(&n)
	return n, err
}

func (q *Queries) CameoByID(ctx context.Context, id string) (domain.Cameo, error) {
	c, err := scanCameo(q.db.QueryRow(ctx, cameoSelect+` WHERE c.user_id = $1 AND c.id = $2`, userID, id))
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.Cameo{}, domain.ErrCameoNotFound
	}
	return c, err
}

func (q *Queries) ListClips(ctx context.Context) ([]domain.Clip, error) {
	rows, err := q.db.Query(ctx, `SELECT id, title, cost FROM clips ORDER BY sort_order, id`)
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

// BalanceAndHeld 用單一 SQL 同時讀餘額與占用點數，兩者來自同一個快照。
func (q *Queries) BalanceAndHeld(ctx context.Context) (balance, held int, err error) {
	err = q.db.QueryRow(ctx, `SELECT a.balance,
		COALESCE((SELECT SUM(cost) FROM cameos WHERE user_id = a.id AND points_state = 'HELD'), 0)::int
		FROM points_account a WHERE a.id = $1`, userID).Scan(&balance, &held)
	return balance, held, err
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
	rows, err := q.db.Query(ctx, query, args...)
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

// DispatchableIDs 回傳 QUEUED 且生成期限尚未到（generation_expires_at > now）的訂單 id，
// created_at 由舊到新，最多 limit 筆。
func (q *Queries) DispatchableIDs(ctx context.Context, now time.Time, limit int) ([]string, error) {
	return q.ids(ctx, `SELECT id FROM cameos WHERE user_id = $1 AND status = 'QUEUED' AND generation_expires_at > $2
		ORDER BY created_at, id LIMIT $3`, userID, now, limit)
}

// timedOutPredicate 是生成逾時的判斷（$2 是 now）；交易內重查時 usecase 用同一條規則（期限 <= now）。
const timedOutPredicate = `status IN ('QUEUED', 'GENERATING') AND generation_expires_at <= $2`

// TimedOutIDs 回傳 QUEUED／GENERATING 且生成期限已到（generation_expires_at <= now）的訂單 id。
func (q *Queries) TimedOutIDs(ctx context.Context, now time.Time) ([]string, error) {
	return q.ids(ctx, `SELECT id FROM cameos WHERE user_id = $1 AND `+timedOutPredicate+` ORDER BY created_at, id`, userID, now)
}

func (q *Queries) ids(ctx context.Context, sql string, args ...any) ([]string, error) {
	rows, err := q.db.Query(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var ids []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}
