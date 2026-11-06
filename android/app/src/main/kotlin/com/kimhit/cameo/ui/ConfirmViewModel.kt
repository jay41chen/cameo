package com.kimhit.cameo.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kimhit.cameo.domain.model.CameoOrder
import com.kimhit.cameo.domain.model.CameoStatus
import com.kimhit.cameo.domain.model.Clip
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.usecase.ConfirmCameoUseCase
import com.kimhit.cameo.domain.usecase.GetClipsUseCase
import com.kimhit.cameo.domain.usecase.PHOTO_MISSING
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface ConfirmUiState {
    data object Loading : ConfirmUiState
    data class LoadError(val message: String?) : ConfirmUiState
    data class Ready(
        val clip: Clip,
        val submitting: Boolean = false,
        val error: String? = null,
        val failed: Boolean = false,
    ) : ConfirmUiState
    data class Created(val clientRequestId: String, val order: CameoOrder) : ConfirmUiState
    data class Rejected(val message: String?) : ConfirmUiState
    data class Expired(val clip: Clip, val title: String?, val message: String?) : ConfirmUiState
    /** 本機照片遺失，需要重新拍照。 */
    data object PhotoMissing : ConfirmUiState
}

/** 確認頁在建單前要做的通知相關動作；[NONE] 表示沒有。 */
enum class ConfirmPrompt { NONE, REQUEST_PERMISSION, NEED_SETTINGS }

@HiltViewModel
class ConfirmViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val getClips: GetClipsUseCase,
    private val confirmCameo: ConfirmCameoUseCase,
) : ViewModel() {
    private val clipId: String = checkNotNull(savedStateHandle["clipId"])

    /**
     * 相機暫存檔路徑。第一次確認（PHOTO_MISSING 以外的結果）之後檔案已被移走，
     * 之後的重試改用草稿已有的照片，所以清掉。
     */
    private var photo: String? = savedStateHandle[PHOTO_KEY]

    private val _state = MutableStateFlow<ConfirmUiState>(ConfirmUiState.Loading)
    val state: StateFlow<ConfirmUiState> = _state

    private val _prompt = MutableStateFlow(ConfirmPrompt.NONE)
    val prompt: StateFlow<ConfirmPrompt> = _prompt

    init {
        load()
    }

    fun load() {
        _state.value = ConfirmUiState.Loading
        viewModelScope.launch {
            _state.value = when (val r = getClips()) {
                is DomainResult.Success -> {
                    val clip = r.value.firstOrNull { it.id == clipId }
                    if (clip == null) ConfirmUiState.Rejected("片段不存在") else ConfirmUiState.Ready(clip)
                }
                is DomainResult.Failure -> ConfirmUiState.LoadError(r.error.message)
            }
        }
    }

    /** 按「確認支付」：通知檢查通過才建單，否則只改 [prompt]、不呼叫建單。 */
    fun onConfirmClick(gate: NotificationGate) {
        val current = _state.value as? ConfirmUiState.Ready ?: return
        if (current.submitting) return
        when (gate) {
            NotificationGate.PROCEED -> confirm()
            NotificationGate.REQUEST_PERMISSION -> _prompt.value = ConfirmPrompt.REQUEST_PERMISSION
            NotificationGate.NEED_SETTINGS -> _prompt.value = ConfirmPrompt.NEED_SETTINGS
        }
    }

    /** 畫面已送出權限請求。 */
    fun onPermissionRequestLaunched() {
        if (_prompt.value == ConfirmPrompt.REQUEST_PERMISSION) _prompt.value = ConfirmPrompt.NONE
    }

    /**
     * 權限對話框結果：拒絕停在確認頁並顯示前往設定的提示。
     * 同意時用重新取得的 [gate]（權限可能有了、但頻道仍被關）再走 [onConfirmClick]；
     * 不會再要求一次權限，所以 [NotificationGate.REQUEST_PERMISSION] 視同 [NotificationGate.NEED_SETTINGS]。
     */
    fun onPermissionResult(granted: Boolean, gate: NotificationGate) {
        if (!granted) {
            _prompt.value = ConfirmPrompt.NEED_SETTINGS
            return
        }
        onConfirmClick(if (gate == NotificationGate.REQUEST_PERMISSION) NotificationGate.NEED_SETTINGS else gate)
    }

    fun dismissPrompt() {
        _prompt.value = ConfirmPrompt.NONE
    }

    fun confirm() {
        val current = _state.value as? ConfirmUiState.Ready ?: return
        if (current.submitting) return
        _state.value = current.copy(submitting = true, error = null)
        viewModelScope.launch {
            val result = confirmCameo(current.clip.id, photo)
            if (!(result is DomainResult.Failure && result.error.code == PHOTO_MISSING)) {
                photo = null
                savedStateHandle[PHOTO_KEY] = null
            }
            _state.value = when (val r = result) {
                is DomainResult.Success ->
                    if (r.value.order.cameo.status == CameoStatus.EXPIRED) {
                        ConfirmUiState.Expired(
                            current.clip,
                            r.value.order.cameo.displayTitle,
                            r.value.order.cameo.displayMessage,
                        )
                    } else {
                        ConfirmUiState.Created(r.value.clientRequestId, r.value.order)
                    }
                is DomainResult.Failure ->
                    if (r.error.code == PHOTO_MISSING) {
                        ConfirmUiState.PhotoMissing
                    } else if (r.error.retryable) {
                        current.copy(submitting = false, error = r.error.message, failed = true)
                    } else {
                        ConfirmUiState.Rejected(r.error.message)
                    }
            }
        }
    }
}

private const val PHOTO_KEY = "photo"
