package usecase

import (
	"context"
	"crypto/rand"
	"log/slog"

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

// CameoVideo 檢查訂單影片是否可播放：不存在回 domain.ErrCameoNotFound，
// 非 SUCCEEDED 回 domain.ErrVideoNotReady。影片位元組由 HTTP 層提供。
func (s *Service) CameoVideo(ctx context.Context, id string) error {
	c, err := s.Cameo(ctx, id)
	if err != nil {
		return err
	}
	if !c.VideoAvailable() {
		return domain.ErrVideoNotReady
	}
	return nil
}

// ShareCameo 建立分享：只有 SUCCEEDED 可分享（否則 domain.ErrNotShareable）。
// 已在分享中就回原訂單，不改 updated_at；否則發新 token。在交易內鎖列，併發的 PUT 只會產生一個 token。
func (s *Service) ShareCameo(ctx context.Context, id string) (domain.Cameo, error) {
	if !domain.ValidText(id) {
		return domain.Cameo{}, domain.ErrCameoNotFound
	}
	var out domain.Cameo
	err := s.tx.Run(ctx, func(tx Tx) error {
		c, found, err := tx.CameoForUpdate(ctx, id)
		if err != nil {
			return err
		}
		if !found {
			return domain.ErrCameoNotFound
		}
		if !c.VideoAvailable() {
			return domain.ErrNotShareable
		}
		out = c
		if c.ShareToken != nil {
			return nil
		}
		token := rand.Text()
		now := s.clock.Now()
		if err := tx.SetShareToken(ctx, id, &token, now); err != nil {
			return err
		}
		out.ShareToken, out.UpdatedAt = &token, now
		return nil
	})
	if err != nil {
		return domain.Cameo{}, err
	}
	return out, nil
}

// UnshareCameo 取消分享，冪等：未分享（含非 SUCCEEDED）時不寫入。訂單不存在回 domain.ErrCameoNotFound。
func (s *Service) UnshareCameo(ctx context.Context, id string) error {
	if !domain.ValidText(id) {
		return domain.ErrCameoNotFound
	}
	return s.tx.Run(ctx, func(tx Tx) error {
		c, found, err := tx.CameoForUpdate(ctx, id)
		if err != nil {
			return err
		}
		if !found {
			return domain.ErrCameoNotFound
		}
		if c.ShareToken == nil {
			return nil
		}
		return tx.SetShareToken(ctx, id, nil, s.clock.Now())
	})
}

// SharedVideoAvailable 回傳分享 token 是否有效（對應 SUCCEEDED 且仍在分享中的訂單）。
func (s *Service) SharedVideoAvailable(ctx context.Context, token string) (bool, error) {
	if !domain.ValidText(token) {
		return false, nil
	}
	return s.q.ShareTokenExists(ctx, token)
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

// ExpireDue 釋放已逾期的訂單（不寫明細），可重複執行；回傳本次轉換的訂單 id。
// 交易 commit 後才刪除自拍檔；刪除失敗只記 log。
func (s *Service) ExpireDue(ctx context.Context) ([]string, error) {
	var ids []string
	err := s.tx.Run(ctx, func(tx Tx) error {
		var err error
		ids, err = tx.ExpireDue(ctx, s.clock.Now())
		return err
	})
	if err != nil {
		return nil, err
	}
	s.deleteSelfies(ids...)
	return ids, nil
}

// deleteSelfies 刪除訂單的自拍檔；失敗只記 log，不影響呼叫端。
func (s *Service) deleteSelfies(ids ...string) {
	for _, id := range ids {
		if err := s.files.Delete(id); err != nil {
			slog.Error("delete selfie failed", "cameo_id", id, "err", err)
		}
	}
}

// ResetAll 清空訂單、點數明細與廠商事件，餘額回到 seed_balance（供 POST /debug/reset）。
// 交易內先 LockAccount；commit 後才刪自拍檔，刪除失敗只記 log。
func (s *Service) ResetAll(ctx context.Context) error {
	var ids []string
	err := s.tx.Run(ctx, func(tx Tx) error {
		if _, err := tx.LockAccount(ctx); err != nil {
			return err
		}
		var err error
		ids, err = tx.ResetData(ctx)
		return err
	})
	if err != nil {
		return err
	}
	s.deleteSelfies(ids...)
	return nil
}
