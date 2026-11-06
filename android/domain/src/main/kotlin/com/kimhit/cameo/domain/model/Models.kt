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
