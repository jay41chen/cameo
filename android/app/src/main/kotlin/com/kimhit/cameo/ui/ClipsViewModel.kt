package com.kimhit.cameo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kimhit.cameo.domain.model.Clip
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.model.Quota
import com.kimhit.cameo.domain.model.UnfinishedCameo
import com.kimhit.cameo.domain.usecase.DeletePendingCameoUseCase
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
class ClipsViewModel @Inject constructor(
    private val getClips: GetClipsUseCase,
    private val getQuota: GetQuotaUseCase,
    private val getUnfinished: GetUnfinishedCameosUseCase,
    private val deletePending: DeletePendingCameoUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow<Load<List<Clip>>>(Load.Loading)
    val state: StateFlow<Load<List<Clip>>> = _state

    private val _quota = MutableStateFlow<Load<Quota>>(Load.Loading)
    val quota: StateFlow<Load<Quota>> = _quota

    private val _unfinished = MutableStateFlow<List<UnfinishedCameo>>(emptyList())
    val unfinished: StateFlow<List<UnfinishedCameo>> = _unfinished

    private val _blockedHint = MutableStateFlow<Quota?>(null)
    val blockedHint: StateFlow<Quota?> = _blockedHint

    private var quotaJob: Job? = null
    private var unfinishedJob: Job? = null

    init {
        load()
    }

    fun load() {
        _state.value = Load.Loading
        viewModelScope.launch {
            _state.value = when (val r = getClips()) {
                is DomainResult.Success -> Load.Success(r.value)
                is DomainResult.Failure -> Load.Error(r.error.message)
            }
        }
    }

    /** 畫面回到前景（含首次進入、從確認支付返回）時呼叫。 */
    fun onResume() {
        refreshQuota()
        refreshUnfinished()
    }

    /** 重新查額度；目前是 Success 時不切回 Loading，失敗則變 Error（不沿用舊值）。 */
    fun refreshQuota() {
        quotaJob?.cancel()
        if (_quota.value !is Load.Success) _quota.value = Load.Loading
        quotaJob = viewModelScope.launch {
            _quota.value = when (val r = getQuota()) {
                is DomainResult.Success -> Load.Success(r.value)
                is DomainResult.Failure -> Load.Error(r.error.message)
            }
        }
    }

    private fun refreshUnfinished() {
        unfinishedJob?.cancel()
        unfinishedJob = viewModelScope.launch { _unfinished.value = getUnfinished() }
    }

    /**
     * 能否進入確認支付。只有已知額度為 0 才擋；額度載入中或失敗時放行，
     * 由後端決定（後端才是事實來源）。
     */
    fun tryStart(): Boolean {
        val q = (_quota.value as? Load.Success)?.value
        if (q != null && q.remaining == 0) {
            _blockedHint.value = q
            refreshQuota()
            return false
        }
        return true
    }

    fun dismissHint() {
        _blockedHint.value = null
    }

    /** 重新客串：額度允許才刪掉舊草稿並回 true；不允許時不刪。 */
    fun restart(row: UnfinishedCameo): Boolean {
        if (!tryStart()) return false
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
