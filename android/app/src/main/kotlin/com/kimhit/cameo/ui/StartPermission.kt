package com.kimhit.cameo.ui

import android.Manifest
import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.core.app.ActivityCompat

/** 首頁開始客串前的權限檢查結果：先通知，再相機。 */
enum class StartPermission { PROCEED, REQUEST_NOTIFICATION, NOTIFICATION_SETTINGS, REQUEST_CAMERA, CAMERA_SETTINGS }

/**
 * 純函式：先看通知，再看相機。
 * 按下時 [afterDenial] = false：權限沒開就一律先向系統請求。
 * 剛收到系統拒絕時 [afterDenial] = true：只有 [cameraRationale] 為 true 才算系統還能問，否則去設定。
 */
fun decideStartPermission(
    notification: NotificationGate,
    cameraGranted: Boolean,
    afterDenial: Boolean,
    cameraRationale: Boolean,
): StartPermission = when (notification) {
    NotificationGate.REQUEST_PERMISSION -> StartPermission.REQUEST_NOTIFICATION
    NotificationGate.NEED_SETTINGS -> StartPermission.NOTIFICATION_SETTINGS
    NotificationGate.PROCEED -> when {
        cameraGranted -> StartPermission.PROCEED
        !afterDenial || cameraRationale -> StartPermission.REQUEST_CAMERA
        else -> StartPermission.CAMERA_SETTINGS
    }
}

internal fun currentStartPermission(context: Context, afterDenial: Boolean = false): StartPermission {
    val rationale = context.findActivity()?.let {
        ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
    } == true
    return decideStartPermission(
        notification = currentNotificationGate(context, askedBefore = afterDenial),
        cameraGranted = hasCameraPermission(context),
        afterDenial = afterDenial,
        cameraRationale = rationale,
    )
}

internal const val NEED_CAMERA_TITLE = "需要相機才能客串"
internal const val NEED_CAMERA_MESSAGE = "要拍一張自拍才能生成短片。"

@Composable
internal fun StartPermissionDialog(
    prompt: StartPrompt,
    onDismiss: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    when (prompt) {
        StartPrompt.NOTIFICATION_DENIED -> DeniedDialog(NEED_NOTIFICATION_TITLE, NEED_NOTIFICATION_MESSAGE, onDismiss)
        StartPrompt.CAMERA_DENIED -> DeniedDialog(NEED_CAMERA_TITLE, NEED_CAMERA_MESSAGE, onDismiss)
        StartPrompt.NOTIFICATION_SETTINGS ->
            SettingsDialog(NEED_NOTIFICATION_TITLE, NEED_NOTIFICATION_MESSAGE, onDismiss, onOpenNotificationSettings)
        StartPrompt.CAMERA_SETTINGS ->
            SettingsDialog(NEED_CAMERA_TITLE, NEED_CAMERA_MESSAGE, onDismiss, onOpenAppSettings)
        else -> Unit
    }
}

@Composable
private fun DeniedDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

@Composable
private fun SettingsDialog(title: String, message: String, onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                // 從設定回來不自動繼續，由使用者再按一次
                onOpenSettings()
            }) { Text("前往設定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
