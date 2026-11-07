package com.kimhit.cameo.ui

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.dropUnlessResumed
import com.kimhit.cameo.domain.usecase.CameoStep
import com.kimhit.cameo.tracking.startTrackingIfNeeded

internal const val UPLOADING_TEXT = "上傳中…"
internal const val SUBMITTING_TEXT = "送出中…"
internal const val NEED_PHOTO_TEXT = "照片無法使用，請重新拍照"
internal const val BACK_HOME_TEXT = "回我的客串"
internal const val RESTART_FROM_HOME_TEXT = "回首頁重新客串"

@Composable
fun CameoProgressScreen(
    onBack: () -> Unit,
    onLeave: () -> Unit,
    onRetake: (clipId: String, clientRequestId: String) -> Unit,
    onNotCreated: (clipId: String) -> Unit,
    viewModel: CameoProgressViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
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
        is ProgressUiState.Done -> {
            // 先啟動追蹤（同步呼叫）再回首頁
            LaunchedEffect(s.order.cameo.id) {
                startTrackingIfNeeded(context, owner.lifecycle, s.order)
                onLeave()
            }
            LoadingBox()
        }
        is ProgressUiState.Expired -> Body {
            Text(s.title?.takeIf { it.isNotBlank() } ?: EXPIRED_TITLE, style = MaterialTheme.typography.titleLarge)
            Text(s.message?.takeIf { it.isNotBlank() } ?: EXPIRED_MESSAGE)
            ProgressActions(s, onRetry = { viewModel.retry() }, onRetake = {}, onBack = onBack)
        }
        is ProgressUiState.NeedPhoto -> Body {
            Text(NEED_PHOTO_TEXT)
            ProgressActions(
                s,
                onRetry = { viewModel.retry() },
                onRetake = { onRetake(s.clipId, s.clientRequestId) },
                onBack = onBack,
            )
        }
        is ProgressUiState.Failed -> Body {
            Text(s.message ?: GENERIC_ERROR)
            ProgressActions(s, onRetry = { viewModel.retry() }, onRetake = {}, onBack = onBack)
        }
        is ProgressUiState.NotCreated -> {
            LaunchedEffect(s.clipId) { onNotCreated(s.clipId) }
            LoadingBox()
        }
        ProgressUiState.Gone -> {
            LaunchedEffect(Unit) { onLeave() }
            LoadingBox()
        }
    }
}

@Composable
private fun ProgressActions(
    state: ProgressUiState,
    onRetry: () -> Unit,
    onRetake: () -> Unit,
    onBack: () -> Unit,
) {
    progressActions(state).forEach { action ->
        when (action) {
            // 頁面離開中的點擊不啟動扣款流程；這不是完整的取消機制
            ProgressAction.Retry -> Button(onClick = dropUnlessResumed { onRetry() }) { Text("重試") }
            ProgressAction.Retake -> Button(onClick = onRetake) { Text("重新拍照") }
            ProgressAction.BackHome -> Button(onClick = onBack) { Text(BACK_HOME_TEXT) }
            ProgressAction.RestartFromHome -> Button(onClick = onBack) { Text(RESTART_FROM_HOME_TEXT) }
        }
    }
}
