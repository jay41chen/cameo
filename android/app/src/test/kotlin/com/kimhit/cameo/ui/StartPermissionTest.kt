package com.kimhit.cameo.ui

import com.kimhit.cameo.domain.model.CameoStatus
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.model.PendingCameo
import com.kimhit.cameo.domain.model.PendingStep
import com.kimhit.cameo.domain.model.UnfinishedCameo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DecideStartPermissionTest {
    private fun decide(n: NotificationGate, granted: Boolean = false, afterDenial: Boolean = false, rationale: Boolean = false) =
        decideStartPermission(n, granted, afterDenial, rationale)

    @Test
    fun notificationResult_winsOverCamera_evenWhenCameraNotGranted() {
        listOf(false, true).forEach { cameraGranted ->
            assertEquals(
                StartPermission.REQUEST_NOTIFICATION,
                decide(NotificationGate.REQUEST_PERMISSION, granted = cameraGranted),
            )
            assertEquals(
                StartPermission.NOTIFICATION_SETTINGS,
                decide(NotificationGate.NEED_SETTINGS, granted = cameraGranted),
            )
        }
    }

    @Test
    fun notificationProceed_cameraGranted_proceedsInEveryCombination() {
        listOf(false, true).forEach { afterDenial ->
            listOf(false, true).forEach { rationale ->
                assertEquals(
                    StartPermission.PROCEED,
                    decide(NotificationGate.PROCEED, granted = true, afterDenial = afterDenial, rationale = rationale),
                )
            }
        }
    }

    @Test
    fun notificationProceed_onPress_noRationale_stillRequestsCamera() {
        assertEquals(
            StartPermission.REQUEST_CAMERA,
            decide(NotificationGate.PROCEED, afterDenial = false, rationale = false),
        )
    }

    @Test
    fun notificationProceed_onPress_withRationale_requestsCamera() {
        assertEquals(
            StartPermission.REQUEST_CAMERA,
            decide(NotificationGate.PROCEED, afterDenial = false, rationale = true),
        )
    }

    @Test
    fun notificationProceed_afterDenialWithRationale_requestsCameraAgain() {
        assertEquals(
            StartPermission.REQUEST_CAMERA,
            decide(NotificationGate.PROCEED, afterDenial = true, rationale = true),
        )
    }

    @Test
    fun notificationProceed_afterDenialNoRationale_needsSettings() {
        assertEquals(
            StartPermission.CAMERA_SETTINGS,
            decide(NotificationGate.PROCEED, afterDenial = true, rationale = false),
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StartPermissionViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val pending = FakePendingRepo()
    private val quota = FakeQuota()

    private val draft = PendingCameo("k1", "c1", null, PendingStep.CREATE, "err")
    private val created = PendingCameo("k2", "c2", "u2", PendingStep.UPLOAD, null)
    private val draftRow = UnfinishedCameo(draft, null)
    private val createdRow = UnfinishedCameo(created, testOrder(CameoStatus.AWAITING_UPLOAD, "u2"))
    private val expiredPending = PendingCameo("k3", "c3", "u3", PendingStep.UPLOAD, null)
    private val expiredRow = UnfinishedCameo(expiredPending, testOrder(CameoStatus.EXPIRED, "u3"))

    private val cameos = FakeCameoRepo()

    private fun vm() = myCameosVm(pending = pending, cameos = cameos, quota = quota)

    // ---- FAB ----

    @Test
    fun fab_proceed_navigatesToClips() = runTest {
        val vm = vm()
        val navs = collectStartNavigation(vm)
        vm.onStartClick(StartPermission.PROCEED)
        assertEquals(listOf<StartNavigation>(StartNavigation.OpenClips), navs)
        assertEquals(StartPrompt.NONE, vm.startPrompt.value)
    }

    @Test
    fun fab_requestNotification_doesNotNavigate_andSetsPrompt() = runTest {
        val vm = vm()
        val navs = collectStartNavigation(vm)
        vm.onStartClick(StartPermission.REQUEST_NOTIFICATION)
        assertTrue(navs.isEmpty())
        assertEquals(StartPrompt.REQUEST_NOTIFICATION, vm.startPrompt.value)
        vm.onPermissionRequestLaunched()
        assertEquals(StartPrompt.NONE, vm.startPrompt.value)
        assertTrue(navs.isEmpty())
    }

    @Test
    fun fab_notificationGranted_thenAsksCamera_thenNavigatesAfterCameraGranted() = runTest {
        val vm = vm()
        val navs = collectStartNavigation(vm)
        vm.onStartClick(StartPermission.REQUEST_NOTIFICATION)
        vm.onPermissionRequestLaunched()
        vm.onPermissionResult(true, StartPermission.REQUEST_CAMERA)
        assertTrue(navs.isEmpty())
        assertEquals(StartPrompt.REQUEST_CAMERA, vm.startPrompt.value)
        vm.onPermissionRequestLaunched()
        vm.onPermissionResult(true, StartPermission.PROCEED)
        assertEquals(listOf<StartNavigation>(StartNavigation.OpenClips), navs)
    }

    @Test
    fun fab_notificationDenied_showsDenied_doesNotNavigate_andCanRestart() = runTest {
        val vm = vm()
        val navs = collectStartNavigation(vm)
        vm.onStartClick(StartPermission.REQUEST_NOTIFICATION)
        vm.onPermissionRequestLaunched()
        vm.onPermissionResult(false, StartPermission.REQUEST_NOTIFICATION)
        assertTrue(navs.isEmpty())
        assertEquals(StartPrompt.NOTIFICATION_DENIED, vm.startPrompt.value)
        vm.dismissStartPrompt()
        // pendingAction 已清掉：能重新開始
        vm.onStartClick(StartPermission.REQUEST_NOTIFICATION)
        assertEquals(StartPrompt.REQUEST_NOTIFICATION, vm.startPrompt.value)
        vm.onPermissionRequestLaunched()
        vm.onPermissionResult(true, StartPermission.REQUEST_CAMERA)
        vm.onPermissionRequestLaunched()
        vm.onPermissionResult(true, StartPermission.PROCEED)
        assertEquals(listOf<StartNavigation>(StartNavigation.OpenClips), navs)
    }

    @Test
    fun fab_notificationDenied_permanently_showsNotificationSettings() = runTest {
        val vm = vm()
        val navs = collectStartNavigation(vm)
        vm.onStartClick(StartPermission.REQUEST_NOTIFICATION)
        vm.onPermissionRequestLaunched()
        vm.onPermissionResult(false, StartPermission.NOTIFICATION_SETTINGS)
        assertTrue(navs.isEmpty())
        assertEquals(StartPrompt.NOTIFICATION_SETTINGS, vm.startPrompt.value)
    }

    @Test
    fun fab_cameraDenied_showsDenied_doesNotNavigate_andCanRestart() = runTest {
        val vm = vm()
        val navs = collectStartNavigation(vm)
        vm.onStartClick(StartPermission.REQUEST_CAMERA)
        vm.onPermissionRequestLaunched()
        vm.onPermissionResult(false, StartPermission.REQUEST_CAMERA)
        assertTrue(navs.isEmpty())
        assertEquals(StartPrompt.CAMERA_DENIED, vm.startPrompt.value)
        vm.dismissStartPrompt()
        vm.onStartClick(StartPermission.PROCEED)
        assertEquals(listOf<StartNavigation>(StartNavigation.OpenClips), navs)
    }

    @Test
    fun fab_cameraDenied_permanently_showsCameraSettings() = runTest {
        val vm = vm()
        val navs = collectStartNavigation(vm)
        vm.onStartClick(StartPermission.REQUEST_CAMERA)
        vm.onPermissionRequestLaunched()
        vm.onPermissionResult(false, StartPermission.CAMERA_SETTINGS)
        assertTrue(navs.isEmpty())
        assertEquals(StartPrompt.CAMERA_SETTINGS, vm.startPrompt.value)
    }

    @Test
    fun settings_doesNotNavigate_andLaterProceedResultDoesNotAutoContinue() = runTest {
        val vm = vm()
        val navs = collectStartNavigation(vm)
        vm.onStartClick(StartPermission.NOTIFICATION_SETTINGS)
        assertEquals(StartPrompt.NOTIFICATION_SETTINGS, vm.startPrompt.value)
        vm.onPermissionResult(true, StartPermission.PROCEED)
        assertTrue(navs.isEmpty())
        vm.dismissStartPrompt()
        vm.onStartClick(StartPermission.CAMERA_SETTINGS)
        assertEquals(StartPrompt.CAMERA_SETTINGS, vm.startPrompt.value)
        vm.onPermissionResult(true, StartPermission.PROCEED)
        assertTrue(navs.isEmpty())
    }

    @Test
    fun granted_butStillSamePermission_showsSettings_noLoop() = runTest {
        val vm = vm()
        val navs = collectStartNavigation(vm)
        vm.onStartClick(StartPermission.REQUEST_NOTIFICATION)
        vm.onPermissionRequestLaunched()
        vm.onPermissionResult(true, StartPermission.REQUEST_NOTIFICATION)
        assertEquals(StartPrompt.NOTIFICATION_SETTINGS, vm.startPrompt.value)
        assertTrue(navs.isEmpty())
        vm.dismissStartPrompt()
        vm.onStartClick(StartPermission.REQUEST_CAMERA)
        vm.onPermissionRequestLaunched()
        vm.onPermissionResult(true, StartPermission.REQUEST_CAMERA)
        assertEquals(StartPrompt.CAMERA_SETTINGS, vm.startPrompt.value)
        assertTrue(navs.isEmpty())
    }

    @Test
    fun granted_butChannelBlocked_showsNotificationSettings() = runTest {
        val vm = vm()
        val navs = collectStartNavigation(vm)
        vm.onStartClick(StartPermission.REQUEST_NOTIFICATION)
        vm.onPermissionRequestLaunched()
        vm.onPermissionResult(true, StartPermission.NOTIFICATION_SETTINGS)
        assertEquals(StartPrompt.NOTIFICATION_SETTINGS, vm.startPrompt.value)
        assertTrue(navs.isEmpty())
    }

    @Test
    fun permissionResult_afterProcessRecreation_isIgnored() = runTest {
        val vm = vm()
        val navs = collectStartNavigation(vm)
        vm.onPermissionResult(true, StartPermission.PROCEED)
        vm.onPermissionResult(false, StartPermission.REQUEST_NOTIFICATION)
        assertTrue(navs.isEmpty())
        assertEquals(StartPrompt.NONE, vm.startPrompt.value)
    }

    @Test
    fun repeatedTap_whileActionPending_keepsPrompt_andNavigatesOnce() = runTest {
        val vm = vm()
        val navs = collectStartNavigation(vm)
        vm.onStartClick(StartPermission.REQUEST_NOTIFICATION)
        vm.onPermissionRequestLaunched()
        vm.onStartClick(StartPermission.REQUEST_NOTIFICATION) // 第二次被忽略
        assertEquals(StartPrompt.NONE, vm.startPrompt.value)
        vm.onPermissionResult(true, StartPermission.PROCEED)
        assertEquals(listOf<StartNavigation>(StartNavigation.OpenClips), navs)
    }

    // ---- 重新客串 ----

    @Test
    fun restart_permissionDenied_keepsDraft() = runTest {
        pending.rows += expiredPending
        val vm = vm()
        val navs = collectStartNavigation(vm)
        vm.onRestartClick(expiredRow, StartPermission.REQUEST_NOTIFICATION)
        vm.onPermissionRequestLaunched()
        advanceUntilIdle()
        assertEquals(1, pending.rows.size) // 有對話框時先不刪
        vm.onPermissionResult(false, StartPermission.REQUEST_NOTIFICATION)
        advanceUntilIdle()
        assertTrue(navs.isEmpty())
        assertEquals(1, pending.rows.size)
        assertEquals(StartPrompt.NOTIFICATION_DENIED, vm.startPrompt.value)
    }

    @Test
    fun restart_quotaZero_showsOnlyQuotaHint_noPrompt_keepsDraft() = runTest {
        pending.rows += expiredPending
        cameos.gets["u3"] = DomainResult.Success(testOrder(CameoStatus.EXPIRED, "u3"))
        repeat(2) { quota.queue.addLast { DomainResult.Success(quotaOf(0)) } }
        val vm = vm()
        val navs = collectStartNavigation(vm)
        vm.onResume()
        advanceUntilIdle()
        vm.onRestartClick(expiredRow, StartPermission.REQUEST_NOTIFICATION)
        advanceUntilIdle()
        assertEquals(StartPrompt.NONE, vm.startPrompt.value)
        assertEquals(quotaOf(0), vm.blockedHint.value)
        assertTrue(navs.isEmpty())
        assertEquals(1, pending.rows.size)
    }

    @Test
    fun restart_allPass_deletesDraftOnlyAfterPermissionGranted_andNavigatesToCamera() = runTest {
        pending.rows += expiredPending
        val vm = vm()
        val navs = collectStartNavigation(vm)
        vm.onRestartClick(expiredRow, StartPermission.REQUEST_CAMERA)
        vm.onPermissionRequestLaunched()
        advanceUntilIdle()
        assertEquals(1, pending.rows.size)
        assertTrue(navs.isEmpty())
        vm.onPermissionResult(true, StartPermission.PROCEED)
        advanceUntilIdle()
        assertTrue(pending.rows.isEmpty())
        assertEquals(listOf<StartNavigation>(StartNavigation.OpenCamera("c3")), navs)
    }

    // ---- 繼續 ----

    @Test
    fun continue_withOrder_neverChecksPermission() = runTest {
        val vm = vm()
        val navs = collectStartNavigation(vm)
        StartPermission.entries.forEach { vm.onContinueClick(createdRow, it) }
        assertEquals(StartPrompt.NONE, vm.startPrompt.value)
        assertEquals(StartPermission.entries.size, navs.size)
        assertTrue(navs.all { it == StartNavigation.Continue(created) })
    }

    @Test
    fun continue_withOrder_navigatesEvenWhenPermissionNotGranted() = runTest {
        val vm = vm()
        val navs = collectStartNavigation(vm)
        vm.onContinueClick(createdRow, StartPermission.NOTIFICATION_SETTINGS)
        assertEquals(listOf<StartNavigation>(StartNavigation.Continue(created)), navs)
        assertEquals(StartPrompt.NONE, vm.startPrompt.value)
    }

    @Test
    fun continue_withoutOrder_proceed_navigates() = runTest {
        val vm = vm()
        val navs = collectStartNavigation(vm)
        vm.onContinueClick(draftRow, StartPermission.PROCEED)
        assertEquals(listOf<StartNavigation>(StartNavigation.Continue(draft)), navs)
    }

    @Test
    fun continue_withoutOrder_checksPermission_deniedDoesNotNavigate() = runTest {
        val vm = vm()
        val navs = collectStartNavigation(vm)
        vm.onContinueClick(draftRow, StartPermission.REQUEST_CAMERA)
        assertEquals(StartPrompt.REQUEST_CAMERA, vm.startPrompt.value)
        vm.onPermissionRequestLaunched()
        vm.onPermissionResult(false, StartPermission.REQUEST_CAMERA)
        assertTrue(navs.isEmpty())
        assertEquals(StartPrompt.CAMERA_DENIED, vm.startPrompt.value)
    }
}
