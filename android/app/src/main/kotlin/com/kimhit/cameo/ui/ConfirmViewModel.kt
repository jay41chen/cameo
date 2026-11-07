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
import com.kimhit.cameo.tracking.SubmissionKeepAlive
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

@HiltViewModel
class ConfirmViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val getClips: GetClipsUseCase,
    private val confirmCameo: ConfirmCameoUseCase,
    private val keepAlive: SubmissionKeepAlive,
) : ViewModel() {
    private val clipId: String = checkNotNull(savedStateHandle["clipId"])
    private val holdKey = "confirm:$clipId"

    /**
     * 相機暫存檔路徑。第一次確認（PHOTO_MISSING 以外的結果）之後檔案已被移走，
     * 之後的重試改用草稿已有的照片，所以清掉。
     */
    private var photo: String? = savedStateHandle[PHOTO_KEY]

    private val _state = MutableStateFlow<ConfirmUiState>(ConfirmUiState.Loading)
    val state: StateFlow<ConfirmUiState> = _state

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

    fun confirm() {
        val current = _state.value as? ConfirmUiState.Ready ?: return
        if (current.submitting) return
        _state.value = current.copy(submitting = true, error = null)
        keepAlive.acquire(holdKey)
        viewModelScope.launch {
            val result = confirmCameo(current.clip.id, photo)
            // Created 不釋放：交給 onCleared，等進度頁取得自己的保持；其餘結果都釋放
            val expired = (result as? DomainResult.Success)?.value?.order?.cameo?.status == CameoStatus.EXPIRED
            val created = result is DomainResult.Success && !expired
            if (!created) keepAlive.release(holdKey)
            if (!(result is DomainResult.Failure && result.error.code == PHOTO_MISSING)) {
                photo = null
                savedStateHandle[PHOTO_KEY] = null
            }
            _state.value = when (val r = result) {
                is DomainResult.Success ->
                    if (expired) {
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

    override fun onCleared() {
        keepAlive.release(holdKey)
    }
}

private const val PHOTO_KEY = "photo"
