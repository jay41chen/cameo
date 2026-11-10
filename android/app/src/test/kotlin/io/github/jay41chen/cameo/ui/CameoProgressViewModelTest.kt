package io.github.jay41chen.cameo.ui

import androidx.lifecycle.SavedStateHandle
import io.github.jay41chen.cameo.domain.model.Cameo
import io.github.jay41chen.cameo.domain.model.CameoOrder
import io.github.jay41chen.cameo.domain.model.CameoStatus
import io.github.jay41chen.cameo.domain.model.CameoUpload
import io.github.jay41chen.cameo.domain.model.Clip
import io.github.jay41chen.cameo.domain.model.DomainError
import io.github.jay41chen.cameo.domain.model.DomainResult
import io.github.jay41chen.cameo.domain.model.PendingCameo
import io.github.jay41chen.cameo.domain.model.PendingStep
import io.github.jay41chen.cameo.domain.repository.CameoRepository
import io.github.jay41chen.cameo.domain.repository.PendingCameoRepository
import io.github.jay41chen.cameo.domain.usecase.AdvanceCameoUseCase
import io.github.jay41chen.cameo.domain.usecase.AdvanceResult
import io.github.jay41chen.cameo.domain.usecase.AttachCameoPhotoUseCase
import io.github.jay41chen.cameo.domain.usecase.CameoStep
import io.github.jay41chen.cameo.domain.usecase.ConfirmCameoUseCase
import io.github.jay41chen.cameo.submission.AdvanceRun
import io.github.jay41chen.cameo.submission.SubmissionRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val CAMERA_FILE = "/cache/captures/a.jpg"

private fun orderWith(status: CameoStatus, upload: Boolean = false) = CameoOrder(
    Cameo("u1", Clip("c1", "雨夜重逢", 10), status, "標題", "說明"),
    if (upload) CameoUpload("/v1/cameos/u1/selfie", "2026-11-07T10:30:00+08:00") else null,
)

private fun okOrder(status: CameoStatus, upload: Boolean = false) = DomainResult.Success(orderWith(status, upload))

@OptIn(ExperimentalCoroutinesApi::class)
class CameoProgressViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val pending = FakePendingRepo()
    private val cameos = FakeCameoRepo()
    private val gated = GatedCameos(cameos)

    private fun row(cameoId: String? = "u1", photo: String? = "/photos/k1.jpg") {
        pending.rows += PendingCameo("k1", "c1", cameoId, PendingStep.UPLOAD, null, photo)
    }

    private val keepAlive = FakeKeepAlive()

    private fun runnerFor(
        cameoRepo: CameoRepository,
        pendingRepo: PendingCameoRepository,
    ) = SubmissionRunner(
        ConfirmCameoUseCase(cameoRepo, pendingRepo),
        AdvanceCameoUseCase(cameoRepo, pendingRepo),
        AttachCameoPhotoUseCase(pendingRepo),
        keepAlive,
        CoroutineScope(SupervisorJob() + Dispatchers.Main), // 測試的 Main dispatcher，與 runTest 同一個 scheduler
    )

    private var currentRunner: SubmissionRunner? = null
    private val runner: SubmissionRunner get() = currentRunner ?: runnerFor(gated, pending).also { currentRunner = it }

    /** 模擬程序重建：之後的 ViewModel 用全新的 runner（沒有任何先前的推進狀態）。 */
    private fun newProcess() {
        currentRunner = null
    }

    /** [photo] 不為 null 時，先由 runner 推進並附照片（代表重拍「使用這張」），再建立進度頁觀察。 */
    private fun vm(photo: String? = null): CameoProgressViewModel {
        if (photo != null) runner.advance("k1", photo)
        return CameoProgressViewModel(SavedStateHandle(mapOf("clientRequestId" to "k1")), runner)
    }

    @Test
    fun start_acquiresWithClientRequestId_beforeAnyWork() = runTest {
        row()
        gated.getGate = CompletableDeferred()
        vm()
        assertTrue(runner.isAdvancing("k1")) // 建立時就已同步登記
        advanceUntilIdle()
        assertEquals(listOf("acquire:advance:k1"), keepAlive.events)
    }

    @Test
    fun done_nonTerminal_tracksThenReleases_beforeStateIsDone() = runTest {
        listOf(CameoStatus.QUEUED, CameoStatus.GENERATING, CameoStatus.UNKNOWN).forEach { status ->
            pending.rows.clear()
            newProcess()
            keepAlive.events.clear()
            row()
            cameos.gets["u1"] = okOrder(status)
            val vm = vm()
            advanceUntilIdle()
            assertEquals(
                "status=$status",
                listOf("acquire:advance:k1", "track:u1", "release:advance:k1"),
                keepAlive.events,
            )
            assertTrue(vm.state.value is ProgressUiState.Done)
        }
    }

    @Test
    fun done_alreadyFinished_doesNotTrack_butReleases() = runTest {
        listOf(CameoStatus.SUCCEEDED, CameoStatus.FAILED).forEach { status ->
            pending.rows.clear()
            newProcess()
            keepAlive.events.clear()
            row()
            cameos.gets["u1"] = okOrder(status)
            vm()
            advanceUntilIdle()
            assertEquals("status=$status", listOf("acquire:advance:k1", "release:advance:k1"), keepAlive.events)
        }
    }

    @Test
    fun expired_needPhoto_notCreated_gone_failed_allRelease() = runTest {
        val release = listOf("acquire:advance:k1", "release:advance:k1")
        row()
        cameos.gets["u1"] = okOrder(CameoStatus.EXPIRED)
        vm()
        advanceUntilIdle()
        assertEquals("expired", release, keepAlive.events)

        keepAlive.events.clear()
        pending.rows.clear()
        newProcess()
        row(photo = null)
        cameos.gets["u1"] = okOrder(CameoStatus.AWAITING_UPLOAD, upload = true)
        vm()
        advanceUntilIdle()
        assertEquals("needPhoto", release, keepAlive.events)

        keepAlive.events.clear()
        pending.rows.clear()
        newProcess()
        row(cameoId = null)
        vm()
        advanceUntilIdle()
        assertEquals("notCreated", release, keepAlive.events)

        keepAlive.events.clear()
        pending.rows.clear()
        newProcess()
        vm()
        advanceUntilIdle()
        assertEquals("gone", release, keepAlive.events)

        keepAlive.events.clear()
        pending.rows.clear()
        newProcess()
        row()
        cameos.gets["u1"] = okOrder(CameoStatus.AWAITING_UPLOAD, upload = true)
        cameos.uploads.addLast(DomainResult.Failure(DomainError("網路不穩", true)))
        val failed = vm()
        advanceUntilIdle()
        assertTrue(failed.state.value is ProgressUiState.Failed)
        assertEquals("failed", release, keepAlive.events)
    }

    @Test
    fun retry_acquiresAgain() = runTest {
        row()
        cameos.gets["u1"] = okOrder(CameoStatus.AWAITING_UPLOAD, upload = true)
        cameos.uploads.addLast(DomainResult.Failure(DomainError(null, true)))
        cameos.uploads.addLast(okOrder(CameoStatus.UPLOADED))
        cameos.submits.addLast(okOrder(CameoStatus.QUEUED))
        val vm = vm()
        advanceUntilIdle()
        vm.retry()
        advanceUntilIdle()
        assertEquals(
            listOf(
                "acquire:advance:k1", "release:advance:k1",
                "acquire:advance:k1", "track:u1", "release:advance:k1",
            ),
            keepAlive.events,
        )
    }

    @Test
    fun attachEarlyReturns_release() = runTest {
        // NeedPhoto
        row(photo = null)
        pending.missingSources += CAMERA_FILE
        vm(photo = CAMERA_FILE)
        advanceUntilIdle()
        assertEquals(listOf("acquire:advance:k1", "release:advance:k1"), keepAlive.events)
        // Gone
        keepAlive.events.clear()
        pending.rows.clear()
        newProcess()
        vm(photo = CAMERA_FILE)
        advanceUntilIdle()
        assertEquals(listOf("acquire:advance:k1", "release:advance:k1"), keepAlive.events)
    }

    @Test
    fun clearingViewModelWhileRunning_runnerStillFinishes_releaseIsLast() = runTest {
        row()
        cameos.gets["u1"] = okOrder(CameoStatus.QUEUED)
        val gate = CompletableDeferred<Unit>()
        gated.getGate = gate
        val vm = vm()
        advanceUntilIdle()
        clearViewModel(vm) // 離開進度頁
        advanceUntilIdle()
        assertEquals(listOf("acquire:advance:k1"), keepAlive.events)
        gate.complete(Unit)
        advanceUntilIdle()
        val finished = runner.advanceState("k1").value as AdvanceRun.Finished
        assertTrue(finished.result is AdvanceResult.Done)
        assertEquals(listOf("acquire:advance:k1", "track:u1", "release:advance:k1"), keepAlive.events)
    }

    @Test
    fun photoHandedToRunnerBeforeViewModel_isAttachedOnce() = runTest {
        row(photo = null)
        cameos.gets["u1"] = okOrder(CameoStatus.QUEUED)
        vm(photo = CAMERA_FILE)
        advanceUntilIdle()
        assertEquals(listOf("k1" to CAMERA_FILE), pending.attached)
    }

    @Test
    fun confirmThenFastUploadFailure_thenProgressPage_uploadsOnce_andShowsRetryableFailure() = runTest {
        // 本 bug：建單後 runner 接著推進，上傳快速失敗、工作已結束；之後進度頁才建立，不可再推進一次
        cameos.creates += okOrder(CameoStatus.AWAITING_UPLOAD, upload = true)
        cameos.gets["u1"] = okOrder(CameoStatus.AWAITING_UPLOAD, upload = true)
        cameos.uploads.addLast(DomainResult.Failure(DomainError("網路不穩", true)))
        cameos.uploads.addLast(okOrder(CameoStatus.UPLOADED)) // 若被自動重試，這筆會被吃掉
        cameos.submits.addLast(okOrder(CameoStatus.QUEUED))
        runner.confirm("c1", CAMERA_FILE)
        advanceUntilIdle()
        assertFalse(runner.isAdvancing("key-1"))
        val vm = CameoProgressViewModel(SavedStateHandle(mapOf("clientRequestId" to "key-1")), runner)
        advanceUntilIdle()
        assertEquals(1, cameos.calls.count { it == "upload" })
        assertEquals(ProgressUiState.Failed("網路不穩", true), vm.state.value)
    }

    @Test
    fun freshRunner_simulatingProcessRecreation_autoAdvances() = runTest {
        row()
        cameos.gets["u1"] = okOrder(CameoStatus.QUEUED)
        val vm = CameoProgressViewModel(SavedStateHandle(mapOf("clientRequestId" to "k1")), runnerFor(cameos, pending))
        advanceUntilIdle()
        assertTrue(vm.state.value is ProgressUiState.Done)
        assertEquals(listOf("get"), cameos.calls)
    }

    @Test
    fun staleFinishedFromEarlierRun_isNeverShown_whenContinueAdvancesAgain() = runTest {
        row()
        cameos.gets["u1"] = okOrder(CameoStatus.AWAITING_UPLOAD, upload = true)
        cameos.uploads.addLast(DomainResult.Failure(DomainError("舊的失敗", true)))
        runner.advance("k1", null)
        advanceUntilIdle()
        val old = runner.advanceState("k1").value as AdvanceRun.Finished
        assertTrue(old.result is AdvanceResult.Failed)
        assertEquals(1, gated.getCalls)

        cameos.uploads.addLast(okOrder(CameoStatus.UPLOADED))
        cameos.submits.addLast(okOrder(CameoStatus.QUEUED))
        runner.advance("k1", null) // 「繼續」：由使用者動作觸發推進
        val vm = vm()
        val seen = mutableListOf<ProgressUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.toList(seen) }
        advanceUntilIdle()
        assertTrue("不應看到舊的 Failed：$seen", seen.none { it is ProgressUiState.Failed })
        assertEquals(2, gated.getCalls)
        assertTrue(vm.state.value is ProgressUiState.Done)
    }

    @Test
    fun attachSuccess_thenRunsToDone_attachingOnce() = runTest {
        row(photo = null)
        cameos.gets["u1"] = okOrder(CameoStatus.AWAITING_UPLOAD, upload = true)
        cameos.uploads.addLast(okOrder(CameoStatus.UPLOADED))
        cameos.submits.addLast(okOrder(CameoStatus.QUEUED))
        gated.uploadGate = CompletableDeferred() // 卡在上傳
        val vm = vm(photo = CAMERA_FILE)
        advanceUntilIdle()
        assertEquals(listOf("k1" to CAMERA_FILE), pending.attached)
        assertEquals(ProgressUiState.Working(CameoStep.UPLOADING), vm.state.value)
        gated.uploadGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(CameoStatus.QUEUED, (vm.state.value as ProgressUiState.Done).order.cameo.status)
        assertEquals(1, pending.attached.size)
    }

    @Test
    fun attachFailure_isNeedPhoto_andNeverCallsApi() = runTest {
        row(photo = null)
        pending.missingSources += CAMERA_FILE
        val vm = vm(photo = CAMERA_FILE)
        advanceUntilIdle()
        assertEquals(ProgressUiState.NeedPhoto("c1", "k1"), vm.state.value)
        assertTrue(cameos.calls.isEmpty())
    }

    @Test
    fun attachToMissingRow_isGone() = runTest {
        val vm = vm(photo = CAMERA_FILE)
        advanceUntilIdle()
        assertEquals(ProgressUiState.Gone, vm.state.value)
    }

    @Test
    fun startsAsWorkingWithoutStep() = runTest {
        row()
        gated.getGate = CompletableDeferred()
        val vm = vm()
        assertEquals(ProgressUiState.Working(null), vm.state.value)
        advanceUntilIdle()
        assertEquals(ProgressUiState.Working(null), vm.state.value)
    }

    @Test
    fun expired_mapsToExpiredState() = runTest {
        row()
        cameos.gets["u1"] = okOrder(CameoStatus.EXPIRED)
        val vm = vm()
        advanceUntilIdle()
        assertEquals(ProgressUiState.Expired("標題", "說明"), vm.state.value)
    }

    @Test
    fun submittedAndUnknownStatuses_becomeDone() = runTest {
        listOf(
            CameoStatus.QUEUED,
            CameoStatus.GENERATING,
            CameoStatus.SUCCEEDED,
            CameoStatus.FAILED,
            CameoStatus.UNKNOWN,
        ).forEach { status ->
            pending.rows.clear()
            newProcess()
            row()
            cameos.gets["u1"] = okOrder(status)
            val vm = vm()
            advanceUntilIdle()
            assertEquals("status=$status", ProgressUiState.Done(orderWith(status)), vm.state.value)
        }
    }

    @Test
    fun noCameoId_isNotCreated() = runTest {
        row(cameoId = null)
        val vm = vm()
        advanceUntilIdle()
        assertEquals(ProgressUiState.NotCreated("c1"), vm.state.value)
    }

    @Test
    fun noRow_isGone_andCameoNotFoundIsGone() = runTest {
        assertEquals(ProgressUiState.Gone, vm().also { advanceUntilIdle() }.state.value)
        newProcess()
        row()
        cameos.gets["u1"] = DomainResult.Failure(DomainError("x", false, "CAMEO_NOT_FOUND"))
        val vm2 = vm()
        advanceUntilIdle()
        assertEquals(ProgressUiState.Gone, vm2.state.value)
        assertTrue(pending.rows.isEmpty())
    }

    @Test
    fun awaitingUploadWithoutPhoto_isNeedPhoto() = runTest {
        row(photo = null)
        cameos.gets["u1"] = okOrder(CameoStatus.AWAITING_UPLOAD, upload = true)
        val vm = vm()
        advanceUntilIdle()
        assertEquals(ProgressUiState.NeedPhoto("c1", "k1"), vm.state.value)
    }

    @Test
    fun failures_carryMessageAndRetryable() = runTest {
        row()
        cameos.gets["u1"] = okOrder(CameoStatus.AWAITING_UPLOAD, upload = true)
        cameos.uploads.addLast(DomainResult.Failure(DomainError("網路不穩", true)))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(ProgressUiState.Failed("網路不穩", true), vm.state.value)

        val p2 = FakePendingRepo().apply { rows += PendingCameo("k1", "c1", "u1", PendingStep.UPLOAD, null, "/photos/k1.jpg") }
        val c2 = FakeCameoRepo().apply {
            gets["u1"] = okOrder(CameoStatus.AWAITING_UPLOAD, upload = true)
            uploads.addLast(DomainResult.Failure(DomainError(null, false, "UNAUTHORIZED")))
        }
        val vm2 = CameoProgressViewModel(
            SavedStateHandle(mapOf("clientRequestId" to "k1")),
            runnerFor(c2, p2),
        )
        advanceUntilIdle()
        assertEquals(ProgressUiState.Failed(null, false), vm2.state.value)
    }

    @Test
    fun attachSuccess_thenUploadFails_retryDoesNotAttachAgain_orAskForPhoto() = runTest {
        row(photo = null)
        cameos.gets["u1"] = okOrder(CameoStatus.AWAITING_UPLOAD, upload = true)
        cameos.uploads.addLast(DomainResult.Failure(DomainError("網路不穩", true)))
        cameos.uploads.addLast(okOrder(CameoStatus.UPLOADED))
        cameos.submits.addLast(okOrder(CameoStatus.QUEUED))
        val vm = vm(photo = CAMERA_FILE)
        advanceUntilIdle()
        assertEquals(ProgressUiState.Failed("網路不穩", true), vm.state.value)
        assertEquals(1, pending.attached.size)
        vm.retry()
        advanceUntilIdle()
        assertEquals(1, pending.attached.size)
        assertTrue(vm.state.value !is ProgressUiState.NeedPhoto)
        assertTrue(vm.state.value is ProgressUiState.Done)
    }

    @Test
    fun submitting_isReportedAsStep() = runTest {
        row(photo = null)
        cameos.gets["u1"] = okOrder(CameoStatus.UPLOADED)
        cameos.submits.addLast(okOrder(CameoStatus.QUEUED))
        val submitGate = CompletableDeferred<Unit>()
        gated.submitGate = submitGate
        val vm = vm()
        advanceUntilIdle()
        assertEquals(ProgressUiState.Working(CameoStep.SUBMITTING), vm.state.value)
        submitGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.state.value is ProgressUiState.Done)
        assertEquals(listOf("get", "submit"), cameos.calls)
    }

    @Test
    fun retry_isIgnoredWhileRunning_thenResumesAfterFailure() = runTest {
        row()
        cameos.gets["u1"] = okOrder(CameoStatus.AWAITING_UPLOAD, upload = true)
        cameos.uploads.addLast(DomainResult.Failure(DomainError(null, true)))
        cameos.uploads.addLast(okOrder(CameoStatus.UPLOADED))
        cameos.submits.addLast(okOrder(CameoStatus.QUEUED))
        gated.getGate = CompletableDeferred()
        val vm = vm()
        advanceUntilIdle()
        vm.retry() // 第一輪還在執行
        vm.retry()
        advanceUntilIdle()
        assertEquals(1, gated.getCalls)
        gated.getGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(ProgressUiState.Failed(null, true), vm.state.value)
        vm.retry()
        advanceUntilIdle()
        assertTrue(vm.state.value is ProgressUiState.Done)
        assertEquals(2, gated.getCalls)
    }

    @Test
    fun expired_keepsDraftAndOnlyDeletesPhoto() = runTest {
        row()
        cameos.gets["u1"] = okOrder(CameoStatus.EXPIRED)
        val vm = vm()
        advanceUntilIdle()
        assertTrue(vm.state.value is ProgressUiState.Expired)
        assertEquals(1, pending.rows.size)
        assertNull(pending.rows.single().photoPath)
    }
}
