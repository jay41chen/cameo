package com.kimhit.cameo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kimhit.cameo.domain.model.Clip
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.usecase.GetClipsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ClipsViewModel @Inject constructor(
    private val getClips: GetClipsUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow<Load<List<Clip>>>(Load.Loading)
    val state: StateFlow<Load<List<Clip>>> = _state

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
}
