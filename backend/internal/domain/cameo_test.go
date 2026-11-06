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
	if d := (Cameo{Status: StatusSucceeded}).Display(); d != (Display{"已完成", "你的客串短片完成了"}) {
		t.Errorf("succeeded = %+v", d)
	}
	rejected, vendor, timeout := FailureContentRejected, FailureVendorFailed, FailureTimeout
	if d := (Cameo{Status: StatusFailed, FailureReason: &rejected}).Display(); d != (Display{"無法生成", "照片未通過審核，點數已退還，今日次數不退還"}) {
		t.Errorf("content rejected = %+v", d)
	}
	for _, r := range []*string{&vendor, &timeout, nil} {
		if d := (Cameo{Status: StatusFailed, FailureReason: r}).Display(); d != (Display{"生成失敗", "點數已退還"}) {
			t.Errorf("failed(%v) = %+v", r, d)
		}
	}
	if d := (Cameo{Status: "SOMETHING_ELSE"}).Display(); d != (Display{"處理中", ""}) {
		t.Errorf("unknown = %+v", d)
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

func TestDisplayUploadedQueuedGenerating(t *testing.T) {
	cases := map[string]Display{
		StatusUploaded:   {"送出中", "照片已上傳，尚未扣款"},
		StatusQueued:     {"排隊中", "完成後會通知你"},
		StatusGenerating: {"生成中", "完成後會通知你"},
	}
	for status, want := range cases {
		if d := (Cameo{Status: status}).Display(); d != want {
			t.Errorf("%s = %+v, want %+v", status, d, want)
		}
	}
	if (Cameo{Status: StatusExpired}).Display().Message != UploadExpiredMessage {
		t.Error("expired display must use UploadExpiredMessage")
	}
}

func TestIsJPEGPrefix(t *testing.T) {
	cases := []struct {
		name string
		head []byte
		want bool
	}{
		{"jpeg", []byte{0xFF, 0xD8, 0xFF}, true},
		{"jpeg with more", []byte{0xFF, 0xD8, 0xFF, 0xE0, 0x00}, true},
		{"empty", nil, false},
		{"too short", []byte{0xFF, 0xD8}, false},
		{"png", []byte{0x89, 'P', 'N', 'G'}, false},
		{"wrong third byte", []byte{0xFF, 0xD8, 0x00}, false},
		{"text", []byte("GIF"), false},
	}
	for _, c := range cases {
		if got := IsJPEGPrefix(c.head); got != c.want {
			t.Errorf("%s: got %v want %v", c.name, got, c.want)
		}
	}
}

func TestVideoAvailable(t *testing.T) {
	for status, want := range map[string]bool{
		StatusAwaitingUpload: false,
		StatusUploaded:       false,
		StatusQueued:         false,
		StatusGenerating:     false,
		StatusSucceeded:      true,
		StatusFailed:         false,
		StatusExpired:        false,
	} {
		if got := (Cameo{Status: status}).VideoAvailable(); got != want {
			t.Errorf("VideoAvailable(%s) = %v, want %v", status, got, want)
		}
	}
}
