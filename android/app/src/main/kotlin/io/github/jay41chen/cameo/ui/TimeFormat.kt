package io.github.jay41chen.cameo.ui

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private val display = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** 後端回 ISO 8601（+08:00）；轉不了就原樣顯示。 */
fun formatTime(iso: String): String = try {
    OffsetDateTime.parse(iso).format(display)
} catch (e: Exception) {
    iso
}
