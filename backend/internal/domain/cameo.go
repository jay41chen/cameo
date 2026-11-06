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
	StatusUploaded       = "UPLOADED"
	StatusQueued         = "QUEUED"
	StatusGenerating     = "GENERATING"
	StatusSucceeded      = "SUCCEEDED"
	StatusFailed         = "FAILED"
	StatusExpired        = "EXPIRED"
)

// 點數狀態。
const (
	PointsHeld     = "HELD"
	PointsCharged  = "CHARGED"
	PointsRefunded = "REFUNDED"
	PointsReleased = "RELEASED"
)

// 失敗原因（cameos.failure_reason）。只有 FailureContentRejected 不退還每日額度。
const (
	FailureContentRejected = "CONTENT_REJECTED"
	FailureVendorFailed    = "VENDOR_FAILED"
	FailureTimeout         = "TIMEOUT"
)

// UploadExpiredMessage 是上傳期限已過時給使用者看的文字，EXPIRED 的 Display 與 410 錯誤共用。
const UploadExpiredMessage = "上傳期限已過，點數沒有扣款，請重新客串。"

// jpegMagic 是 JPEG 檔案開頭的 3 bytes。
var jpegMagic = [3]byte{0xFF, 0xD8, 0xFF}

// IsJPEGPrefix 回傳 head（檔案前 3 bytes）是否為 JPEG 開頭；只看內容。
func IsJPEGPrefix(head []byte) bool {
	return len(head) >= len(jpegMagic) && [3]byte(head[:3]) == jpegMagic
}

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
	// GenerationExpiresAt 在送出（轉 QUEUED）時寫入，涵蓋排隊與生成兩段；之前的狀態為 nil。
	GenerationExpiresAt *time.Time
	// VideoURL 是廠商回報的影片位置，只存資料庫，不放進 API 回應。
	VideoURL  *string
	CreatedAt time.Time
	UpdatedAt time.Time
}

// Display 是給使用者看的狀態文字。
type Display struct {
	Title   string
	Message string
}

// Display 依狀態產生顯示文字；尚未定義文字的狀態用通用文字。
func (c Cameo) Display() Display {
	switch c.Status {
	case StatusAwaitingUpload:
		return Display{Title: "等待上傳", Message: fmt.Sprintf("已占用 %d 點，送出後才扣款", c.Clip.Cost)}
	case StatusUploaded:
		return Display{Title: "送出中", Message: "照片已上傳，尚未扣款"}
	case StatusQueued:
		return Display{Title: "排隊中", Message: "完成後會通知你"}
	case StatusGenerating:
		return Display{Title: "生成中", Message: "完成後會通知你"}
	case StatusSucceeded:
		return Display{Title: "已完成", Message: "你的客串短片完成了"}
	case StatusFailed:
		if c.FailureReason != nil && *c.FailureReason == FailureContentRejected {
			return Display{Title: "無法生成", Message: "照片未通過審核，點數已退還，今日次數不退還"}
		}
		return Display{Title: "生成失敗", Message: "點數已退還"}
	case StatusExpired:
		return Display{Title: "已過期", Message: UploadExpiredMessage}
	}
	return Display{Title: "處理中", Message: ""}
}
