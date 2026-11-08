package io.github.jay41chen.cameo.domain.usecase

import io.github.jay41chen.cameo.domain.model.Cameo
import io.github.jay41chen.cameo.domain.model.CameoOrder
import io.github.jay41chen.cameo.domain.model.CameoStatus
import io.github.jay41chen.cameo.domain.model.Clip
import io.github.jay41chen.cameo.domain.model.DomainError
import io.github.jay41chen.cameo.domain.model.DomainResult
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackCameoTest {
    private fun ok(status: CameoStatus) =
        DomainResult.Success(CameoOrder(Cameo("u1", Clip("c1", "雨夜重逢", 10), status, "標題", "說明"), null))

    private val queryFailed = DomainResult.Failure(DomainError(message = null, retryable = true, code = "NETWORK"))

    @Test
    fun terminalStatuses_notifyAndStop() {
        assertEquals(TrackDecision.NOTIFY_AND_STOP, decideTracking(ok(CameoStatus.SUCCEEDED), 0))
        assertEquals(TrackDecision.NOTIFY_AND_STOP, decideTracking(ok(CameoStatus.FAILED), 0))
    }

    @Test
    fun terminalStatus_atOrOverLimit_stillNotifies() {
        assertEquals(TrackDecision.NOTIFY_AND_STOP, decideTracking(ok(CameoStatus.SUCCEEDED), TRACK_LIMIT_MS))
        assertEquals(TrackDecision.NOTIFY_AND_STOP, decideTracking(ok(CameoStatus.FAILED), TRACK_LIMIT_MS + 1))
    }

    @Test
    fun inProgressAndQueryFailure_continue() {
        assertEquals(TrackDecision.CONTINUE, decideTracking(ok(CameoStatus.QUEUED), 0))
        assertEquals(TrackDecision.CONTINUE, decideTracking(ok(CameoStatus.GENERATING), 10_000))
        assertEquals(TrackDecision.CONTINUE, decideTracking(queryFailed, 10_000))
    }

    @Test
    fun limit_is35Minutes() {
        assertEquals(35 * 60 * 1000L, TRACK_LIMIT_MS)
    }

    @Test
    fun limitBoundary_justBelowContinues_atLimitStops() {
        listOf(ok(CameoStatus.QUEUED), ok(CameoStatus.GENERATING), queryFailed).forEach {
            assertEquals(TrackDecision.CONTINUE, decideTracking(it, TRACK_LIMIT_MS - 1))
            assertEquals(TrackDecision.STOP, decideTracking(it, TRACK_LIMIT_MS))
            assertEquals(TrackDecision.STOP, decideTracking(it, TRACK_LIMIT_MS + 1))
        }
    }

    @Test
    fun otherStatuses_stopWithoutNotification() {
        listOf(CameoStatus.EXPIRED, CameoStatus.AWAITING_UPLOAD, CameoStatus.UPLOADED, CameoStatus.UNKNOWN).forEach {
            assertEquals(TrackDecision.STOP, decideTracking(ok(it), 0))
        }
    }
}
