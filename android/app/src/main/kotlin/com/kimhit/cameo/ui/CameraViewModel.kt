package com.kimhit.cameo.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface CameraUiState {
    data object Viewfinder : CameraUiState
    data object Capturing : CameraUiState
    data class Preview(val path: String) : CameraUiState
    data class Error(val message: String) : CameraUiState
}

internal const val CAPTURE_FAILED_MESSAGE = "拍照失敗，請再試一次"

/**
 * 相機頁狀態。不依賴 CameraX；實際拍攝由畫面呼叫 [startCapture] 取得路徑後交給 CameraX，
 * 結果再回報 [onCaptured] 或 [onCaptureFailed]。
 * 不在 onCleared 刪暫存：使用者按「使用這張」離開後，確認頁還要用這個檔。
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val files: CaptureFiles,
) : ViewModel() {
    private val restored: String? = savedStateHandle.get<String>(PREVIEW_KEY)?.takeIf { files.exists(it) }

    private val _state = MutableStateFlow<CameraUiState>(
        restored?.let { CameraUiState.Preview(it) } ?: CameraUiState.Viewfinder,
    )
    val state: StateFlow<CameraUiState> = _state

    private var capturingPath: String? = null

    init {
        savedStateHandle[PREVIEW_KEY] = restored
        // 進入相機頁：清掉其他舊暫存，只保留目前的預覽
        files.deleteOthers(restored)
    }

    /** 只有 Viewfinder 可以開始拍；回傳要寫入的路徑，不能拍時回 null（避免連按）。 */
    fun startCapture(): String? {
        if (_state.value != CameraUiState.Viewfinder) return null
        val path = files.newFile()
        capturingPath = path
        _state.value = CameraUiState.Capturing
        return path
    }

    fun onCaptured(path: String) {
        capturingPath = null
        savedStateHandle[PREVIEW_KEY] = path
        _state.value = CameraUiState.Preview(path)
    }

    fun onCaptureFailed() {
        capturingPath?.let(files::delete)
        capturingPath = null
        _state.value = CameraUiState.Error(CAPTURE_FAILED_MESSAGE)
    }

    /** 重拍：刪掉目前的暫存照片，回到取景。拍照失敗後也用它回到取景。 */
    fun retake() {
        if (_state.value == CameraUiState.Capturing) return
        (_state.value as? CameraUiState.Preview)?.let { files.delete(it.path) }
        savedStateHandle[PREVIEW_KEY] = null
        _state.value = CameraUiState.Viewfinder
    }
}

private const val PREVIEW_KEY = "preview"
