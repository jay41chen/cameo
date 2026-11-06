package domain

import "errors"

// ErrInvalidCursor 表示分頁 cursor 無法解析。
var ErrInvalidCursor = errors.New("invalid cursor")

var (
	ErrClipNotFound        = errors.New("clip not found")
	ErrCameoNotFound       = errors.New("cameo not found")
	ErrIdempotencyConflict = errors.New("idempotency key reused with a different clip")
	ErrDailyLimitReached   = errors.New("daily limit reached")
	ErrInsufficientPoints  = errors.New("insufficient points")
)
