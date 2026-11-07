package com.kimhit.cameo.ui

import com.kimhit.cameo.domain.model.Cameo
import com.kimhit.cameo.domain.model.CameoOrder
import com.kimhit.cameo.domain.model.CameoStatus
import com.kimhit.cameo.domain.model.Clip
import com.kimhit.cameo.domain.usecase.CameoStep
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressActionsTest {
    @Test
    fun failedRetryable_retryThenBackHome() {
        assertEquals(
            listOf(ProgressAction.Retry, ProgressAction.BackHome),
            progressActions(ProgressUiState.Failed("x", true)),
        )
    }

    @Test
    fun failedNotRetryable_onlyBackHome() {
        assertEquals(listOf(ProgressAction.BackHome), progressActions(ProgressUiState.Failed("x", false)))
    }

    @Test
    fun needPhoto_retakeThenBackHome() {
        assertEquals(
            listOf(ProgressAction.Retake, ProgressAction.BackHome),
            progressActions(ProgressUiState.NeedPhoto("c1", "k1")),
        )
    }

    @Test
    fun expired_onlyBackHome() {
        assertEquals(listOf(ProgressAction.BackHome), progressActions(ProgressUiState.Expired("t", "m")))
    }

    @Test
    fun otherStates_haveNoActions() {
        val order = CameoOrder(Cameo("u1", Clip("c1", "雨夜重逢", 10), CameoStatus.QUEUED, null, null), null)
        assertEquals(emptyList<ProgressAction>(), progressActions(ProgressUiState.Working(null)))
        assertEquals(emptyList<ProgressAction>(), progressActions(ProgressUiState.Working(CameoStep.UPLOADING)))
        assertEquals(emptyList<ProgressAction>(), progressActions(ProgressUiState.Done(order)))
        assertEquals(emptyList<ProgressAction>(), progressActions(ProgressUiState.NotCreated("c1")))
        assertEquals(emptyList<ProgressAction>(), progressActions(ProgressUiState.Gone))
    }
}
