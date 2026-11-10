package io.github.jay41chen.cameo.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jay41chen.cameo.domain.model.CameoOrder
import io.github.jay41chen.cameo.domain.usecase.AdvanceResult
import io.github.jay41chen.cameo.domain.usecase.CameoStep
import io.github.jay41chen.cameo.submission.AdvanceRun
import io.github.jay41chen.cameo.submission.SubmissionRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
 * 進度頁只觀察 [SubmissionRunner.advanceState]，離開畫面不會取消。
 * 推進由使用者動作觸發：確認支付、「繼續」、重拍「使用這張」、「重試」。
 * 進入時呼叫 [SubmissionRunner.resume]，只在本程序沒有這筆狀態（程序被殺重建）時接續。
 */
@HiltViewModel
class CameoProgressViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val runner: SubmissionRunner,
) : ViewModel() {
    private val clientRequestId: String = checkNotNull(savedStateHandle["clientRequestId"])

    private val _state = MutableStateFlow<ProgressUiState>(ProgressUiState.Working(null))
    val state: StateFlow<ProgressUiState> = _state

    init {
        runner.resume(clientRequestId)
        viewModelScope.launch {
            runner.advanceState(clientRequestId).collect { run ->
                when (run) {
                    null -> Unit
                    is AdvanceRun.Working -> _state.value = ProgressUiState.Working(run.step)
                    is AdvanceRun.Finished -> _state.value = when (val r = run.result) {
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
    }

    /** 執行中不重入。 */
    fun retry() {
        if (runner.isAdvancing(clientRequestId)) return
        runner.advance(clientRequestId, null)
    }
}
