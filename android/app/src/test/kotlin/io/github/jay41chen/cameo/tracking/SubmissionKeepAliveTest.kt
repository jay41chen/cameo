package io.github.jay41chen.cameo.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmissionKeepAliveTest {
    @Test
    fun `shouldStopService 只有兩者都空才停`() {
        assertTrue(shouldStopService(trackedEmpty = true, holdsEmpty = true))
        assertFalse(shouldStopService(trackedEmpty = true, holdsEmpty = false))
        assertFalse(shouldStopService(trackedEmpty = false, holdsEmpty = true))
        assertFalse(shouldStopService(trackedEmpty = false, holdsEmpty = false))
    }

    @Test
    fun `expiredHolds 剛好等於上限算過期`() {
        assertEquals(setOf("a"), expiredHolds(mapOf("a" to 1_000L), now = 1_000L + HOLD_LIMIT_MS))
    }

    @Test
    fun `expiredHolds 差一毫秒不算過期`() {
        assertEquals(emptySet<String>(), expiredHolds(mapOf("a" to 1_000L), now = 1_000L + HOLD_LIMIT_MS - 1))
    }

    @Test
    fun `expiredHolds 多筆只回傳過期的`() {
        val holds = mapOf("old" to 0L, "edge" to 100L, "fresh" to 10_000L)
        assertEquals(setOf("old", "edge"), expiredHolds(holds, now = 100L + 1_000L, limitMs = 1_000L))
        assertEquals(emptySet<String>(), expiredHolds(emptyMap(), now = 5L))
    }
}
