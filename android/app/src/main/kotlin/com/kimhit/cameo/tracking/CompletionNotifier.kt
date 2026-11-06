package com.kimhit.cameo.tracking

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kimhit.cameo.domain.model.CameoOrder

internal const val ONGOING_CHANNEL = "cameo_tracking"
private const val DONE_CHANNEL = "cameo_done"

/** 完成通知。前景服務與 App 回前景的補發共用；呼叫端要先認領草稿，認領成功才呼叫 [notifyDone]。 */
internal class CompletionNotifier(private val context: Context) {
    fun isEnabled(): Boolean = areCompletionNotificationsUsable(context)

    fun ensureChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ONGOING_CHANNEL, "生成進行中", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(DONE_CHANNEL, "生成結果", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    @SuppressLint("MissingPermission")
    fun notifyDone(order: CameoOrder) {
        val nm = NotificationManagerCompat.from(context)
        if (!isEnabled()) {
            Log.i(TRACK_TAG, "notifications disabled, skip id=${order.cameo.id}")
            return
        }
        ensureChannels()
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val tap = PendingIntent.getActivity(
            context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, DONE_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(order.cameo.displayTitle ?: order.cameo.clip.title)
            .setContentText(order.cameo.displayMessage)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        nm.notify(doneNotificationId(order.cameo.id), notification)
        Log.i(TRACK_TAG, "notified id=${order.cameo.id} title=${order.cameo.displayTitle}")
    }
}

/** 純判斷：「生成結果」頻道被使用者關閉（`IMPORTANCE_NONE`）。頻道還沒建立（[importance] 為 null）視為可用。 */
internal fun isChannelBlocked(importance: Int?): Boolean = importance == NotificationManager.IMPORTANCE_NONE

/** 「生成結果」頻道目前是否被關閉；API 26 以下沒有頻道，一律 false。 */
internal fun isDoneChannelBlocked(context: Context): Boolean =
    isChannelBlocked(NotificationManagerCompat.from(context).getNotificationChannelCompat(DONE_CHANNEL)?.importance)

/** 完成通知是否真的送得出去：App 通知開著，且「生成結果」頻道沒被關。確認頁 gate、回前景補發、前景服務共用。 */
internal fun areCompletionNotificationsUsable(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled() && !isDoneChannelBlocked(context)

/** 完成通知 id 由 cameoId 衍生；避開常駐通知的 id。 */
internal fun doneNotificationId(cameoId: String): Int = (cameoId.hashCode() and 0x7fffffff) + 1000
