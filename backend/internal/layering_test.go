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
// domain 不依賴 usecase／adapter／app；usecase 不依賴 adapter／app；
// app 可依賴 usecase，不得依賴 adapter；adapter 之間互不依賴。
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
	case dir == "app" || strings.HasPrefix(dir, "app/"):
		return "app"
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
	case f == "domain" && g == "app", f == "usecase" && g == "app":
		return "domain and usecase must not depend on app"
	case f == "app" && strings.HasPrefix(g, "adapter/"):
		return "app must not depend on adapter"
	case f == "usecase" && strings.HasPrefix(g, "adapter/"):
		return "usecase must not depend on adapter"
	case strings.HasPrefix(f, "adapter/") && strings.HasPrefix(g, "adapter/") && f != g:
		return "adapters must not depend on each other"
	}
	return ""
}

// TestViolationRules 確認規則本身：filestore 屬於 adapter，只能被組裝層以外的人使用，
// 不能依賴其他 adapter，usecase／domain 也不能依賴它。
func TestViolationRules(t *testing.T) {
	cases := []struct {
		from, to string
		bad      bool
	}{
		{"adapter/filestore", "adapter/postgres", true},
		{"adapter/postgres", "adapter/filestore", true},
		{"adapter/http", "adapter/filestore", true},
		{"usecase", "adapter/filestore", true},
		{"domain", "adapter/filestore", true},
		{"app", "adapter/filestore", true},
		{"adapter/filestore", "usecase", false},
		{"adapter/filestore", "domain", false},
		{"adapter/filestore", "adapter/filestore", false},
	}
	for _, c := range cases {
		if got := violation(c.from, c.to) != ""; got != c.bad {
			t.Errorf("violation(%s -> %s) = %v, want %v", c.from, c.to, got, c.bad)
		}
	}
}
