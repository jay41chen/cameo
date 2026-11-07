package com.kimhit.cameo.ui

import androidx.lifecycle.SavedStateHandle
import com.kimhit.cameo.domain.model.Cameo
import com.kimhit.cameo.domain.model.CameoOrder
import com.kimhit.cameo.domain.model.CameoStatus
import com.kimhit.cameo.domain.model.CameoUpload
import com.kimhit.cameo.domain.model.Clip
import com.kimhit.cameo.domain.model.DomainError
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.model.PendingCameo
import com.kimhit.cameo.domain.model.PendingStep
import com.kimhit.cameo.domain.repository.CameoRepository
import com.kimhit.cameo.domain.usecase.AdvanceCameoUseCase
import com.kimhit.cameo.domain.usecase.AttachCameoPhotoUseCase
import com.kimhit.cameo.domain.usecase.CameoStep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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

/** 可以卡住 getCameo 與 uploadSelfie，驗證執行中的狀態與不重入。 */
private class GatedCameos(val delegate: FakeCameoRepo) : CameoRepository by delegate {
    var getGate: CompletableDeferred<Unit>? = null
    var uploadGate: CompletableDeferred<Unit>? = null
    var submitGate: CompletableDeferred<Unit>? = null
    var getCalls = 0

    override suspend fun getCameo(id: String): DomainResult<CameoOrder> {
        getCalls++
        getGate?.await()
        return delegate.getCameo(id)
    }

    override suspend fun submitCameo(cameoId: String): DomainResult<CameoOrder> {
        submitGate?.await()
        return delegate.submitCameo(cameoId)
    }

    override suspend fun uploadSelfie(url: String, photoPath: String): DomainResult<CameoOrder> {
        uploadGate?.await()
        return delegate.uploadSelfie(url, photoPath)
    }
}

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

    private fun vm(photo: String? = null, handle: SavedStateHandle = SavedStateHandle(mapOf("clientRequestId" to "k1", "photo" to photo))) =
        CameoProgressViewModel(
            handle,
            AdvanceCameoUseCase(gated, pending),
            AttachCameoPhotoUseCase(pending),
            keepAlive,
        )

    private val keepAlive = FakeKeepAlive()

    @Test
    fun start_acquiresWithClientRequestId_beforeAnyWork() = runTest {
        row()
        gated.getGate = CompletableDeferred()
        vm()
        assertEquals(listOf("acquire:k1"), keepAlive.events)
        advanceUntilIdle()
        assertEquals(listOf("acquire:k1"), keepAlive.events)
    }

    @Test
    fun done_queuedOrGenerating_tracksThenReleases_beforeStateIsDone() = runTest {
        listOf(CameoStatus.QUEUED, CameoStatus.GENERATING).forEach { status ->
            pending.rows.clear()
            keepAlive.events.clear()
            row()
            cameos.gets["u1"] = okOrder(status)
            val vm = vm()
            var stateAtRelease: ProgressUiState? = null
            keepAlive.onRelease = { stateAtRelease = vm.state.value }
            advanceUntilIdle()
            assertEquals("status=$status", listOf("acquire:k1", "track:u1", "release:k1"), keepAlive.events)
            assertTrue(vm.state.value is ProgressUiState.Done)
            assertTrue("release 時狀態應還不是 Done：$stateAtRelease", stateAtRelease is ProgressUiState.Working)
        }
    }

    @Test
    fun done_alreadyFinished_doesNotTrack_butReleases() = runTest {
        listOf(CameoStatus.SUCCEEDED, CameoStatus.FAILED, CameoStatus.UNKNOWN).forEach { status ->
            pending.rows.clear()
            keepAlive.events.clear()
            row()
            cameos.gets["u1"] = okOrder(status)
            vm()
            advanceUntilIdle()
            assertEquals("status=$status", listOf("acquire:k1", "release:k1"), keepAlive.events)
        }
    }

    @Test
    fun expired_needPhoto_notCreated_gone_failed_allRelease() = runTest {
        val release = listOf("acquire:k1", "release:k1")
        row()
        cameos.gets["u1"] = okOrder(CameoStatus.EXPIRED)
        vm()
        advanceUntilIdle()
        assertEquals("expired", release, keepAlive.events)

        keepAlive.events.clear()
        pending.rows.clear()
        row(photo = null)
        cameos.gets["u1"] = okOrder(CameoStatus.AWAITING_UPLOAD, upload = true)
        vm()
        advanceUntilIdle()
        assertEquals("needPhoto", release, keepAlive.events)

        keepAlive.events.clear()
        pending.rows.clear()
        row(cameoId = null)
        vm()
        advanceUntilIdle()
        assertEquals("notCreated", release, keepAlive.events)

        keepAlive.events.clear()
        pending.rows.clear()
        vm()
        advanceUntilIdle()
        assertEquals("gone", release, keepAlive.events)

        keepAlive.events.clear()
        pending.rows.clear()
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
            listOf("acquire:k1", "release:k1", "acquire:k1", "track:u1", "release:k1"),
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
        assertEquals(listOf("acquire:k1", "release:k1"), keepAlive.events)
        // Gone
        keepAlive.events.clear()
        pending.rows.clear()
        vm(photo = CAMERA_FILE)
        advanceUntilIdle()
        assertEquals(listOf("acquire:k1", "release:k1"), keepAlive.events)
    }

    @Test
    fun onCleared_releases() = runTest {
        row()
        gated.getGate = CompletableDeferred()
        val vm = vm()
        advanceUntilIdle()
        clearViewModel(vm)
        assertEquals(listOf("acquire:k1", "release:k1"), keepAlive.events)
    }

    @Test
    fun attachSuccess_clearsPhotoArgImmediately_thenRunsToDone() = runTest {
        row(photo = null)
        cameos.gets["u1"] = okOrder(CameoStatus.AWAITING_UPLOAD, upload = true)
        cameos.uploads.addLast(okOrder(CameoStatus.UPLOADED))
        cameos.submits.addLast(okOrder(CameoStatus.QUEUED))
        val handle = SavedStateHandle(mapOf("clientRequestId" to "k1", "photo" to CAMERA_FILE))
        val vm = vm(handle = handle)
        gated.uploadGate = CompletableDeferred() // 卡在上傳，確認附上照片後就已經清掉參數
        advanceUntilIdle()
        assertNull(handle.get<String>("photo"))
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
            AdvanceCameoUseCase(c2, p2),
            AttachCameoPhotoUseCase(p2),
            keepAlive,
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
        val handle = SavedStateHandle(mapOf("clientRequestId" to "k1", "photo" to CAMERA_FILE))
        val vm = vm(handle = handle)
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
