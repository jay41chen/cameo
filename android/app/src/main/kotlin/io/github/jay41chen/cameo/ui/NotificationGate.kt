package io.github.jay41chen.cameo.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import io.github.jay41chen.cameo.tracking.isDoneChannelBlocked

/** 開始客串入口用的通知檢查結果。 */
enum class NotificationGate {
    /** 通知可用：照常繼續。 */
    PROCEED,

    /** 通知不可用：先向系統請求 `POST_NOTIFICATIONS`（系統不一定會顯示對話框）。 */
    REQUEST_PERMISSION,

    /** 請求沒用或系統確定不能再問：顯示 App 自己的提示，前往設定。 */
    NEED_SETTINGS,
}

internal const val POST_NOTIFICATIONS_MIN_SDK = 33

/**
 * 純函式。[afterDenial]：按下時為 false，一律先請求；剛收到拒絕時為 true，
 * 只有 [shouldShowRationale] 為 true 才算系統還能問。
 * [enabled] 是 App 層級通知開關；[channelBlocked] 是「生成結果」頻道被關。
 */
fun decideNotificationGate(
    enabled: Boolean,
    sdkInt: Int,
    afterDenial: Boolean,
    shouldShowRationale: Boolean,
    channelBlocked: Boolean = false,
): NotificationGate = when {
    // 權限有、但「生成結果」頻道被關：再要權限沒用，直接請使用者去設定
    enabled && channelBlocked -> NotificationGate.NEED_SETTINGS
    enabled -> NotificationGate.PROCEED
    sdkInt >= POST_NOTIFICATIONS_MIN_SDK && (!afterDenial || shouldShowRationale) -> NotificationGate.REQUEST_PERMISSION
    else -> NotificationGate.NEED_SETTINGS
}

internal fun currentNotificationGate(context: Context, afterDenial: Boolean): NotificationGate {
    val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val channelBlocked = isDoneChannelBlocked(context)
    val rationale = Build.VERSION.SDK_INT >= POST_NOTIFICATIONS_MIN_SDK &&
        context.findActivity()?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.POST_NOTIFICATIONS)
        } == true
    return decideNotificationGate(enabled, Build.VERSION.SDK_INT, afterDenial, rationale, channelBlocked)
}

internal fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

internal fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
}

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

internal const val NEED_NOTIFICATION_TITLE = "需要開啟通知才能客串"
internal const val NEED_NOTIFICATION_MESSAGE = "生成完成時要用通知告訴你。"
