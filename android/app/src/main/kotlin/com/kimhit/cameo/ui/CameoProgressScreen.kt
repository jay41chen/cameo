package com.kimhit.cameo.ui

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.kimhit.cameo.domain.usecase.CameoStep

internal const val UPLOADING_TEXT = "上傳中…"
internal const val SUBMITTING_TEXT = "送出中…"
internal const val NEED_PHOTO_TEXT = "照片已遺失或無法使用，請重新拍照"
internal const val GONE_TEXT = "這筆客串已不存在"

@Composable
fun CameoProgressScreen(
    onBack: () -> Unit,
    onRetake: (clipId: String, clientRequestId: String) -> Unit,
    onRestart: (clipId: String) -> Unit,
    onNotCreated: (clipId: String) -> Unit,
    viewModel: CameoProgressViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    when (val s = state) {
        is ProgressUiState.Working -> when (s.step) {
            null -> LoadingBox()
            CameoStep.UPLOADING -> Body {
                Text(UPLOADING_TEXT, style = MaterialTheme.typography.titleLarge)
            }
            CameoStep.SUBMITTING -> Body {
                Text(SUBMITTING_TEXT, style = MaterialTheme.typography.titleLarge)
            }
        }
        is ProgressUiState.Done -> Body {
            val cameo = s.order.cameo
            Text(cameo.displayTitle ?: "處理中", style = MaterialTheme.typography.titleLarge)
            cameo.displayMessage?.takeIf { it.isNotEmpty() }?.let { Text(it) }
            Button(onClick = onBack) { Text("回片段清單") }
        }
        is ProgressUiState.Expired -> Body {
            Text(s.title?.takeIf { it.isNotBlank() } ?: EXPIRED_TITLE, style = MaterialTheme.typography.titleLarge)
            Text(s.message?.takeIf { it.isNotBlank() } ?: EXPIRED_MESSAGE)
            Button(onClick = {
                viewModel.restartExpired()
                onRestart(s.clipId)
            }) { Text("重新客串") }
            Button(onClick = onBack) { Text("回片段清單") }
        }
        is ProgressUiState.NeedPhoto -> Body {
            Text(NEED_PHOTO_TEXT)
            Button(onClick = { onRetake(s.clipId, s.clientRequestId) }) { Text("重新拍照") }
        }
        is ProgressUiState.Failed -> Body {
            Text(s.message ?: GENERIC_ERROR)
            if (s.retryable) Button(onClick = viewModel::retry) { Text("重試") }
            Button(onClick = onBack) { Text("回片段清單") }
        }
        is ProgressUiState.NotCreated -> {
            LaunchedEffect(s.clipId) { onNotCreated(s.clipId) }
            LoadingBox()
        }
        ProgressUiState.Gone -> Body {
            Text(GONE_TEXT)
            Button(onClick = onBack) { Text("回片段清單") }
        }
    }
}
