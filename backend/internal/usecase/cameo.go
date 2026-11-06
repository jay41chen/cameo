package usecase

import (
	"context"

	"cameo/backend/internal/domain"
)

// CreateResult 是建單結果；Created 為 false 表示同一冪等鍵的重送。
type CreateResult struct {
	Cameo   domain.Cameo
	Created bool
}

// CreateCameo 在同一交易內建單。先鎖 points_account 讓同一使用者的建單序列化，
// 再依序檢查：冪等鍵、片段、當日額度、可用點數。
func (s *Service) CreateCameo(ctx context.Context, key, clipID string) (CreateResult, error) {
	var res CreateResult
	err := s.tx.Run(ctx, func(tx Tx) error {
		balance, err := tx.LockAccount(ctx)
		if err != nil {
			return err
		}
		existing, found, err := tx.CameoByKey(ctx, key)
		if err != nil {
			return err
		}
		if found {
			if existing.Clip.ID != clipID {
				return domain.ErrIdempotencyConflict
			}
			res = CreateResult{Cameo: existing}
			return nil
		}
		if !domain.ValidText(clipID) {
			return domain.ErrClipNotFound
		}
		clip, found, err := tx.Clip(ctx, clipID)
		if err != nil {
			return err
		}
		if !found {
			return domain.ErrClipNotFound
		}
		now := s.clock.Now()
		from, to := domain.TaipeiDayBounds(now)
		used, err := tx.QuotaUsed(ctx, from, to)
		if err != nil {
			return err
		}
		if used >= s.cfg.DailyLimit {
			return domain.ErrDailyLimitReached
		}
		held, err := tx.HeldPoints(ctx)
		if err != nil {
			return err
		}
		if balance-held < clip.Cost {
			return domain.ErrInsufficientPoints
		}
		c := domain.Cameo{
			IdempotencyKey:  key,
			Clip:            clip,
			Status:          domain.StatusAwaitingUpload,
			PointsState:     domain.PointsHeld,
			UploadExpiresAt: now.Add(s.cfg.UploadTTL),
			CreatedAt:       now,
			UpdatedAt:       now,
		}
		id, err := tx.InsertCameo(ctx, c)
		if err != nil {
			return err
		}
		c.ID = id
		res = CreateResult{Cameo: c, Created: true}
		return nil
	})
	if err != nil {
		return CreateResult{}, err
	}
	return res, nil
}

// Cameo 回傳單一訂單；不存在回 domain.ErrCameoNotFound。
func (s *Service) Cameo(ctx context.Context, id string) (domain.Cameo, error) {
	if !domain.ValidText(id) {
		return domain.Cameo{}, domain.ErrCameoNotFound
	}
	return s.q.CameoByID(ctx, id)
}

// Quota 回傳當日額度，由訂單計算。
func (s *Service) Quota(ctx context.Context) (domain.Quota, error) {
	from, to := domain.TaipeiDayBounds(s.clock.Now())
	used, err := s.q.QuotaUsed(ctx, from, to)
	if err != nil {
		return domain.Quota{}, err
	}
	return domain.NewQuota(s.cfg.DailyLimit, used, to), nil
}

// ExpireDue 釋放已逾期的訂單（不寫明細），可重複執行；回傳本次轉換筆數。
func (s *Service) ExpireDue(ctx context.Context) (int, error) {
	var n int
	err := s.tx.Run(ctx, func(tx Tx) error {
		var err error
		n, err = tx.ExpireDue(ctx, s.clock.Now())
		return err
	})
	return n, err
}
