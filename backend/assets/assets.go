// Package assets 內嵌隨程式發佈的固定資源。
package assets

import _ "embed"

// SampleVideo 是所有 SUCCEEDED 訂單共用的固定範例影片。
//
//go:embed sample.mp4
var SampleVideo []byte
