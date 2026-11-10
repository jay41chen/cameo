package io.github.jay41chen.cameo.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jay41chen.cameo.domain.model.CameoOrder
import io.github.jay41chen.cameo.domain.model.CameoStatus
import io.github.jay41chen.cameo.domain.model.Clip
import io.github.jay41chen.cameo.domain.model.DomainResult
import io.github.jay41chen.cameo.domain.usecase.GetClipsUseCase
import io.github.jay41chen.cameo.domain.usecase.CreatedCameo
import io.github.jay41chen.cameo.domain.usecase.PHOTO_MISSING
import io.github.jay41chen.cameo.submission.ConfirmRun
import io.github.jay41chen.cameo.submission.SubmissionRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
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

/**
 * 確認頁。按下確認後的建單、上傳、送出都由程序層級的 [SubmissionRunner] 執行，離開頁面不會取消；
 * 這裡只觀察 runner 的 [SubmissionRunner.confirmState] 並對應成畫面狀態。
 * keepAlive 也由 runner 負責，ViewModel 不再持有。
 */
@HiltViewModel
class ConfirmViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getClips: GetClipsUseCase,
    private val runner: SubmissionRunner,
) : ViewModel() {
    private val clipId: String = checkNotNull(savedStateHandle["clipId"])

    /** 這個 ViewModel 正在等 runner 的結果；沒等的時候，runner 留下的 Finished 是上一次的，不能套用。 */
    private var awaiting = runner.confirmState(clipId).value is ConfirmRun.Running

    private val _state = MutableStateFlow<ConfirmUiState>(ConfirmUiState.Loading)
    val state: StateFlow<ConfirmUiState> = _state

    private var observeJob: Job? = null

    init {
        load()
    }

    fun load() {
        observeJob?.cancel()
        _state.value = ConfirmUiState.Loading
        viewModelScope.launch {
            when (val r = getClips()) {
                is DomainResult.Success -> {
                    val clip = r.value.firstOrNull { it.id == clipId }
                    if (clip == null) {
                        _state.value = ConfirmUiState.Rejected("片段不存在")
                    } else {
                        _state.value = ConfirmUiState.Ready(clip, submitting = awaiting)
                        observe()
                    }
                }
                is DomainResult.Failure -> _state.value = ConfirmUiState.LoadError(r.error.message)
            }
        }
    }

    private fun observe() {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            runner.confirmState(clipId).collect { run ->
                when (run) {
                    null -> Unit
                    ConfirmRun.Running -> (_state.value as? ConfirmUiState.Ready)?.let {
                        _state.value = it.copy(submitting = true, error = null)
                    }
                    is ConfirmRun.Finished -> if (awaiting) applyResult(run.result)
                }
            }
        }
    }

    private fun applyResult(result: DomainResult<CreatedCameo>) {
        // [待確認] 這個分支目前走不到：收集只在 Ready 之後才開始，而 load() 會先取消收集。
        // 這裡不是 Ready 就沒有 clip 可對應，保留 awaiting 為 true 是防禦性寫法。
        val current = _state.value as? ConfirmUiState.Ready ?: return
        awaiting = false
        val expired = (result as? DomainResult.Success)?.value?.order?.cameo?.status == CameoStatus.EXPIRED
        _state.value = when (result) {
            is DomainResult.Success ->
                if (expired) {
                    ConfirmUiState.Expired(
                        current.clip,
                        result.value.order.cameo.displayTitle,
                        result.value.order.cameo.displayMessage,
                    )
                } else {
                    ConfirmUiState.Created(result.value.clientRequestId, result.value.order)
                }
            is DomainResult.Failure ->
                if (result.error.code == PHOTO_MISSING) {
                    ConfirmUiState.PhotoMissing
                } else if (result.error.retryable) {
                    current.copy(submitting = false, error = result.error.message, failed = true)
                } else {
                    ConfirmUiState.Rejected(result.error.message)
                }
        }
    }

    /** 交給程序層級的 runner；狀態更新由 [observe] 處理。離開頁面不會取消。 */
    fun confirm() {
        val current = _state.value as? ConfirmUiState.Ready ?: return
        if (current.submitting) return
        awaiting = true
        runner.confirm(clipId)
    }
}
