// Package faults 內嵌異常情境資料：<情境>.json 是登記用的 body，scenarios.tsv 是顯示順序與說明。
// 控制台頁面與 fault.sh 都從這份資料產生，不要在別處再抄一份清單。
package faults

import (
	"embed"
	"encoding/json"
	"fmt"
	"io/fs"
	"strings"
)

//go:embed *.json scenarios.tsv
var files embed.FS

// Scenario 是一個可登記的異常情境。
type Scenario struct {
	Name    string          `json:"name"`
	Desc    string          `json:"desc"`
	Example json.RawMessage `json:"example"`
}

// Load 讀取內嵌的情境，順序同 scenarios.tsv。
func Load() ([]Scenario, error) { return load(files) }

func load(fsys fs.FS) ([]Scenario, error) {
	raw, err := fs.ReadFile(fsys, "scenarios.tsv")
	if err != nil {
		return nil, err
	}
	var list []Scenario
	seen := map[string]bool{}
	for i, line := range strings.Split(string(raw), "\n") {
		line = strings.TrimRight(line, "\r")
		if strings.TrimSpace(line) == "" {
			continue
		}
		if strings.Count(line, "\t") != 1 {
			return nil, fmt.Errorf("scenarios.tsv line %d: want exactly one tab", i+1)
		}
		name, desc, _ := strings.Cut(line, "\t")
		name, desc = strings.TrimSpace(name), strings.TrimSpace(desc)
		if name == "" || desc == "" {
			return nil, fmt.Errorf("scenarios.tsv line %d: empty name or description", i+1)
		}
		if seen[name] {
			return nil, fmt.Errorf("scenarios.tsv line %d: duplicate name %q", i+1, name)
		}
		seen[name] = true
		b, err := fs.ReadFile(fsys, name+".json")
		if err != nil {
			return nil, fmt.Errorf("scenarios.tsv line %d: %w", i+1, err)
		}
		b = []byte(strings.TrimSpace(string(b)))
		var obj map[string]json.RawMessage
		if err := json.Unmarshal(b, &obj); err != nil {
			return nil, fmt.Errorf("%s.json: not a JSON object: %w", name, err)
		}
		list = append(list, Scenario{Name: name, Desc: desc, Example: b})
	}
	jsons, err := fs.Glob(fsys, "*.json")
	if err != nil {
		return nil, err
	}
	for _, f := range jsons {
		if !seen[strings.TrimSuffix(f, ".json")] {
			return nil, fmt.Errorf("%s is not listed in scenarios.tsv", f)
		}
	}
	return list, nil
}
