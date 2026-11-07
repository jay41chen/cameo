package com.kimhit.cameo.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kimhit.cameo.domain.model.CameoOrder
import com.kimhit.cameo.domain.usecase.AdvanceCameoUseCase
import com.kimhit.cameo.domain.usecase.AdvanceResult
import com.kimhit.cameo.domain.usecase.AttachCameoPhotoUseCase
import com.kimhit.cameo.domain.usecase.AttachResult
import com.kimhit.cameo.domain.usecase.CameoStep
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface ProgressUiState {
    data class Working(val step: CameoStep?) : ProgressUiState
    data class Done(val order: CameoOrder) : ProgressUiState
    data class Expired(val title: String?, val message: String?) : ProgressUiState
    data class NeedPhoto(val clipId: String, val clientRequestId: String) : ProgressUiState
    data class Failed(val message: String?, val retryable: Boolean) : ProgressUiState
    data class NotCreated(val clipId: String) : ProgressUiState
    data object Gone : ProgressUiState
}

/** 進度頁的按鈕。 */
internal enum class ProgressAction { Retry, Retake, BackHome, RestartFromHome }

internal fun progressActions(state: ProgressUiState): List<ProgressAction> = when (state) {
    is ProgressUiState.Failed ->
        if (state.retryable) listOf(ProgressAction.Retry, ProgressAction.BackHome) else listOf(ProgressAction.BackHome)
    is ProgressUiState.NeedPhoto -> listOf(ProgressAction.Retake, ProgressAction.BackHome)
    is ProgressUiState.Expired -> listOf(ProgressAction.RestartFromHome)
    is ProgressUiState.Working, is ProgressUiState.Done, is ProgressUiState.NotCreated, ProgressUiState.Gone -> emptyList()
}

/**
 * 進入就開始上傳與送出；離開畫面（ViewModel 清除）會取消請求，草稿保留。
 * 重拍後帶 `photo` 參數回來：先附到草稿，附上成功就立刻清掉參數，避免重建時再附一次。
 */
@HiltViewModel
class CameoProgressViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val advance: AdvanceCameoUseCase,
    private val attachPhoto: AttachCameoPhotoUseCase,
) : ViewModel() {
    private val clientRequestId: String = checkNotNull(savedStateHandle["clientRequestId"])
    private var photo: String? = savedStateHandle[PHOTO_KEY]

    private val _state = MutableStateFlow<ProgressUiState>(ProgressUiState.Working(null))
    val state: StateFlow<ProgressUiState> = _state

    private var job: Job? = null

    init {
        start()
    }

    /** 執行中不重入。 */
    fun retry() {
        if (job?.isActive == true) return
        start()
    }

    private fun start() {
        _state.value = ProgressUiState.Working(null)
        job = viewModelScope.launch {
            photo?.let { source ->
                when (val a = attachPhoto(clientRequestId, source)) {
                    AttachResult.Attached -> {
                        photo = null
                        savedStateHandle[PHOTO_KEY] = null
                    }
                    is AttachResult.NeedPhoto -> {
                        _state.value = ProgressUiState.NeedPhoto(a.clipId, clientRequestId)
                        return@launch
                    }
                    AttachResult.Gone -> {
                        _state.value = ProgressUiState.Gone
                        return@launch
                    }
                }
            }
            _state.value = when (val r = advance(clientRequestId) { _state.value = ProgressUiState.Working(it) }) {
                is AdvanceResult.Done -> ProgressUiState.Done(r.order)
                is AdvanceResult.Expired -> ProgressUiState.Expired(
                    title = r.order.cameo.displayTitle,
                    message = r.order.cameo.displayMessage,
                )
                is AdvanceResult.NeedPhoto -> ProgressUiState.NeedPhoto(r.clipId, clientRequestId)
                is AdvanceResult.NotCreated -> ProgressUiState.NotCreated(r.clipId)
                AdvanceResult.Gone -> ProgressUiState.Gone
                is AdvanceResult.Failed -> ProgressUiState.Failed(r.error.message, r.error.retryable)
            }
        }
    }
}

private const val PHOTO_KEY = "photo"
