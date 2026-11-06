package com.kimhit.cameo.ui

import android.app.NotificationManager
import androidx.lifecycle.SavedStateHandle
import com.kimhit.cameo.tracking.isChannelBlocked
import com.kimhit.cameo.domain.model.Clip
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.usecase.ConfirmCameoUseCase
import com.kimhit.cameo.domain.usecase.GetClipsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NotificationGateDecisionTest {
    @Test
    fun enabled_proceeds_onAnyApi() {
        listOf(26, 32, 33, 36).forEach {
            assertEquals(NotificationGate.PROCEED, decideNotificationGate(true, it, askedBefore = true, shouldShowRationale = false))
        }
    }

    @Test
    fun disabled_api33_neverAsked_requestsPermission() {
        assertEquals(
            NotificationGate.REQUEST_PERMISSION,
            decideNotificationGate(false, 33, askedBefore = false, shouldShowRationale = false),
        )
    }

    @Test
    fun disabled_api33_askedButRationale_requestsPermissionAgain() {
        assertEquals(
            NotificationGate.REQUEST_PERMISSION,
            decideNotificationGate(false, 36, askedBefore = true, shouldShowRationale = true),
        )
    }

    @Test
    fun disabled_api33_askedAndNoRationale_needsSettings() {
        assertEquals(
            NotificationGate.NEED_SETTINGS,
            decideNotificationGate(false, 33, askedBefore = true, shouldShowRationale = false),
        )
    }

    @Test
    fun disabled_belowApi33_needsSettings_evenIfNeverAsked() {
        assertEquals(
            NotificationGate.NEED_SETTINGS,
            decideNotificationGate(false, 32, askedBefore = false, shouldShowRationale = false),
        )
        assertEquals(
            NotificationGate.NEED_SETTINGS,
            decideNotificationGate(false, 26, askedBefore = false, shouldShowRationale = true),
        )
    }

    @Test
    fun appEnabledButChannelBlocked_alwaysNeedsSettings() {
        listOf(26, 32, 33, 36).forEach {
            assertEquals(
                NotificationGate.NEED_SETTINGS,
                decideNotificationGate(true, it, askedBefore = false, shouldShowRationale = true, channelBlocked = true),
            )
        }
    }

    @Test
    fun appDisabled_channelBlockedFlagDoesNotChangeNormalPath() {
        assertEquals(
            NotificationGate.REQUEST_PERMISSION,
            decideNotificationGate(false, 33, askedBefore = false, shouldShowRationale = false, channelBlocked = true),
        )
    }

    @Test
    fun channelBlocked_onlyForImportanceNone_nullMeansNotCreatedYet() {
        assertTrue(isChannelBlocked(NotificationManager.IMPORTANCE_NONE))
        assertFalse(isChannelBlocked(null))
        assertFalse(isChannelBlocked(NotificationManager.IMPORTANCE_LOW))
        assertFalse(isChannelBlocked(NotificationManager.IMPORTANCE_HIGH))
    }

    private class FakeStore : PermissionAskedStore {
        var flag = false
        override fun asked() = flag
        override fun markAsked() {
            flag = true
        }
    }

    @Test
    fun askedFlag_writtenOnlyWhenResultArrives_notByDecision() {
        val store = FakeStore()
        decideNotificationGate(false, 33, store.asked(), false)
        assertFalse(store.flag) // 決定要請求、尚未有結果：不寫
        assertTrue(onPermissionResult(granted = true, store = store))
        assertTrue(store.flag)
    }

    @Test
    fun permissionResult_denied_writesFlagAndReturnsFalse() {
        val store = FakeStore()
        assertFalse(onPermissionResult(granted = false, store = store))
        assertTrue(store.flag)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmGateViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val pending = FakePendingRepo()
    private val cameos = FakeCameoRepo()
    private val clips = DomainResult.Success(listOf(Clip("c1", "雨夜重逢", 10)))

    private fun vm() = ConfirmViewModel(
        SavedStateHandle(mapOf("clipId" to "c1", "photo" to "/cache/captures/a.jpg")),
        GetClipsUseCase(FakeClips(ArrayDeque(listOf(clips)))),
        ConfirmCameoUseCase(cameos, pending),
    )

    @Test
    fun needSettings_doesNotCreate_andShowsPrompt() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.onConfirmClick(NotificationGate.NEED_SETTINGS)
        advanceUntilIdle()
        assertEquals(ConfirmPrompt.NEED_SETTINGS, vm.prompt.value)
        assertTrue(cameos.keys.isEmpty())
        assertTrue(pending.rows.isEmpty())
        assertTrue(vm.state.value is ConfirmUiState.Ready)
    }

    @Test
    fun requestPermission_doesNotCreate_untilGranted() = runTest {
        cameos.creates.addLast(DomainResult.Success(testOrder()))
        val vm = vm()
        advanceUntilIdle()
        vm.onConfirmClick(NotificationGate.REQUEST_PERMISSION)
        advanceUntilIdle()
        assertEquals(ConfirmPrompt.REQUEST_PERMISSION, vm.prompt.value)
        assertTrue(cameos.keys.isEmpty())
        vm.onPermissionRequestLaunched()
        assertEquals(ConfirmPrompt.NONE, vm.prompt.value)
        vm.onPermissionResult(granted = true, gate = NotificationGate.PROCEED)
        advanceUntilIdle()
        assertEquals(1, cameos.keys.size)
        assertTrue(vm.state.value is ConfirmUiState.Created)
    }

    @Test
    fun permissionGranted_butChannelStillBlocked_doesNotCreate_showsSettingsPrompt() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.onConfirmClick(NotificationGate.REQUEST_PERMISSION)
        vm.onPermissionRequestLaunched()
        vm.onPermissionResult(granted = true, gate = NotificationGate.NEED_SETTINGS)
        advanceUntilIdle()
        assertEquals(ConfirmPrompt.NEED_SETTINGS, vm.prompt.value)
        assertTrue(cameos.keys.isEmpty())
        assertTrue(pending.rows.isEmpty())
        assertTrue(vm.state.value is ConfirmUiState.Ready)
    }

    @Test
    fun permissionGranted_gateProceed_createsExactlyOnce() = runTest {
        cameos.creates.addLast(DomainResult.Success(testOrder()))
        val vm = vm()
        advanceUntilIdle()
        vm.onConfirmClick(NotificationGate.REQUEST_PERMISSION)
        vm.onPermissionRequestLaunched()
        vm.onPermissionResult(granted = true, gate = NotificationGate.PROCEED)
        vm.onPermissionResult(granted = true, gate = NotificationGate.PROCEED)
        advanceUntilIdle()
        assertEquals(1, cameos.keys.size)
        assertEquals(ConfirmPrompt.NONE, vm.prompt.value)
    }

    @Test
    fun permissionDenied_staysOnConfirm_noCreate_showsSettingsPrompt() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.onConfirmClick(NotificationGate.REQUEST_PERMISSION)
        vm.onPermissionRequestLaunched()
        vm.onPermissionResult(granted = false, gate = NotificationGate.PROCEED)
        advanceUntilIdle()
        assertEquals(ConfirmPrompt.NEED_SETTINGS, vm.prompt.value)
        assertTrue(cameos.keys.isEmpty())
        assertTrue(vm.state.value is ConfirmUiState.Ready)
        vm.dismissPrompt()
        assertEquals(ConfirmPrompt.NONE, vm.prompt.value)
    }

    @Test
    fun proceed_createsOrder() = runTest {
        cameos.creates.addLast(DomainResult.Success(testOrder()))
        val vm = vm()
        advanceUntilIdle()
        vm.onConfirmClick(NotificationGate.PROCEED)
        advanceUntilIdle()
        assertEquals(1, cameos.keys.size)
        assertEquals(ConfirmPrompt.NONE, vm.prompt.value)
    }
}
