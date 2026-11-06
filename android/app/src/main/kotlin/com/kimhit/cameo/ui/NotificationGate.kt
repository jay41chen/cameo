package com.kimhit.cameo.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import com.kimhit.cameo.tracking.isDoneChannelBlocked

/** 確認支付前的通知檢查結果。 */
enum class NotificationGate {
    /** 通知可用：照常建單。 */
    PROCEED,

    /** 通知不可用，且系統還能顯示權限對話框：請求 `POST_NOTIFICATIONS`。 */
    REQUEST_PERMISSION,

    /** 通知不可用，且系統不會再顯示對話框（或 API 33 以下）：顯示 App 自己的提示，前往設定。 */
    NEED_SETTINGS,
}

internal const val POST_NOTIFICATIONS_MIN_SDK = 33

/**
 * 純函式。[askedBefore] 是「權限對話框已有過結果」的旗標；
 * 系統還能顯示對話框 = 沒請求過，或 [shouldShowRationale] 為 true。
 * [enabled] 是 App 層級通知開關；[channelBlocked] 是「生成結果」頻道被關。
 */
fun decideNotificationGate(
    enabled: Boolean,
    sdkInt: Int,
    askedBefore: Boolean,
    shouldShowRationale: Boolean,
    channelBlocked: Boolean = false,
): NotificationGate = when {
    // 權限有、但「生成結果」頻道被關：再要權限沒用，直接請使用者去設定
    enabled && channelBlocked -> NotificationGate.NEED_SETTINGS
    enabled -> NotificationGate.PROCEED
    sdkInt >= POST_NOTIFICATIONS_MIN_SDK && (!askedBefore || shouldShowRationale) -> NotificationGate.REQUEST_PERMISSION
    else -> NotificationGate.NEED_SETTINGS
}

/** 「已請求過通知權限」旗標；只在收到權限結果之後寫入。 */
interface PermissionAskedStore {
    fun asked(): Boolean
    fun markAsked()
}

/** 權限對話框回來的結果：先寫旗標（此時才寫，不在 launch 前寫），回傳是否可以繼續建單。 */
fun onPermissionResult(granted: Boolean, store: PermissionAskedStore): Boolean {
    store.markAsked()
    return granted
}

private const val PREFS = "cameo_tracking"
private const val ASKED_KEY = "notification_permission_asked"

internal class SharedPrefsAskedStore(context: Context) : PermissionAskedStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    override fun asked(): Boolean = prefs.getBoolean(ASKED_KEY, false)
    override fun markAsked() {
        prefs.edit().putBoolean(ASKED_KEY, true).apply()
    }
}

internal fun currentNotificationGate(context: Context, store: PermissionAskedStore): NotificationGate {
    val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val channelBlocked = isDoneChannelBlocked(context)
    val rationale = Build.VERSION.SDK_INT >= POST_NOTIFICATIONS_MIN_SDK &&
        context.findActivity()?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.POST_NOTIFICATIONS)
        } == true
    return decideNotificationGate(enabled, Build.VERSION.SDK_INT, store.asked(), rationale, channelBlocked)
}

internal fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

internal const val NEED_NOTIFICATION_TITLE = "需要開啟通知才能客串"
internal const val NEED_NOTIFICATION_MESSAGE = "生成完成時要用通知告訴你。"
