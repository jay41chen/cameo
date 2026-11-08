package io.github.jay41chen.cameo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jay41chen.cameo.domain.model.DomainResult
import io.github.jay41chen.cameo.domain.model.LedgerEntry
import io.github.jay41chen.cameo.domain.model.Points
import io.github.jay41chen.cameo.domain.usecase.GetLedgerPageUseCase
import io.github.jay41chen.cameo.domain.usecase.GetPointsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class PointsViewModel @Inject constructor(
    private val getPoints: GetPointsUseCase,
    private val getLedgerPage: GetLedgerPageUseCase,
) : ViewModel() {
    private val _points = MutableStateFlow<Load<Points>>(Load.Loading)
    val points: StateFlow<Load<Points>> = _points

    private val ledgerList = PagedList<LedgerEntry>(viewModelScope) { getLedgerPage(it) }
    val ledger: StateFlow<PagedUiState<LedgerEntry>> = ledgerList.state

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

    fun loadFirstPage() = ledgerList.loadFirstPage()

    /** 回到畫面時靜默刷新明細第一頁（扣點與退點會新增明細）；規則見 [PagedList.refresh]。點數頁忽略 refreshFailed。 */
    fun refreshLedger() = ledgerList.refresh()

    /** 載入下一頁；沒有下一頁或正在載入時不做事。分頁失敗後呼叫即為重試。 */
    fun loadMore() = ledgerList.loadMore()
}
