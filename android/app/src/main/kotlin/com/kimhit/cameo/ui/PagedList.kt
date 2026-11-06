package com.kimhit.cameo.ui

import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.model.Page
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 分頁列表的載入階段。[Idle] 表示已載入、可能還有下一頁。 */
enum class PagePhase { Loading, Idle, Error, LoadingMore, LoadMoreError }

/**
 * [refreshFailed]：靜默刷新失敗（列表保留，[errorMessage] 為錯誤訊息）。
 * 下一次載入更多、重新載入或刷新成功時清掉。
 * [refreshing]：刷新查詢進行中。畫面只在 [refreshFailed] 為 true 時用它顯示重試的轉圈；
 * 沒有失敗的回前景刷新不該顯示轉圈。刷新成功、失敗、被載入更多或重新載入取代時都清成 false。
 */
data class PagedUiState<T>(
    val items: List<T> = emptyList(),
    val nextCursor: String? = null,
    val phase: PagePhase = PagePhase.Loading,
    val errorMessage: String? = null,
    val refreshFailed: Boolean = false,
    val refreshing: Boolean = false,
)

/**
 * 游標分頁列表的狀態機。四個動作共用同一個 [Job]：新的動作先取消前一個，
 * 被取消的查詢不會改狀態（safeCall 會重丟 CancellationException）。
 */
class PagedList<T>(
    private val scope: CoroutineScope,
    private val fetch: suspend (String?) -> DomainResult<Page<T>>,
) {
    private val _state = MutableStateFlow(PagedUiState<T>())
    val state: StateFlow<PagedUiState<T>> = _state

    private var job: Job? = null

    /** 首次載入與 Error 的重試：整個狀態重設為 Loading（清空列表）。 */
    fun loadFirstPage() {
        job?.cancel()
        _state.value = PagedUiState(phase = PagePhase.Loading)
        job = scope.launch {
            _state.value = when (val r = fetch(null)) {
                is DomainResult.Success -> PagedUiState(
                    items = r.value.items,
                    nextCursor = r.value.nextCursor,
                    phase = PagePhase.Idle,
                )
                is DomainResult.Failure -> PagedUiState(
                    phase = PagePhase.Error,
                    errorMessage = r.error.message,
                )
            }
        }
    }

    /**
     * 回到畫面時靜默刷新第一頁。只在 [PagePhase.Idle] 執行；查詢期間只設 [PagedUiState.refreshing]，
     * items 與 phase 不變。成功就取代 items 與 nextCursor；失敗保留 items 與 phase，設
     * [PagedUiState.refreshFailed]。兩種結果都清掉 refreshing。
     */
    fun refresh() {
        if (_state.value.phase != PagePhase.Idle) return
        job?.cancel()
        _state.value = _state.value.copy(refreshing = true)
        job = scope.launch {
            val r = fetch(null)
            val latest = _state.value
            _state.value = when (r) {
                is DomainResult.Success -> latest.copy(
                    items = r.value.items,
                    nextCursor = r.value.nextCursor,
                    errorMessage = null,
                    refreshFailed = false,
                    refreshing = false,
                )
                is DomainResult.Failure -> latest.copy(
                    refreshFailed = true,
                    refreshing = false,
                    errorMessage = r.error.message,
                )
            }
        }
    }

    /** 載入下一頁；沒有下一頁或正在載入時不做事。分頁失敗後呼叫即為重試。會取消進行中的 refresh。 */
    fun loadMore() {
        val current = _state.value
        val cursor = current.nextCursor ?: return
        if (current.phase != PagePhase.Idle && current.phase != PagePhase.LoadMoreError) return
        job?.cancel()
        _state.value = current.copy(
            phase = PagePhase.LoadingMore,
            errorMessage = null,
            refreshFailed = false,
            refreshing = false,
        )
        job = scope.launch {
            val r = fetch(cursor)
            val latest = _state.value
            _state.value = when (r) {
                is DomainResult.Success -> latest.copy(
                    items = latest.items + r.value.items,
                    nextCursor = r.value.nextCursor,
                    phase = PagePhase.Idle,
                )
                is DomainResult.Failure -> latest.copy(
                    phase = PagePhase.LoadMoreError,
                    errorMessage = r.error.message,
                )
            }
        }
    }
}
