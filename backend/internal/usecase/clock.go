package usecase

import "time"

// SystemClock 使用系統時間。
type SystemClock struct{}

func (SystemClock) Now() time.Time { return time.Now() }
