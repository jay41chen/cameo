package io.github.jay41chen.cameo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jay41chen.cameo.domain.model.Clip
import io.github.jay41chen.cameo.domain.model.DomainResult
import io.github.jay41chen.cameo.domain.model.Quota
import io.github.jay41chen.cameo.domain.usecase.GetClipsUseCase
import io.github.jay41chen.cameo.domain.usecase.GetQuotaUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ClipsViewModel @Inject constructor(
    private val getClips: GetClipsUseCase,
    getQuota: GetQuotaUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow<Load<List<Clip>>>(Load.Loading)
    val state: StateFlow<Load<List<Clip>>> = _state

    private val gate = QuotaGate(viewModelScope, getQuota)
    val quota: StateFlow<Load<Quota>> = gate.quota
    val blockedHint: StateFlow<Quota?> = gate.blockedHint

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
    }

    fun refreshQuota() = gate.refresh()

    fun tryStart(): Boolean = gate.tryStart()

    fun dismissHint() = gate.dismissHint()
}
