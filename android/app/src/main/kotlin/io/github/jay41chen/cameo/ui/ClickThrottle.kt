package io.github.jay41chen.cameo.ui

/** 導航點擊節流間隔。Navigation Compose 2.10.2 預設轉場為 700ms，故 1000ms 可涵蓋整段轉場。 */
internal const val NAV_CLICK_INTERVAL_MS = 1000L

/**
 * 距上次「被接受」的點擊未滿 [intervalMs] 就擋下。被擋的點擊不更新計時。
 * [now] 應為單調時鐘（SystemClock.elapsedRealtime）。
 */
class ClickThrottle(private val intervalMs: Long, private val now: () -> Long) {
    private var last: Long? = null

    fun tryAcquire(): Boolean {
        val t = now()
        val prev = last
        if (prev != null && t - prev < intervalMs) return false
        last = t
        return true
    }
}

/** 先節流、通過才執行 [action]；被擋時完全沒有副作用。 */
inline fun ClickThrottle.guarded(action: () -> Unit) {
    if (tryAcquire()) action()
}
