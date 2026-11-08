package io.github.jay41chen.cameo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.dropUnlessResumed

@Composable
fun ConfirmScreen(
    onBack: () -> Unit,
    onRetake: () -> Unit,
    onBackHome: () -> Unit,
    onCreated: (String) -> Unit,
    viewModel: ConfirmViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    when (val s = state) {
        ConfirmUiState.Loading -> LoadingBox()
        is ConfirmUiState.LoadError -> ErrorBox(s.message, viewModel::load, Modifier.fillMaxSize())
        is ConfirmUiState.Ready -> Body {
            Text(s.clip.title, style = MaterialTheme.typography.titleLarge)
            Text("確認後會占用 ${s.clip.cost} 點，送出後才扣款")
            if (s.failed && !s.submitting) Text(s.error ?: GENERIC_ERROR, color = MaterialTheme.colorScheme.error)
            // 頁面離開中的點擊不啟動扣款流程；這不是完整的取消機制
            Button(onClick = dropUnlessResumed { viewModel.confirm() }, enabled = !s.submitting) {
                Text(if (s.failed) "重試" else "確認支付")
            }
        }
        is ConfirmUiState.Created -> {
            // 建單成功：交給進度畫面接手上傳與送出，只導一次
            LaunchedEffect(s.clientRequestId) { onCreated(s.clientRequestId) }
            LoadingBox()
        }
        is ConfirmUiState.Rejected -> Body {
            Text(s.message ?: GENERIC_ERROR)
            Button(onClick = onBack) { Text("回片段清單") }
        }
        ConfirmUiState.PhotoMissing -> Body {
            Text(NEED_PHOTO_TEXT)
            Button(onClick = onRetake) { Text("重新拍照") }
        }
        is ConfirmUiState.Expired -> Body {
            Text(s.title?.takeIf { it.isNotBlank() } ?: EXPIRED_TITLE, style = MaterialTheme.typography.titleLarge)
            Text(s.message?.takeIf { it.isNotBlank() } ?: EXPIRED_MESSAGE)
            // 草稿已刪除，回首頁後從 FAB 重新開始
            Button(onClick = onBackHome) { Text(RESTART_FROM_HOME_TEXT) }
        }
    }
}

@Composable
internal fun Body(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}
