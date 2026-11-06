package com.kimhit.cameo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

@Composable
fun ConfirmScreen(onBack: () -> Unit, viewModel: ConfirmViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    when (val s = state) {
        ConfirmUiState.Loading -> LoadingBox()
        is ConfirmUiState.LoadError -> ErrorBox(s.message, viewModel::load, Modifier.fillMaxSize())
        is ConfirmUiState.Ready -> Body {
            Text(s.clip.title, style = MaterialTheme.typography.titleLarge)
            Text("確認後會占用 ${s.clip.cost} 點，送出後才扣款")
            if (s.failed && !s.submitting) Text(s.error ?: GENERIC_ERROR, color = MaterialTheme.colorScheme.error)
            Button(onClick = viewModel::confirm, enabled = !s.submitting) {
                Text(if (s.failed) "重試" else "確認支付")
            }
        }
        is ConfirmUiState.Created -> Body {
            val cameo = s.order.cameo
            Text(cameo.displayTitle ?: "處理中", style = MaterialTheme.typography.titleLarge)
            cameo.displayMessage?.takeIf { it.isNotEmpty() }?.let { Text(it) }
            s.order.upload?.let { Text("上傳期限：${formatTime(it.expiresAt)}") }
            Button(onClick = onBack) { Text("回片段清單") }
        }
        is ConfirmUiState.Rejected -> Body {
            Text(s.message ?: GENERIC_ERROR)
            Button(onClick = onBack) { Text("回片段清單") }
        }
        is ConfirmUiState.Expired -> Body {
            Text(s.title?.takeIf { it.isNotBlank() } ?: EXPIRED_TITLE, style = MaterialTheme.typography.titleLarge)
            Text(s.message?.takeIf { it.isNotBlank() } ?: EXPIRED_MESSAGE)
            Button(onClick = viewModel::restart) { Text("重新客串") }
        }
    }
}

@Composable
private fun Body(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}
