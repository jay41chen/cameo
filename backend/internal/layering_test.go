package internal_test

import (
	"go/parser"
	"go/token"
	"io/fs"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

const modulePath = "cameo/backend/internal/"

// TestImportDirection 檢查分層方向：
// domain 不依賴 usecase／adapter；usecase 不依賴 adapter；adapter 之間互不依賴；
// 非 _test.go 不得 import internal/testdb。
func TestImportDirection(t *testing.T) {
	root, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	checked := 0
	err = filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") || strings.HasSuffix(path, "layering_test.go") {
			return err
		}
		rel, _ := filepath.Rel(root, filepath.Dir(path))
		rel = filepath.ToSlash(rel)
		f, err := parser.ParseFile(token.NewFileSet(), path, nil, parser.ImportsOnly)
		if err != nil {
			return err
		}
		checked++
		for _, imp := range f.Imports {
			p, _ := strconv.Unquote(imp.Path.Value)
			if !strings.HasPrefix(p, modulePath) {
				continue
			}
			target := strings.TrimPrefix(p, modulePath)
			if target == "testdb" && !strings.HasSuffix(path, "_test.go") {
				t.Errorf("%s imports %s: testdb is for _test.go files only", filepath.ToSlash(filepath.Join("internal", rel)), p)
			}
			if reason := violation(rel, target); reason != "" {
				t.Errorf("%s imports %s: %s", filepath.ToSlash(filepath.Join("internal", rel)), p, reason)
			}
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if checked == 0 {
		t.Fatal("no go files checked")
	}
}

func layer(dir string) string {
	switch {
	case dir == "domain" || strings.HasPrefix(dir, "domain/"):
		return "domain"
	case dir == "usecase" || strings.HasPrefix(dir, "usecase/"):
		return "usecase"
	case strings.HasPrefix(dir, "adapter/"):
		return "adapter/" + strings.Split(dir, "/")[1]
	}
	return ""
}

func violation(from, to string) string {
	f, g := layer(from), layer(to)
	switch {
	case f == "domain" && (g == "usecase" || strings.HasPrefix(g, "adapter/")):
		return "domain must not depend on usecase or adapter"
	case f == "usecase" && strings.HasPrefix(g, "adapter/"):
		return "usecase must not depend on adapter"
	case strings.HasPrefix(f, "adapter/") && strings.HasPrefix(g, "adapter/") && f != g:
		return "adapters must not depend on each other"
	}
	return ""
}
