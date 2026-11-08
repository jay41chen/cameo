package com.kimhit.cameo.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kimhit.cameo.data.network.DataConfig
import com.kimhit.cameo.domain.model.Cameo
import com.kimhit.cameo.domain.model.CameoShare
import com.kimhit.cameo.domain.model.CameoStatus
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.usecase.GetCameoUseCase
import com.kimhit.cameo.domain.usecase.ShareCameoUseCase
import com.kimhit.cameo.domain.usecase.UnshareCameoUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

internal const val CODE_CAMEO_NOT_FOUND = "CAMEO_NOT_FOUND"
internal const val CODE_NOT_SHAREABLE = "NOT_SHAREABLE"

sealed interface VideoUiState {
    data object Loading : VideoUiState
    data class LoadError(val message: String) : VideoUiState
    data object NotFound : VideoUiState
    data class NotReady(val title: String?, val message: String?) : VideoUiState

    /** [shareUrlToSend] 只放 PUT 回應的網址，畫面開完分享選單後由 `onShareSent()` 清掉。 */
    data class Ready(
        val cameo: Cameo,
        val busy: Boolean = false,
        val message: String? = null,
        val shareUrlToSend: String? = null,
    ) : VideoUiState
}

/** 進頁重查訂單，分享狀態以後端為準；離開頁面時請求隨 [viewModelScope] 取消。 */
@HiltViewModel
class VideoViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getCameo: GetCameoUseCase,
    private val shareCameo: ShareCameoUseCase,
    private val unshareCameo: UnshareCameoUseCase,
    config: DataConfig,
    /** 給畫面建立 ExoPlayer 的 OkHttpDataSource.Factory，帶登入憑證。 */
    val okHttpClient: OkHttpClient,
) : ViewModel() {
    private val cameoId: String = checkNotNull(savedStateHandle["cameoId"])

    val videoUrl: String = "${config.baseUrl.trimEnd('/')}/v1/cameos/$cameoId/video"

    private val _state = MutableStateFlow<VideoUiState>(VideoUiState.Loading)
    val state: StateFlow<VideoUiState> = _state

    init {
        load()
    }

    fun retry() {
        _state.value = VideoUiState.Loading
        load()
    }

    private fun load(messageIfReady: String? = null) {
        viewModelScope.launch { _state.value = fetch(messageIfReady) }
    }

    private suspend fun fetch(messageIfReady: String? = null): VideoUiState = when (val r = getCameo(cameoId)) {
        is DomainResult.Success ->
            if (r.value.cameo.status == CameoStatus.SUCCEEDED) {
                VideoUiState.Ready(r.value.cameo, message = messageIfReady)
            } else {
                VideoUiState.NotReady(r.value.cameo.displayTitle, r.value.cameo.displayMessage)
            }
        is DomainResult.Failure ->
            if (r.error.code == CODE_CAMEO_NOT_FOUND) VideoUiState.NotFound
            else VideoUiState.LoadError(r.error.message ?: GENERIC_ERROR)
    }

    fun share() {
        val s = _state.value as? VideoUiState.Ready ?: return
        if (s.busy) return
        _state.value = s.copy(busy = true, message = null)
        viewModelScope.launch {
            when (val r = shareCameo(cameoId)) {
                is DomainResult.Success -> {
                    val url = r.value.url
                    if (url.isNullOrEmpty()) {
                        updateReady { it.copy(busy = false, message = GENERIC_ERROR) }
                    } else {
                        updateReady { it.copy(cameo = it.cameo.copy(share = r.value), busy = false, shareUrlToSend = url) }
                    }
                }
                is DomainResult.Failure ->
                    if (r.error.code == CODE_NOT_SHAREABLE) {
                        _state.value = fetch(r.error.message ?: GENERIC_ERROR)
                    } else {
                        updateReady { it.copy(busy = false, message = r.error.message ?: GENERIC_ERROR) }
                    }
            }
        }
    }

    fun unshare() {
        val s = _state.value as? VideoUiState.Ready ?: return
        if (s.busy || s.cameo.share?.active != true) return
        _state.value = s.copy(busy = true, message = null)
        viewModelScope.launch {
            when (val r = unshareCameo(cameoId)) {
                is DomainResult.Success ->
                    updateReady { it.copy(cameo = it.cameo.copy(share = CameoShare(false, null)), busy = false) }
                is DomainResult.Failure ->
                    updateReady { it.copy(busy = false, message = r.error.message ?: GENERIC_ERROR) }
            }
        }
    }

    fun onShareSent() = updateReady { it.copy(shareUrlToSend = null) }

    fun dismissMessage() = updateReady { it.copy(message = null) }

    private fun updateReady(transform: (VideoUiState.Ready) -> VideoUiState.Ready) {
        _state.update { if (it is VideoUiState.Ready) transform(it) else it }
    }
}
