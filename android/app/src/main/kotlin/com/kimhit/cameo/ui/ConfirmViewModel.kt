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
    data class Created(val order: CameoOrder) : ConfirmUiState
    data class Rejected(val message: String?) : ConfirmUiState
    data class Expired(val clip: Clip, val title: String?, val message: String?) : ConfirmUiState
}

@HiltViewModel
class ConfirmViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getClips: GetClipsUseCase,
    private val confirmCameo: ConfirmCameoUseCase,
) : ViewModel() {
    private val clipId: String = checkNotNull(savedStateHandle["clipId"])

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
        viewModelScope.launch {
            _state.value = when (val r = confirmCameo(current.clip.id)) {
                is DomainResult.Success ->
                    if (r.value.cameo.status == CameoStatus.EXPIRED) {
                        ConfirmUiState.Expired(current.clip, r.value.cameo.displayTitle, r.value.cameo.displayMessage)
                    } else {
                        ConfirmUiState.Created(r.value)
                    }
                is DomainResult.Failure ->
                    if (r.error.retryable) {
                        current.copy(submitting = false, error = r.error.message, failed = true)
                    } else {
                        ConfirmUiState.Rejected(r.error.message)
                    }
            }
        }
    }

    /** Expired 後重新客串；草稿已被 use case 刪除，下次確認會產生新 key。 */
    fun restart() {
        val current = _state.value as? ConfirmUiState.Expired ?: return
        _state.value = ConfirmUiState.Ready(current.clip)
    }
}
