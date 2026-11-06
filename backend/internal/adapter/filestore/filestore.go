// Package filestore 把自拍存在本機目錄。暫存檔與正式檔在同一個目錄（同一個檔案系統），
// 所以 Commit 的 rename 是原子的。
package filestore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

const (
	tempPrefix = ".tmp-"
	fileSuffix = ".jpg"
)

// safeID 只允許英數與連字號（訂單 id 是 UUID），避免路徑穿越。
var safeID = regexp.MustCompile(`^[A-Za-z0-9-]{1,128}$`)

// Local 實作 usecase.FileStore。
type Local struct {
	dir string
}

// New 建立目錄（若不存在）並回傳 Local。
//
// 啟動時會刪除目錄內程序中止（crash、kill）留下的 ".tmp-*" 暫存檔，只動這個前綴，不碰正式檔。
// 前提：同一個目錄同一時間只有一個實例；若有第二個實例正在寫入，它的暫存檔也會被刪掉。
// 單一暫存檔刪除失敗只記 log，不影響啟動；讀不了目錄則回傳錯誤。
func New(dir string) (*Local, error) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	es, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	for _, e := range es {
		if e.IsDir() || !strings.HasPrefix(e.Name(), tempPrefix) {
			continue
		}
		if err := os.Remove(filepath.Join(dir, e.Name())); err != nil && !errors.Is(err, os.ErrNotExist) {
			slog.Warn("remove orphan temp selfie failed", "name", e.Name(), "err", err)
		}
	}
	return &Local{dir: dir}, nil
}

func (l *Local) finalPath(cameoID string) (string, error) {
	if !safeID.MatchString(cameoID) {
		return "", fmt.Errorf("filestore: invalid cameo id %q", cameoID)
	}
	return filepath.Join(l.dir, cameoID+fileSuffix), nil
}

func (l *Local) tempPath(name string) (string, error) {
	if len(name) <= len(tempPrefix) || name[:len(tempPrefix)] != tempPrefix || filepath.Base(name) != name {
		return "", fmt.Errorf("filestore: invalid temp name %q", name)
	}
	return filepath.Join(l.dir, name), nil
}

func (l *Local) WriteTemp(ctx context.Context, r io.Reader) (string, error) {
	f, err := os.CreateTemp(l.dir, tempPrefix+"*")
	if err != nil {
		return "", err
	}
	name := filepath.Base(f.Name())
	fail := func(err error) (string, error) {
		f.Close()
		os.Remove(f.Name())
		return "", err
	}
	if _, err := io.Copy(f, &ctxReader{ctx: ctx, r: r}); err != nil {
		return fail(err)
	}
	if err := f.Sync(); err != nil {
		return fail(err)
	}
	if err := f.Close(); err != nil {
		os.Remove(f.Name())
		return "", err
	}
	return name, nil
}

func (l *Local) Commit(tempName, cameoID string) error {
	src, err := l.tempPath(tempName)
	if err != nil {
		return err
	}
	dst, err := l.finalPath(cameoID)
	if err != nil {
		return err
	}
	return os.Rename(src, dst)
}

func (l *Local) Discard(tempName string) error {
	p, err := l.tempPath(tempName)
	if err != nil {
		return err
	}
	if err := os.Remove(p); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return nil
}

func (l *Local) Delete(cameoID string) error {
	p, err := l.finalPath(cameoID)
	if err != nil {
		return err
	}
	if err := os.Remove(p); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return nil
}

// ctxReader 在 ctx 結束時中止複製。ctx 取消的唯一檢查點：usecase 不再重複檢查，
// 而是把 WriteTemp 回傳的 ctx 錯誤歸類為 BodyReadError。
type ctxReader struct {
	ctx context.Context
	r   io.Reader
}

func (c *ctxReader) Read(p []byte) (int, error) {
	if err := c.ctx.Err(); err != nil {
		return 0, err
	}
	return c.r.Read(p)
}
