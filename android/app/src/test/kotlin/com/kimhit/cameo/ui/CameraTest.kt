package com.kimhit.cameo.ui

import androidx.lifecycle.SavedStateHandle
import com.kimhit.cameo.domain.model.PendingCameo
import com.kimhit.cameo.domain.model.PendingStep
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
}
