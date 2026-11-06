package domain

import (
	"fmt"
	"strings"
	"time"
	"unicode/utf8"
)

// 訂單狀態。
const (
	StatusAwaitingUpload = "AWAITING_UPLOAD"
	StatusExpired        = "EXPIRED"
)

// 點數狀態。
const PointsHeld = "HELD"

// ValidText 回傳 s 能否安全當作資料庫文字查詢條件：必須是合法 UTF-8 且不含 NUL。
// 不合法的 id 一定查不到資源，呼叫端應視為「不存在」。
func ValidText(s string) bool {
	return utf8.ValidString(s) && !strings.ContainsRune(s, 0)
}

// Cameo 是一筆客串訂單。Clip.Cost 是建單時的快照。
type Cameo struct {
	ID              string
	IdempotencyKey  string
	Clip            Clip
	Status          string
	PointsState     string
	FailureReason   *string
	UploadExpiresAt time.Time
	CreatedAt       time.Time
	UpdatedAt       time.Time
}

// Display 是給使用者看的狀態文字。
type Display struct {
	Title   string
	Message string
}

// Display 依狀態產生顯示文字。這輪只會出現 AWAITING_UPLOAD 與 EXPIRED，其餘用通用文字。
func (c Cameo) Display() Display {
	switch c.Status {
	case StatusAwaitingUpload:
		return Display{Title: "等待上傳", Message: fmt.Sprintf("已占用 %d 點，送出後才扣款", c.Clip.Cost)}
	case StatusExpired:
		return Display{Title: "已過期", Message: "上傳期限已過，點數沒有扣款，請重新客串。"}
	}
	return Display{Title: "處理中", Message: ""}
}
