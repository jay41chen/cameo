package io.github.jay41chen.cameo.tracking

import io.github.jay41chen.cameo.domain.model.CameoStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class NeedsTrackingTest {
    @Test
    fun `排隊、生成中與未知狀態需要追蹤`() {
        val expected = setOf(CameoStatus.QUEUED, CameoStatus.GENERATING, CameoStatus.UNKNOWN)
        CameoStatus.entries.forEach { status ->
            assertEquals("status=$status", status in expected, needsTracking(status))
        }
    }
}
