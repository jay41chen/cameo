package com.kimhit.cameo.ui

import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.model.Quota
import com.kimhit.cameo.domain.usecase.GetQuotaUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 額度查詢與「能否開始客串」的判斷，選片段頁與首頁共用。 */
class QuotaGate(
    private val scope: CoroutineScope,
    private val getQuota: GetQuotaUseCase,
) {
    private val _quota = MutableStateFlow<Load<Quota>>(Load.Loading)
    val quota: StateFlow<Load<Quota>> = _quota

    private val _blockedHint = MutableStateFlow<Quota?>(null)
    val blockedHint: StateFlow<Quota?> = _blockedHint

    private var quotaJob: Job? = null

    /** 重新查額度；目前是 Success 時不切回 Loading，失敗則變 Error（不沿用舊值）。 */
    fun refresh() {
        quotaJob?.cancel()
        if (_quota.value !is Load.Success) _quota.value = Load.Loading
        quotaJob = scope.launch {
            _quota.value = when (val r = getQuota()) {
                is DomainResult.Success -> Load.Success(r.value)
                is DomainResult.Failure -> Load.Error(r.error.message)
            }
        }
    }

    /**
     * 能否進入確認支付。只有已知額度為 0 才擋；額度載入中或失敗時放行，
     * 由後端決定（後端才是事實來源）。
     */
    fun tryStart(): Boolean {
        val q = (_quota.value as? Load.Success)?.value
        if (q != null && q.remaining == 0) {
            _blockedHint.value = q
            refresh()
            return false
        }
        return true
    }

    fun dismissHint() {
        _blockedHint.value = null
    }
}
