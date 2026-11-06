package com.kimhit.cameo.tracking

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.kimhit.cameo.domain.model.CameoOrder
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.repository.CameoRepository
import com.kimhit.cameo.domain.usecase.TRACK_LIMIT_MS
import com.kimhit.cameo.domain.usecase.TrackDecision
import com.kimhit.cameo.domain.usecase.decideTracking
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val TRACK_TAG = "CameoTrack"
internal const val POLL_INTERVAL_MS = 10_000L
private const val ONGOING_CHANNEL = "cameo_tracking"
private const val DONE_CHANNEL = "cameo_done"
private const val ONGOING_ID = 1
private const val EXTRA_CAMEO_ID = "cameoId"

/**
 * 前景服務（dataSync）：每 [POLL_INTERVAL_MS] 用 `getCameo` 查所有追蹤中的訂單，
 * 到結束狀態就發完成通知。全部都用主執行緒（Dispatchers.Main），所以追蹤表不需要鎖。
 * 只存在記憶體；程序被殺就不再追蹤（不做持久化）。
 */
@AndroidEntryPoint
class CameoTrackService : Service() {
    @Inject lateinit var cameos: CameoRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** cameoId -> 開始追蹤的時間（elapsedRealtime）。 */
    private val tracked = LinkedHashMap<String, Long>()
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannels()
        // startForegroundService 之後必須在時限內呼叫；每次收到命令都呼叫，確保一定有常駐通知
        ServiceCompat.startForeground(
            this, ONGOING_ID, ongoingNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        val id = intent?.getStringExtra(EXTRA_CAMEO_ID)
        if (id != null && id !in tracked) {
            tracked[id] = SystemClock.elapsedRealtime()
            Log.i(TRACK_TAG, "track start id=$id tracked=${tracked.size}")
        }
        if (loop?.isActive != true) {
            if (tracked.isEmpty()) {
                finish()
            } else {
                loop = scope.launch { pollLoop() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TRACK_TAG, "service destroyed")
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun pollLoop() {
        while (tracked.isNotEmpty()) {
            for ((id, startedAt) in tracked.entries.toList()) {
                val result = cameos.getCameo(id)
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val decision = decideTracking(result, elapsed)
                Log.i(TRACK_TAG, "poll id=$id elapsedMs=$elapsed decision=$decision ${describe(result)}")
                when (decision) {
                    TrackDecision.CONTINUE -> Unit
                    TrackDecision.NOTIFY_AND_STOP -> {
                        notifyDone((result as DomainResult.Success).value, id)
                        tracked.remove(id)
                    }
                    TrackDecision.STOP -> {
                        if (elapsed >= TRACK_LIMIT_MS) Log.i(TRACK_TAG, "limit reached id=$id")
                        tracked.remove(id)
                    }
                }
            }
            if (tracked.isEmpty()) break
            delay(POLL_INTERVAL_MS)
        }
        finish()
    }

    private fun finish() {
        Log.i(TRACK_TAG, "all finished, stopping")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun describe(r: DomainResult<CameoOrder>) = when (r) {
        is DomainResult.Success -> "status=${r.value.cameo.status}"
        is DomainResult.Failure -> "error=${r.error.code}"
    }

    private fun ensureChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ONGOING_CHANNEL, "生成進行中", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(DONE_CHANNEL, "生成結果", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun ongoingNotification(): Notification =
        NotificationCompat.Builder(this, ONGOING_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentText("生成中，完成後會通知你")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    @SuppressLint("MissingPermission")
    private fun notifyDone(order: CameoOrder, cameoId: String) {
        val nm = NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) {
            Log.i(TRACK_TAG, "notifications disabled, skip id=$cameoId")
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val tap = PendingIntent.getActivity(
            this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, DONE_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(order.cameo.displayTitle ?: order.cameo.clip.title)
            .setContentText(order.cameo.displayMessage)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        nm.notify(doneNotificationId(cameoId), notification)
        Log.i(TRACK_TAG, "notified id=$cameoId title=${order.cameo.displayTitle}")
    }

    companion object {
        /** 啟動（或加入）追蹤；呼叫端必須在前景。 */
        fun start(context: Context, cameoId: String) {
            val intent = Intent(context, CameoTrackService::class.java).putExtra(EXTRA_CAMEO_ID, cameoId)
            context.startForegroundService(intent)
        }
    }
}

/** 完成通知 id 由 cameoId 衍生；避開常駐通知的 id。 */
internal fun doneNotificationId(cameoId: String): Int = (cameoId.hashCode() and 0x7fffffff) + 1000
