package com.kimhit.cameo.domain.model

data class Clip(val id: String, val title: String, val cost: Int)

data class Points(val available: Int, val held: Int)

enum class LedgerType { CHARGE, REFUND, UNKNOWN }

/** [createdAt] 保留後端的 ISO 8601 字串，顯示格式由 App 處理。 */
data class LedgerEntry(
    val id: String,
    val type: LedgerType,
    val amount: Int,
    val cameoId: String?,
    val clipTitle: String?,
    val createdAt: String,
)

data class LedgerPage(val items: List<LedgerEntry>, val nextCursor: String?)

/** [resetsAt] 保留後端的 ISO 8601 字串；[display] 為後端給的顯示文字。 */
data class Quota(val remaining: Int, val resetsAt: String, val display: String)

enum class CameoStatus { AWAITING_UPLOAD, UPLOADED, QUEUED, GENERATING, SUCCEEDED, FAILED, EXPIRED, UNKNOWN }

data class Cameo(
    val id: String,
    val clip: Clip,
    val status: CameoStatus,
    val displayTitle: String?,
    val displayMessage: String?,
)

data class CameoUpload(val url: String, val expiresAt: String)

data class CameoOrder(val cameo: Cameo, val upload: CameoUpload?)

enum class PendingStep { CREATE, UPLOAD, SUBMIT, TRACK }

/** 本機草稿。[clientRequestId] 即建單的 Idempotency-Key。 */
data class PendingCameo(
    val clientRequestId: String,
    val clipId: String,
    val cameoId: String?,
    val step: PendingStep,
    val lastError: String?,
    /** 正式路徑的照片；沒有照片或檔案已遺失時為 null。路徑由資料層決定。 */
    val photoPath: String? = null,
)

/** [order] 為 null 表示沒有 cameoId，或暫時查不到狀態。 */
data class UnfinishedCameo(val pending: PendingCameo, val order: CameoOrder?)
