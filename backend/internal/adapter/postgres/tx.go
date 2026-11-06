package postgres

import (
	"context"
	"errors"
	"time"

	"cameo/backend/internal/domain"
	"cameo/backend/internal/usecase"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// TxRunner 實作 usecase.TxRunner。
type TxRunner struct {
	pool *pgxpool.Pool
}

func NewTxRunner(pool *pgxpool.Pool) *TxRunner {
	return &TxRunner{pool: pool}
}

func (r *TxRunner) Run(ctx context.Context, fn func(tx usecase.Tx) error) error {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck
	if err := fn(&txStore{store{db: tx}}); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// txStore 實作 usecase.Tx。
type txStore struct {
	store
}

func (t *txStore) LockAccount(ctx context.Context) (int, error) {
	var balance int
	err := t.db.QueryRow(ctx, `SELECT balance FROM points_account WHERE id = $1 FOR UPDATE`, userID).Scan(&balance)
	return balance, err
}

func (t *txStore) CameoByKey(ctx context.Context, key string) (domain.Cameo, bool, error) {
	c, err := scanCameo(t.db.QueryRow(ctx, cameoSelect+` WHERE c.user_id = $1 AND c.idempotency_key = $2`, userID, key))
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.Cameo{}, false, nil
	}
	return c, err == nil, err
}

func (t *txStore) Clip(ctx context.Context, id string) (domain.Clip, bool, error) {
	var c domain.Clip
	err := t.db.QueryRow(ctx, `SELECT id, title, cost FROM clips WHERE id = $1`, id).Scan(&c.ID, &c.Title, &c.Cost)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.Clip{}, false, nil
	}
	return c, err == nil, err
}

func (t *txStore) InsertCameo(ctx context.Context, c domain.Cameo) (string, error) {
	var id string
	err := t.db.QueryRow(ctx, `INSERT INTO cameos
		(user_id, idempotency_key, clip_id, cost, status, points_state, failure_reason, upload_expires_at, created_at, updated_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10) RETURNING id`,
		userID, c.IdempotencyKey, c.Clip.ID, c.Clip.Cost, c.Status, c.PointsState, c.FailureReason,
		c.UploadExpiresAt, c.CreatedAt, c.UpdatedAt).Scan(&id)
	return id, err
}

// expiredPredicate 是逾期的唯一判斷規則：ExpireDue 與 ExpireCameo 共用。$1 是 now。
const expiredPredicate = `status IN ('AWAITING_UPLOAD', 'UPLOADED') AND upload_expires_at <= $1`

func (t *txStore) ExpireDue(ctx context.Context, now time.Time) ([]string, error) {
	rows, err := t.db.Query(ctx, `UPDATE cameos SET status = 'EXPIRED', points_state = 'RELEASED', updated_at = $1
		WHERE user_id = $2 AND `+expiredPredicate+` RETURNING id`, now, userID)
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

func (t *txStore) CameoForUpdate(ctx context.Context, id string) (domain.Cameo, bool, error) {
	c, err := scanCameo(t.db.QueryRow(ctx, cameoSelect+` WHERE c.user_id = $1 AND c.id = $2 FOR UPDATE OF c`, userID, id))
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.Cameo{}, false, nil
	}
	return c, err == nil, err
}

func (t *txStore) ExpireCameo(ctx context.Context, id string, now time.Time) (bool, error) {
	tag, err := t.db.Exec(ctx, `UPDATE cameos SET status = 'EXPIRED', points_state = 'RELEASED', updated_at = $1
		WHERE user_id = $2 AND id = $3 AND `+expiredPredicate, now, userID, id)
	return tag.RowsAffected() == 1, err
}

func (t *txStore) MarkUploaded(ctx context.Context, id string, now time.Time) error {
	_, err := t.db.Exec(ctx, `UPDATE cameos SET status = 'UPLOADED', updated_at = $1 WHERE user_id = $2 AND id = $3`, now, userID, id)
	return err
}

func (t *txStore) DebitAccount(ctx context.Context, amount int) error {
	_, err := t.db.Exec(ctx, `UPDATE points_account SET balance = balance - $1 WHERE id = $2`, amount, userID)
	return err
}

func (t *txStore) InsertLedger(ctx context.Context, e domain.LedgerEntry) error {
	_, err := t.db.Exec(ctx, `INSERT INTO ledger_entries (type, amount, cameo_id, clip_title, created_at)
		VALUES ($1, $2, $3, $4, $5)`, string(e.Type), e.Amount, e.CameoID, e.ClipTitle, e.CreatedAt)
	return err
}

func (t *txStore) MarkQueued(ctx context.Context, id string, now time.Time) error {
	_, err := t.db.Exec(ctx, `UPDATE cameos SET status = 'QUEUED', points_state = 'CHARGED', updated_at = $1 WHERE user_id = $2 AND id = $3`, now, userID, id)
	return err
}
