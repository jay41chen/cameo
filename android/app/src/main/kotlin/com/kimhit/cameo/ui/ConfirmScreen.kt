package com.kimhit.cameo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.dropUnlessResumed

@Composable
fun ConfirmScreen(
    onBack: () -> Unit,
    onRetake: () -> Unit,
    onCreated: (String) -> Unit,
    viewModel: ConfirmViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val prompt by viewModel.prompt.collectAsState()
    val context = LocalContext.current
    val askedStore = remember(context) { SharedPrefsAskedStore(context) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // 收到結果後才寫旗標
        val ok = onPermissionResult(granted, askedStore)
        // 同意後重新檢查（頻道可能仍被關）
        viewModel.onPermissionResult(ok, currentNotificationGate(context, askedStore))
    }
    LaunchedEffect(prompt) {
        if (prompt == ConfirmPrompt.REQUEST_PERMISSION) {
            viewModel.onPermissionRequestLaunched()
            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    if (prompt == ConfirmPrompt.NEED_SETTINGS) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPrompt,
            title = { Text(NEED_NOTIFICATION_TITLE) },
            text = { Text(NEED_NOTIFICATION_MESSAGE) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissPrompt()
                    // 從設定回來不自動建單，由使用者再按一次確認
                    openNotificationSettings(context)
                }) { Text("前往設定") }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissPrompt) { Text("取消") } },
        )
    }
    when (val s = state) {
        ConfirmUiState.Loading -> LoadingBox()
        is ConfirmUiState.LoadError -> ErrorBox(s.message, viewModel::load, Modifier.fillMaxSize())
        is ConfirmUiState.Ready -> Body {
            Text(s.clip.title, style = MaterialTheme.typography.titleLarge)
            Text("確認後會占用 ${s.clip.cost} 點，送出後才扣款")
            if (s.failed && !s.submitting) Text(s.error ?: GENERIC_ERROR, color = MaterialTheme.colorScheme.error)
            // 頁面離開中的點擊不啟動扣款流程；這不是完整的取消機制
            Button(onClick = dropUnlessResumed {
                viewModel.onConfirmClick(currentNotificationGate(context, askedStore))
            }, enabled = !s.submitting) {
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
            // 逾期的草稿與照片已刪除，重新客串要重新拍照
            Button(onClick = onRetake) { Text("重新客串") }
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
