package domain

import "errors"

// ErrInvalidCursor 表示分頁 cursor 無法解析。
var ErrInvalidCursor = errors.New("invalid cursor")
