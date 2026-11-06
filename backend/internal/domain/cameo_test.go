package domain

import (
	"testing"
	"time"
)

func TestDisplay(t *testing.T) {
	c := Cameo{Status: StatusAwaitingUpload, Clip: Clip{Cost: 10}}
	if d := c.Display(); d != (Display{"等待上傳", "已占用 10 點，送出後才扣款"}) {
		t.Errorf("awaiting = %+v", d)
	}
	if d := (Cameo{Status: StatusExpired}).Display(); d != (Display{"已過期", "上傳期限已過，點數沒有扣款，請重新客串。"}) {
		t.Errorf("expired = %+v", d)
	}
	if d := (Cameo{Status: "GENERATING"}).Display(); d.Title != "處理中" {
		t.Errorf("other = %+v", d)
	}
}

func TestTaipeiDayBounds(t *testing.T) {
	// UTC 15:59 仍是台北當天，UTC 16:00 才換日。
	s, e := TaipeiDayBounds(time.Date(2026, 11, 6, 15, 59, 0, 0, time.UTC))
	if !s.Equal(time.Date(2026, 11, 5, 16, 0, 0, 0, time.UTC)) || !e.Equal(time.Date(2026, 11, 6, 16, 0, 0, 0, time.UTC)) {
		t.Errorf("bounds = %v %v", s, e)
	}
	s, _ = TaipeiDayBounds(time.Date(2026, 11, 6, 16, 0, 0, 0, time.UTC))
	if !s.Equal(time.Date(2026, 11, 6, 16, 0, 0, 0, time.UTC)) {
		t.Errorf("start = %v", s)
	}
}

func TestNewQuotaNeverNegative(t *testing.T) {
	if q := NewQuota(3, 5, time.Time{}); q.Remaining != 0 || q.Display != "今日客串次數已用完" {
		t.Errorf("quota = %+v", q)
	}
	if q := NewQuota(3, 1, time.Time{}); q.Remaining != 2 || q.Display != "今日還可客串 2 次" {
		t.Errorf("quota = %+v", q)
	}
}
