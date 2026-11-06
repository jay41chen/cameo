package filestore_test

import (
	"context"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"cameo/backend/internal/adapter/filestore"
)

func entries(t *testing.T, dir string) []string {
	t.Helper()
	es, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, e := range es {
		names = append(names, e.Name())
	}
	return names
}

func TestWriteCommitOverwriteDelete(t *testing.T) {
	dir := t.TempDir()
	fs, err := filestore.New(dir)
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	for _, content := range []string{"first", "second"} {
		tmp, err := fs.WriteTemp(ctx, strings.NewReader(content))
		if err != nil {
			t.Fatal(err)
		}
		if err := fs.Commit(tmp, "abc-123"); err != nil {
			t.Fatal(err)
		}
		got, err := os.ReadFile(filepath.Join(dir, "abc-123.jpg"))
		if err != nil || string(got) != content {
			t.Fatalf("content = %q, %v; want %q", got, err, content)
		}
	}
	if names := entries(t, dir); len(names) != 1 {
		t.Fatalf("entries = %v", names)
	}
	if err := fs.Delete("abc-123"); err != nil {
		t.Fatal(err)
	}
	if err := fs.Delete("abc-123"); err != nil {
		t.Fatalf("delete missing: %v", err)
	}
	if names := entries(t, dir); len(names) != 0 {
		t.Fatalf("entries = %v", names)
	}
}

func TestDiscard(t *testing.T) {
	dir := t.TempDir()
	fs, _ := filestore.New(dir)
	tmp, err := fs.WriteTemp(context.Background(), strings.NewReader("x"))
	if err != nil {
		t.Fatal(err)
	}
	if err := fs.Discard(tmp); err != nil {
		t.Fatal(err)
	}
	if err := fs.Discard(tmp); err != nil {
		t.Fatalf("discard twice: %v", err)
	}
	if names := entries(t, dir); len(names) != 0 {
		t.Fatalf("entries = %v", names)
	}
}

func TestWriteTempFailureLeavesNothing(t *testing.T) {
	dir := t.TempDir()
	fs, _ := filestore.New(dir)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := fs.WriteTemp(ctx, strings.NewReader("x")); err == nil {
		t.Fatal("want error")
	}
	if names := entries(t, dir); len(names) != 0 {
		t.Fatalf("entries = %v", names)
	}
}

func TestRejectsPathTraversal(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "selfies")
	fs, err := filestore.New(dir)
	if err != nil {
		t.Fatal(err)
	}
	tmp, err := fs.WriteTemp(context.Background(), strings.NewReader("x"))
	if err != nil {
		t.Fatal(err)
	}
	for _, id := range []string{"../evil", "a/b", "", "..", "a\x00b", `a\b`} {
		if err := fs.Commit(tmp, id); err == nil {
			t.Errorf("Commit(%q) = nil", id)
		}
		if err := fs.Delete(id); err == nil {
			t.Errorf("Delete(%q) = nil", id)
		}
	}
	for _, name := range []string{"../x", "x", ".tmp-/../../x", ""} {
		if err := fs.Discard(name); err == nil {
			t.Errorf("Discard(%q) = nil", name)
		}
		if err := fs.Commit(name, "ok"); err == nil {
			t.Errorf("Commit temp %q = nil", name)
		}
	}
	// 只有原本的暫存檔，沒有任何檔案跑到目錄外。
	if names := entries(t, filepath.Dir(dir)); len(names) != 1 {
		t.Fatalf("parent entries = %v", names)
	}
}

func TestNewRemovesOrphanTempFilesOnly(t *testing.T) {
	dir := t.TempDir()
	for _, n := range []string{".tmp-123", ".tmp-abc", "abc-123.jpg", "other.txt", "x.tmp-1"} {
		if err := os.WriteFile(filepath.Join(dir, n), []byte("x"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := filestore.New(dir); err != nil {
		t.Fatal(err)
	}
	got := strings.Join(entries(t, dir), ",")
	if want := "abc-123.jpg,other.txt,x.tmp-1"; got != want {
		t.Fatalf("entries = %s, want %s", got, want)
	}
}

// cancelAfterFirstRead 在第一次讀取後取消 ctx，模擬讀 body 中途 client 斷線。
type cancelAfterFirstRead struct {
	cancel context.CancelFunc
	n      int
}

func (c *cancelAfterFirstRead) Read(p []byte) (int, error) {
	c.n++
	if c.n > 1 {
		return 0, io.EOF // 若 WriteTemp 沒有檢查 ctx，就會正常結束而不是失敗
	}
	c.cancel()
	return copy(p, "partial"), nil
}

func TestWriteTempCancelledMidReadLeavesNothing(t *testing.T) {
	dir := t.TempDir()
	fs, _ := filestore.New(dir)
	ctx, cancel := context.WithCancel(context.Background())
	_, err := fs.WriteTemp(ctx, &cancelAfterFirstRead{cancel: cancel})
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v, want context.Canceled", err)
	}
	if names := entries(t, dir); len(names) != 0 {
		t.Fatalf("entries = %v", names)
	}
}
