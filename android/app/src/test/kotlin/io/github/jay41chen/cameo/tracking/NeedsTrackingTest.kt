package io.github.jay41chen.cameo.tracking

import io.github.jay41chen.cameo.domain.model.CameoStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class NeedsTrackingTest {
    @Test
    fun `只有排隊與生成中需要追蹤`() {
        val expected = setOf(CameoStatus.QUEUED, CameoStatus.GENERATING)
        CameoStatus.entries.forEach { status ->
            assertEquals("status=$status", status in expected, needsTracking(status))
        }
    }
}
