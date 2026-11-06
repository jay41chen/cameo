package usecase

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"cameo/backend/internal/domain"
)

// dispatchBatch 是 DispatchDue 每次掃描最多處理的訂單數。
const dispatchBatch = 20

// 廠商回呼的狀態。
const (
	VendorSucceeded = "SUCCEEDED"
	VendorFailed    = "FAILED"
)

// 鎖序一律：LockAccount → 訂單 CameoForUpdate。下面所有退款與回呼交易都照這個順序。

// refund 是共用的失敗流程，必須在已鎖住帳戶與訂單的交易內呼叫：
// 餘額加回快照 cost、寫 REFUND 明細、訂單轉 FAILED／REFUNDED 並寫失敗原因。
func refund(ctx context.Context, tx Tx, c domain.Cameo, reason string, now time.Time) error {
	if err := tx.CreditAccount(ctx, c.Clip.Cost); err != nil {
		return err
	}
	cameoID := c.ID
	if err := tx.InsertLedger(ctx, domain.LedgerEntry{
		Type: domain.LedgerRefund, Amount: c.Clip.Cost, CameoID: &cameoID,
		ClipTitle: c.Clip.Title, CreatedAt: now,
	}); err != nil {
		return err
	}
	return tx.MarkFailed(ctx, c.ID, reason, now)
}

// inGeneration 回傳訂單是否在 QUEUED 或 GENERATING（廠商還可能回覆的狀態）。
func inGeneration(c domain.Cameo) bool {
	return c.Status == domain.StatusQueued || c.Status == domain.StatusGenerating
}

// DispatchDue 把 QUEUED 且未逾期的訂單交給廠商（created_at 由舊到新，每次最多 20 筆）。
// HTTP 呼叫在資料庫交易外；廠商接受後才開交易，鎖序 LockAccount → CameoForUpdate，
// 仍是 QUEUED 才轉 GENERATING。Dispatch 失敗只記 log，下次掃描重試（廠商以 jobID 去重）。
// 單筆失敗不影響其他筆。回傳本次成功轉為 GENERATING 的訂單 id。
func (s *Service) DispatchDue(ctx context.Context) ([]string, error) {
	if s.vendor == nil {
		return nil, nil
	}
	ids, err := s.q.DispatchableIDs(ctx, s.clock.Now(), dispatchBatch)
	if err != nil {
		return nil, err
	}
	var moved []string
	for _, id := range ids {
		if err := s.vendor.Dispatch(ctx, id); err != nil {
			slog.Warn("vendor dispatch failed; will retry", "cameo_id", id, "err", err)
			continue
		}
		changed := false
		err := s.tx.Run(ctx, func(tx Tx) error {
			if _, err := tx.LockAccount(ctx); err != nil {
				return err
			}
			c, found, err := tx.CameoForUpdate(ctx, id)
			if err != nil {
				return err
			}
			if !found || c.Status != domain.StatusQueued {
				return nil
			}
			changed, err = tx.MarkGenerating(ctx, id, s.clock.Now())
			return err
		})
		if err != nil {
			slog.Error("mark generating failed", "cameo_id", id, "err", err)
			continue
		}
		if changed {
			moved = append(moved, id)
		}
	}
	return moved, nil
}

// FailTimedOut 把 QUEUED／GENERATING 且生成期限已到（<= now）的訂單轉為 FAILED／REFUNDED，
// 原因 TIMEOUT。每筆獨立交易，鎖序 LockAccount → CameoForUpdate，交易內重查狀態與期限。
// 交易提交後刪自拍。單筆失敗不影響其他筆；回傳本次轉換的訂單 id，以及失敗筆數的合併錯誤。
func (s *Service) FailTimedOut(ctx context.Context) ([]string, error) {
	ids, err := s.q.TimedOutIDs(ctx, s.clock.Now())
	if err != nil {
		return nil, err
	}
	var (
		failed []string
		errs   []error
	)
	for _, id := range ids {
		done := false
		err := s.tx.Run(ctx, func(tx Tx) error {
			done = false
			if _, err := tx.LockAccount(ctx); err != nil {
				return err
			}
			c, found, err := tx.CameoForUpdate(ctx, id)
			if err != nil {
				return err
			}
			now := s.clock.Now()
			if !found || !inGeneration(c) || c.GenerationExpiresAt == nil || c.GenerationExpiresAt.After(now) {
				return nil
			}
			if err := refund(ctx, tx, c, domain.FailureTimeout, now); err != nil {
				return err
			}
			done = true
			return nil
		})
		if err != nil {
			errs = append(errs, fmt.Errorf("fail timed out %s: %w", id, err))
			continue
		}
		if done {
			failed = append(failed, id)
			s.deleteSelfies(id)
		}
	}
	return failed, errors.Join(errs...)
}

// HandleVendorCallback 處理已驗簽且格式正確的廠商回呼，單一交易：
// LockAccount → InsertVendorEvent（event_id 重複就是重複回呼，直接成功）→ CameoForUpdate。
//   - 找不到訂單：回 domain.ErrCameoNotFound，交易回滾，事件不留。
//   - QUEUED／GENERATING：SUCCEEDED 轉 SUCCEEDED 並存 video_url；FAILED 走共用失敗流程
//     （reason 是 CONTENT_REJECTED 照用，其他一律 VENDOR_FAILED）。接受 QUEUED 是為了回呼先於「接受」寫入的競爭。
//   - 其他狀態（終態、AWAITING_UPLOAD、UPLOADED）：不改訂單，記 log，事件留存，回成功。
//
// 終態以資料庫狀態為準，回呼內不比對期限（先到的為準）。進入終態的交易提交後刪自拍。
func (s *Service) HandleVendorCallback(ctx context.Context, ev VendorEvent) error {
	if ev.Status != VendorSucceeded && ev.Status != VendorFailed {
		return fmt.Errorf("vendor callback: unsupported status %q", ev.Status)
	}
	if !domain.ValidText(ev.JobID) {
		return domain.ErrCameoNotFound
	}
	terminal := false
	err := s.tx.Run(ctx, func(tx Tx) error {
		terminal = false
		if _, err := tx.LockAccount(ctx); err != nil {
			return err
		}
		now := s.clock.Now()
		ev.ReceivedAt = now
		inserted, err := tx.InsertVendorEvent(ctx, ev)
		if err != nil {
			return err
		}
		if !inserted {
			return nil
		}
		c, found, err := tx.CameoForUpdate(ctx, ev.JobID)
		if err != nil {
			return err
		}
		if !found {
			return domain.ErrCameoNotFound
		}
		if !inGeneration(c) {
			slog.Warn("vendor callback ignored: cameo not in generation",
				"event_id", ev.EventID, "job_id", ev.JobID, "callback_status", ev.Status, "cameo_status", c.Status)
			return nil
		}
		if ev.Status == VendorSucceeded {
			if err := tx.MarkSucceeded(ctx, c.ID, ev.VideoURL, now); err != nil {
				return err
			}
		} else {
			reason := domain.FailureVendorFailed
			if ev.Reason != nil && *ev.Reason == domain.FailureContentRejected {
				reason = domain.FailureContentRejected
			}
			if err := refund(ctx, tx, c, reason, now); err != nil {
				return err
			}
		}
		terminal = true
		return nil
	})
	if err != nil {
		return err
	}
	if terminal {
		s.deleteSelfies(ev.JobID)
	}
	return nil
}
