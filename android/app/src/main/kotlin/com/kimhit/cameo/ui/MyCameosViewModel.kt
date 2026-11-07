package com.kimhit.cameo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kimhit.cameo.domain.model.Cameo
import com.kimhit.cameo.domain.model.Clip
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.model.Quota
import com.kimhit.cameo.domain.model.UnfinishedCameo
import com.kimhit.cameo.domain.usecase.DeletePendingCameoUseCase
import com.kimhit.cameo.domain.usecase.GetCameoPageUseCase
import com.kimhit.cameo.domain.usecase.GetClipsUseCase
import com.kimhit.cameo.domain.usecase.GetQuotaUseCase
import com.kimhit.cameo.domain.usecase.GetUnfinishedCameosUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class MyCameosViewModel @Inject constructor(
    getCameoPage: GetCameoPageUseCase,
    getQuota: GetQuotaUseCase,
    private val getClips: GetClipsUseCase,
    private val getUnfinished: GetUnfinishedCameosUseCase,
    private val deletePending: DeletePendingCameoUseCase,
) : ViewModel() {
    private val list = PagedList<Cameo>(viewModelScope, idOf = { it.id }) { getCameoPage(it) }
    val state: StateFlow<PagedUiState<Cameo>> = list.state

    private val gate = QuotaGate(viewModelScope, getQuota)
    val blockedHint: StateFlow<Quota?> = gate.blockedHint

    private val _unfinished = MutableStateFlow<List<UnfinishedCameo>>(emptyList())
    val unfinished: StateFlow<List<UnfinishedCameo>> = _unfinished

    private val _clips = MutableStateFlow<List<Clip>>(emptyList())
    val clips: StateFlow<List<Clip>> = _clips

    private var unfinishedJob: Job? = null
    private var clipsJob: Job? = null

    init {
        loadFirstPage()
    }

    fun loadFirstPage() = list.loadFirstPage()
    fun refresh() = list.refresh()
    fun loadMore() = list.loadMore()

    /** 畫面回到前景時呼叫：刷新列表、待處理區、額度，片段名稱還沒有時補查。 */
    fun onResume() {
        refresh()
        refreshUnfinished()
        gate.refresh()
        loadClipsIfEmpty()
    }

    private fun refreshUnfinished() {
        unfinishedJob?.cancel()
        unfinishedJob = viewModelScope.launch { _unfinished.value = getUnfinished() }
    }

    /** 片段名稱只用來顯示待處理列；失敗保持空清單、不顯示錯誤，下次回前景再查。 */
    private fun loadClipsIfEmpty() {
        if (_clips.value.isNotEmpty()) return
        if (clipsJob?.isActive == true) return
        clipsJob = viewModelScope.launch {
            val r = getClips()
            if (r is DomainResult.Success) _clips.value = r.value
        }
    }

    fun dismissHint() = gate.dismissHint()

    /** 重新客串：額度允許才刪掉舊草稿並回 true；不允許時不刪。 */
    fun restart(row: UnfinishedCameo): Boolean {
        if (!gate.tryStart()) return false
        viewModelScope.launch {
            deletePending(row.pending.clientRequestId)
            refreshUnfinished()
        }
        return true
    }

    fun remove(row: UnfinishedCameo) {
        viewModelScope.launch {
            deletePending(row.pending.clientRequestId)
            refreshUnfinished()
        }
    }
}
