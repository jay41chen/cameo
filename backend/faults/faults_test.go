package faults

import (
	"strings"
	"testing"
	"testing/fstest"
)

func fsOf(tsv string, jsons map[string]string) fstest.MapFS {
	m := fstest.MapFS{"scenarios.tsv": {Data: []byte(tsv)}}
	for k, v := range jsons {
		m[k] = &fstest.MapFile{Data: []byte(v)}
	}
	return m
}

func TestLoadEmbedded(t *testing.T) {
	list, err := Load()
	if err != nil || len(list) == 0 {
		t.Fatalf("Load() = %d scenarios, err %v", len(list), err)
	}
}

func TestLoadOrderAndTrim(t *testing.T) {
	list, err := load(fsOf("b\tdesc b\n\na\tdesc a\n", map[string]string{"a.json": "{\"name\":\"x\"}\n", "b.json": "{}"}))
	if err != nil || len(list) != 2 || list[0].Name != "b" || list[1].Name != "a" || list[1].Desc != "desc a" || string(list[1].Example) != `{"name":"x"}` {
		t.Fatalf("list = %+v err = %v", list, err)
	}
}

func TestLoadErrors(t *testing.T) {
	cases := []struct {
		name, want string
		fs         fstest.MapFS
	}{
		{"missing json", "a.json", fsOf("a\tx\n", nil)},
		{"orphan json", "b.json is not listed", fsOf("a\tx\n", map[string]string{"a.json": "{}", "b.json": "{}"})},
		{"duplicate", "duplicate", fsOf("a\tx\na\ty\n", map[string]string{"a.json": "{}"})},
		{"no tab", "one tab", fsOf("a x\n", map[string]string{"a.json": "{}"})},
		{"two tabs", "one tab", fsOf("a\tb\tc\n", map[string]string{"a.json": "{}"})},
		{"empty desc", "empty", fsOf("a\t \n", map[string]string{"a.json": "{}"})},
		{"bad json", "not a JSON object", fsOf("a\tx\n", map[string]string{"a.json": "{"})},
		{"json array", "not a JSON object", fsOf("a\tx\n", map[string]string{"a.json": "[]"})},
	}
	for _, c := range cases {
		_, err := load(c.fs)
		if err == nil || !strings.Contains(err.Error(), c.want) {
			t.Errorf("%s: err = %v, want containing %q", c.name, err, c.want)
		}
	}
}
