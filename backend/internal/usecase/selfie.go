package usecase

import (
	"bytes"
	"context"
	"errors"
	"io"
	"log/slog"

	"cameo/backend/internal/domain"
)

// BodyReadError 表示讀取上傳內容時失敗（client 斷線、內容不足、超過大小上限等）。
// Err 保留原始錯誤，呼叫端可用 errors.As 辨識例如 *http.MaxBytesError。
type BodyReadError struct{ Err error }

func (e *BodyReadError) Error() string { return "read upload body: " + e.Err.Error() }
func (e *BodyReadError) Unwrap() error { return e.Err }

// bodyReader 包住上傳內容並記錄讀取錯誤（EOF 除外），
// 讓呼叫端分得出是讀 body 失敗還是寫檔失敗。ctx 取消由 FileStore.WriteTemp 負責中止
// （filestore 的 ctxReader），UploadSelfie 再把它歸類為 BodyReadError，這裡不重複檢查。
type bodyReader struct {
	r   io.Reader
	err error
}

func (b *bodyReader) Read(p []byte) (int, error) {
	n, err := b.r.Read(p)
	if err != nil && err != io.EOF {
		b.err = err
	}
	return n, err
}

// UploadSelfie 儲存自拍（只收 JPEG，只看內容前 3 bytes）。
// 順序：先查訂單擋掉明顯不能上傳的（不讀 body）→ 串流寫暫存檔 → 交易內鎖列、重查狀態與期限、
// 更新狀態並把暫存檔移到正式位置。程序仍在執行時，所有錯誤路徑都會刪除暫存檔；
// 程序中止（crash、kill）留下的暫存檔由 filestore.New 在下次啟動時清除。
// 回傳的錯誤：domain.ErrCameoNotFound、ErrUploadExpired、ErrInvalidState、ErrInvalidSelfie，
// 或 *BodyReadError。
func (s *Service) UploadSelfie(ctx context.Context, id string, body io.Reader) (domain.Cameo, error) {
	c, err := s.Cameo(ctx, id)
	if err != nil {
		return domain.Cameo{}, err
	}
	switch c.Status {
	case domain.StatusAwaitingUpload, domain.StatusUploaded:
	case domain.StatusExpired:
		return domain.Cameo{}, domain.ErrUploadExpired
	default:
		return domain.Cameo{}, domain.ErrInvalidState
	}

	br := &bodyReader{r: body}
	head := make([]byte, 3)
	n := 0
	for n < len(head) {
		m, err := br.Read(head[n:])
		n += m
		if err == io.EOF {
			break
		}
		if err != nil {
			return domain.Cameo{}, &BodyReadError{Err: err}
		}
	}
	if !domain.IsJPEGPrefix(head[:n]) {
		return domain.Cameo{}, domain.ErrInvalidSelfie
	}
	temp, err := s.files.WriteTemp(ctx, io.MultiReader(bytes.NewReader(head), br))
	if err != nil {
		if br.err != nil {
			return domain.Cameo{}, &BodyReadError{Err: br.err}
		}
		// 讀 body 期間 ctx 取消（client 斷線）一律視為讀取失敗。
		if ctxErr := ctx.Err(); ctxErr != nil && errors.Is(err, ctxErr) {
			return domain.Cameo{}, &BodyReadError{Err: err}
		}
		return domain.Cameo{}, err
	}
	committed := false
	defer func() {
		if !committed {
			if err := s.files.Discard(temp); err != nil {
				slog.Error("discard temp selfie failed", "temp", temp, "err", err)
			}
		}
	}()

	var (
		res     domain.Cameo
		expired bool
	)
	err = s.tx.Run(ctx, func(tx Tx) error {
		cur, found, err := tx.CameoForUpdate(ctx, id)
		if err != nil {
			return err
		}
		if !found {
			return domain.ErrCameoNotFound
		}
		now := s.clock.Now()
		switch cur.Status {
		case domain.StatusAwaitingUpload, domain.StatusUploaded:
		case domain.StatusExpired:
			expired = true
			return nil
		default:
			return domain.ErrInvalidState
		}
		if err := s.applyUploadExpiryFault(ctx, tx, id, now); err != nil {
			return err
		}
		// 逾期要讓釋放真的 commit，所以這裡回 nil，交易外再回 410。
		gone, err := tx.ExpireCameo(ctx, id, now)
		if err != nil {
			return err
		}
		if gone {
			expired = true
			return nil
		}
		if err := tx.MarkUploaded(ctx, id, now); err != nil {
			return err
		}
		if err := s.files.Commit(temp, id); err != nil {
			return err
		}
		committed = true
		cur.Status = domain.StatusUploaded
		cur.UpdatedAt = now
		res = cur
		return nil
	})
	if err != nil {
		return domain.Cameo{}, err
	}
	if expired {
		// 逾期（含剛被這次交易釋放）：確保檔案不留存。
		s.deleteSelfies(id)
		return domain.Cameo{}, domain.ErrUploadExpired
	}
	return res, nil
}

// SubmitCameo 送出訂單：同一交易內先 LockAccount、再鎖訂單；先判斷期限、再判斷是否已上傳，
// 然後扣款、寫 CHARGE 明細、轉 QUEUED／CHARGED 並寫入 generation_expires_at（now + GenerationTTL）。已是 QUEUED 之後的狀態則原樣回傳，不重複扣款。
// 回傳的錯誤：domain.ErrCameoNotFound、ErrUploadExpired、ErrSelfieNotUploaded。
func (s *Service) SubmitCameo(ctx context.Context, id string) (domain.Cameo, error) {
	if !domain.ValidText(id) {
		return domain.Cameo{}, domain.ErrCameoNotFound
	}
	var (
		res     domain.Cameo
		expired bool
		outErr  error
	)
	err := s.tx.Run(ctx, func(tx Tx) error {
		if _, err := tx.LockAccount(ctx); err != nil {
			return err
		}
		c, found, err := tx.CameoForUpdate(ctx, id)
		if err != nil {
			return err
		}
		if !found {
			return domain.ErrCameoNotFound
		}
		now := s.clock.Now()
		switch c.Status {
		case domain.StatusExpired:
			expired = true
			return nil
		case domain.StatusAwaitingUpload, domain.StatusUploaded:
		default: // QUEUED、GENERATING、SUCCEEDED、FAILED：冪等，不重複扣款。
			res = c
			return nil
		}
		if err := s.applyUploadExpiryFault(ctx, tx, id, now); err != nil {
			return err
		}
		gone, err := tx.ExpireCameo(ctx, id, now)
		if err != nil {
			return err
		}
		if gone {
			expired = true
			return nil
		}
		if c.Status == domain.StatusAwaitingUpload {
			outErr = domain.ErrSelfieNotUploaded
			return nil
		}
		if err := tx.DebitAccount(ctx, c.Clip.Cost); err != nil {
			return err
		}
		cameoID := c.ID
		if err := tx.InsertLedger(ctx, domain.LedgerEntry{
			Type: domain.LedgerCharge, Amount: c.Clip.Cost, CameoID: &cameoID,
			ClipTitle: c.Clip.Title, CreatedAt: now,
		}); err != nil {
			return err
		}
		expiresAt := now.Add(s.cfg.GenerationTTL)
		if err := tx.MarkQueued(ctx, id, now, expiresAt); err != nil {
			return err
		}
		c.Status, c.PointsState, c.UpdatedAt = domain.StatusQueued, domain.PointsCharged, now
		c.GenerationExpiresAt = &expiresAt
		res = c
		return nil
	})
	if err != nil {
		return domain.Cameo{}, err
	}
	if expired {
		s.deleteSelfies(id)
		return domain.Cameo{}, domain.ErrUploadExpired
	}
	if outErr != nil {
		return domain.Cameo{}, outErr
	}
	return res, nil
}
