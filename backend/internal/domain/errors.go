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
	ErrUploadExpired       = errors.New("upload window expired")
	ErrInvalidState        = errors.New("cameo is not in a state that allows this")
	ErrSelfieNotUploaded   = errors.New("selfie not uploaded")
	ErrInvalidSelfie       = errors.New("selfie is not a JPEG")
	ErrVideoNotReady       = errors.New("video not ready")
)
