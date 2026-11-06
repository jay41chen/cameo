package com.kimhit.cameo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kimhit.cameo.domain.model.Cameo
import com.kimhit.cameo.domain.usecase.GetCameoPageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class MyCameosViewModel @Inject constructor(
    getCameoPage: GetCameoPageUseCase,
) : ViewModel() {
    private val list = PagedList<Cameo>(viewModelScope) { getCameoPage(it) }
    val state: StateFlow<PagedUiState<Cameo>> = list.state

    init {
        loadFirstPage()
    }

    fun loadFirstPage() = list.loadFirstPage()
    fun refresh() = list.refresh()
    fun loadMore() = list.loadMore()
}
