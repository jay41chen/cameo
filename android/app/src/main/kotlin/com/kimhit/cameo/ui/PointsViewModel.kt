package com.kimhit.cameo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.model.LedgerEntry
import com.kimhit.cameo.domain.model.Points
import com.kimhit.cameo.domain.usecase.GetLedgerPageUseCase
import com.kimhit.cameo.domain.usecase.GetPointsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 明細的載入階段。[Idle] 表示已載入、可能還有下一頁。 */
enum class LedgerPhase { Loading, Idle, Error, LoadingMore, LoadMoreError }

data class LedgerUiState(
    val items: List<LedgerEntry> = emptyList(),
    val nextCursor: String? = null,
    val phase: LedgerPhase = LedgerPhase.Loading,
    val errorMessage: String? = null,
)

@HiltViewModel
class PointsViewModel @Inject constructor(
    private val getPoints: GetPointsUseCase,
    private val getLedgerPage: GetLedgerPageUseCase,
) : ViewModel() {
    private val _points = MutableStateFlow<Load<Points>>(Load.Loading)
    val points: StateFlow<Load<Points>> = _points

    private val _ledger = MutableStateFlow(LedgerUiState())
    val ledger: StateFlow<LedgerUiState> = _ledger

    private var pointsJob: Job? = null

    init {
        refresh()
    }

    /** 重新載入點數與明細第一頁（也是失敗後的重試）。 */
    fun refresh() {
        loadPoints()
        loadFirstPage()
    }

    fun loadPoints() = startPointsQuery(showLoading = true)

    /**
     * 回到畫面時只重查點數（建單會改變占用點數，但不寫明細）。
     * 目前是 Success 時不切 Loading，失敗才變 Error。
     */
    fun refreshPoints() = startPointsQuery(showLoading = _points.value !is Load.Success)

    /** loadPoints 與 refreshPoints 共用同一個 Job：新的查詢取消前一次，舊回應不能覆蓋新回應。 */
    private fun startPointsQuery(showLoading: Boolean) {
        pointsJob?.cancel()
        if (showLoading) _points.value = Load.Loading
        pointsJob = viewModelScope.launch {
            _points.value = when (val r = getPoints()) {
                is DomainResult.Success -> Load.Success(r.value)
                is DomainResult.Failure -> Load.Error(r.error.message)
            }
        }
    }

    fun loadFirstPage() {
        _ledger.value = LedgerUiState(phase = LedgerPhase.Loading)
        viewModelScope.launch {
            _ledger.value = when (val r = getLedgerPage(null)) {
                is DomainResult.Success -> LedgerUiState(
                    items = r.value.items,
                    nextCursor = r.value.nextCursor,
                    phase = LedgerPhase.Idle,
                )
                is DomainResult.Failure -> LedgerUiState(
                    phase = LedgerPhase.Error,
                    errorMessage = r.error.message,
                )
            }
        }
    }

    /** 載入下一頁；沒有下一頁或正在載入時不做事。分頁失敗後呼叫即為重試。 */
    fun loadMore() {
        val current = _ledger.value
        val cursor = current.nextCursor ?: return
        if (current.phase != LedgerPhase.Idle && current.phase != LedgerPhase.LoadMoreError) return
        _ledger.value = current.copy(phase = LedgerPhase.LoadingMore, errorMessage = null)
        viewModelScope.launch {
            val latest = _ledger.value
            _ledger.value = when (val r = getLedgerPage(cursor)) {
                is DomainResult.Success -> latest.copy(
                    items = latest.items + r.value.items,
                    nextCursor = r.value.nextCursor,
                    phase = LedgerPhase.Idle,
                )
                is DomainResult.Failure -> latest.copy(
                    phase = LedgerPhase.LoadMoreError,
                    errorMessage = r.error.message,
                )
            }
        }
    }
}
