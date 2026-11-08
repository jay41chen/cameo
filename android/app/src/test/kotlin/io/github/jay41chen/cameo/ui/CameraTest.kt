package io.github.jay41chen.cameo.ui

import androidx.lifecycle.SavedStateHandle
import io.github.jay41chen.cameo.domain.model.Cameo
import io.github.jay41chen.cameo.domain.model.CameoOrder
import io.github.jay41chen.cameo.domain.model.CameoStatus
import io.github.jay41chen.cameo.domain.model.Clip
import io.github.jay41chen.cameo.domain.model.PendingCameo
import io.github.jay41chen.cameo.domain.model.PendingStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraViewModelTest {
    private val files = FakeCaptureFiles()
    private fun vm(handle: SavedStateHandle = SavedStateHandle()) = CameraViewModel(handle, files)

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
        assertEquals("cameo/k1?photo=%2Fcache%2Fa%20b.jpg", cameoRoute("k1", "/cache/a b.jpg"))
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
