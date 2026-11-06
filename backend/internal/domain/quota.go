package domain

import (
	"fmt"
	"time"
)

// Taipei 是額度與對外時間使用的時區（UTC+8，無日光節約）。
var Taipei = time.FixedZone("+08:00", 8*60*60)

// Quota 是當日剩餘額度。
type Quota struct {
	Remaining int
	ResetsAt  time.Time
	Display   string
}

// TaipeiDayBounds 回傳 now 所在台北日的 [start, end)；end 即下一個台北午夜。
func TaipeiDayBounds(now time.Time) (start, end time.Time) {
	t := now.In(Taipei)
	start = time.Date(t.Year(), t.Month(), t.Day(), 0, 0, 0, 0, Taipei)
	return start, start.AddDate(0, 0, 1)
}

// NewQuota 依上限與已用次數計算剩餘額度；剩餘不會小於 0。
func NewQuota(limit, used int, resetsAt time.Time) Quota {
	remaining := limit - used
	if remaining < 0 {
		remaining = 0
	}
	display := "今日客串次數已用完"
	if remaining > 0 {
		display = fmt.Sprintf("今日還可客串 %d 次", remaining)
	}
	return Quota{Remaining: remaining, ResetsAt: resetsAt, Display: display}
}
