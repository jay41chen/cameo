package io.github.jay41chen.cameo.ui

import androidx.lifecycle.SavedStateHandle
import io.github.jay41chen.cameo.domain.model.Cameo
import io.github.jay41chen.cameo.domain.model.CameoOrder
import io.github.jay41chen.cameo.domain.model.CameoStatus
import io.github.jay41chen.cameo.domain.model.Clip
import io.github.jay41chen.cameo.domain.model.DomainResult
import io.github.jay41chen.cameo.domain.model.PendingCameo
import io.github.jay41chen.cameo.domain.model.PendingStep
import io.github.jay41chen.cameo.domain.usecase.AdvanceCameoUseCase
import io.github.jay41chen.cameo.domain.usecase.AttachCameoPhotoUseCase
import io.github.jay41chen.cameo.domain.usecase.ConfirmCameoUseCase
import io.github.jay41chen.cameo.submission.SubmissionRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelTest {
    private val files = FakeCaptureFiles()
    private val pending = FakePendingRepo()
    private val cameos = FakeCameoRepo()

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    // 用 Main（= runTest 同一個 scheduler），工作照正式環境的順序排程，不會在 launch 回傳前就跑完
    private val runner by lazy {
        SubmissionRunner(
            ConfirmCameoUseCase(cameos, pending),
            AdvanceCameoUseCase(cameos, pending),
            AttachCameoPhotoUseCase(pending),
            FakeKeepAlive(),
            CoroutineScope(SupervisorJob() + Dispatchers.Main),
        )
    }
    private fun vm(handle: SavedStateHandle = SavedStateHandle()) = CameraViewModel(handle, files, runner)

    @Test
    fun startsAtViewfinder_andCleansOldCaptures() {
        files.existing += "/cache/captures/old.jpg"
        val vm = vm()
        assertEquals(CameraUiState.Viewfinder, vm.state.value)
        assertEquals(listOf("/cache/captures/old.jpg"), files.deleted)
        assertNull(files.keptByCleanup)
    }

    @Test
    fun capture_flowsToPreview_andSavesPath() {
        val handle = SavedStateHandle()
        val vm = vm(handle)
        val path = vm.startCapture()!!
        assertEquals(CameraUiState.Capturing, vm.state.value)
        assertNull(vm.startCapture()) // 拍攝中不能再拍
        vm.onCaptured(path)
        assertEquals(CameraUiState.Preview(path), vm.state.value)
        assertEquals(path, handle.get<String>("preview"))
    }

    @Test
    fun retake_deletesTempFile_andReturnsToViewfinder() {
        val handle = SavedStateHandle()
        val vm = vm(handle)
        val path = vm.startCapture()!!
        vm.onCaptured(path)
        vm.retake()
        assertEquals(CameraUiState.Viewfinder, vm.state.value)
        assertTrue(path in files.deleted)
        assertFalse(files.exists(path))
        assertNull(handle.get<String>("preview"))
        assertTrue(vm.startCapture() != null)
    }

    @Test
    fun captureFailure_showsError_deletesPartialFile_andRetakeRecovers() {
        val vm = vm()
        val path = vm.startCapture()!!
        vm.onCaptureFailed()
        assertEquals(CameraUiState.Error("拍照失敗，請再試一次"), vm.state.value)
        assertTrue(path in files.deleted)
        vm.retake()
        assertEquals(CameraUiState.Viewfinder, vm.state.value)
    }

    @Test
    fun restoresPreviewFromSavedState_andKeepsItWhenCleaning() {
        val kept = "/cache/captures/kept.jpg"
        files.existing += kept
        files.existing += "/cache/captures/other.jpg"
        val vm = vm(SavedStateHandle(mapOf("preview" to kept)))
        assertEquals(CameraUiState.Preview(kept), vm.state.value)
        assertEquals(kept, files.keptByCleanup)
        assertTrue(files.exists(kept))
        assertEquals(listOf("/cache/captures/other.jpg"), files.deleted)
    }

    @Test
    fun savedPreviewWhoseFileIsGone_fallsBackToViewfinder() {
        val handle = SavedStateHandle(mapOf("preview" to "/cache/captures/gone.jpg"))
        val vm = vm(handle)
        assertEquals(CameraUiState.Viewfinder, vm.state.value)
        assertNull(handle.get<String>("preview"))
    }

    @Test
    fun usePhoto_withRequest_handsPathToRunnerOnce() = runTest {
        pending.rows += PendingCameo("k1", "c1", "u1", PendingStep.UPLOAD, null, null)
        cameos.gets["u1"] = DomainResult.Success(
            CameoOrder(Cameo("u1", Clip("c1", "t", 10), CameoStatus.QUEUED, null, null), null),
        )
        val vm = vm(SavedStateHandle(mapOf("request" to "k1")))
        vm.onUsePhoto("/cache/captures/a.jpg")
        advanceUntilIdle() // 第一次推進跑完，runner 已不在跑
        assertFalse(runner.isAdvancing("k1"))
        vm.onUsePhoto("/cache/captures/b.jpg") // 再點：只能被 photoHandedOff 旗標擋下
        advanceUntilIdle()
        assertEquals(listOf("k1" to "/cache/captures/a.jpg"), pending.attached)
        assertEquals(listOf("get"), cameos.calls)
    }

    @Test
    fun usePhoto_withoutRequest_doesNotAdvance() = runTest {
        val vm = vm()
        vm.onUsePhoto("/cache/captures/a.jpg")
        advanceUntilIdle()
        assertTrue(pending.attached.isEmpty())
        assertTrue(cameos.calls.isEmpty())
        assertFalse(runner.isAdvancing("k1"))
    }
}

class CameoNavTest {
    private fun pending(photo: String?) = PendingCameo("k1", "c1", null, PendingStep.CREATE, "err", photo)

    @Test
    fun continue_withPhoto_goesToConfirmWithoutPhotoArg() {
        assertEquals("confirm/c1", continueRoute(pending("/files/selfies/k1.jpg")))
    }

    @Test
    fun continue_withoutPhoto_goesToCamera() {
        assertEquals("camera/c1", continueRoute(pending(null)))
    }

    @Test
    fun confirmRoute_encodesPhotoPath() {
        assertEquals("confirm/c1?photo=%2Fcache%2Fcaptures%2Fa%20b.jpg", confirmRoute("c1", "/cache/captures/a b.jpg"))
        assertEquals("confirm/c1", confirmRoute("c1"))
    }

    @Test
    fun continue_withCameoId_goesToProgress_regardlessOfPhoto() {
        val created = PendingCameo("k1", "c1", "u1", PendingStep.UPLOAD, null, "/files/selfies/k1.jpg")
        assertEquals("cameo/k1", continueRoute(created))
        assertEquals("cameo/k1", continueRoute(created.copy(photoPath = null)))
    }

    @Test
    fun cameoAndCameraRoutes_encodeArguments() {
        assertEquals("cameo/k1", cameoRoute("k1"))
        assertEquals("camera/c1?request=k1", cameraRoute("c1", "k1"))
        assertEquals("camera/c1", cameraRoute("c1"))
    }

    private fun orderWith(status: CameoStatus) = CameoOrder(
        Cameo("u1", Clip("c1", "雨夜重逢", 10), status, null, null),
        null,
    )

    @Test
    fun canContinue_onlyWhileAwaitingUploadOrUploaded() {
        assertTrue(canContinueOrder(orderWith(CameoStatus.AWAITING_UPLOAD)))
        assertTrue(canContinueOrder(orderWith(CameoStatus.UPLOADED)))
        for (s in listOf(CameoStatus.QUEUED, CameoStatus.GENERATING, CameoStatus.SUCCEEDED, CameoStatus.FAILED, CameoStatus.EXPIRED, CameoStatus.UNKNOWN)) {
            assertFalse("$s", canContinueOrder(orderWith(s)))
        }
        assertFalse(canContinueOrder(null))
    }
}
