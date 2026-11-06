package integration_test

import (
	"testing"

	"cameo/backend/internal/adapter/filestore"
)

// newFiles 回傳放在測試暫存目錄的 filestore。
func newFiles(t *testing.T) *filestore.Local {
	t.Helper()
	fs, err := filestore.New(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	return fs
}
