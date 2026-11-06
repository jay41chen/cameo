package com.kimhit.cameo.tracking

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.kimhit.cameo.domain.model.CameoOrder
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.repository.CameoRepository
import com.kimhit.cameo.domain.usecase.ClaimFinishedCameoUseCase
import com.kimhit.cameo.domain.usecase.TRACK_LIMIT_MS
import com.kimhit.cameo.domain.usecase.TrackDecision
import com.kimhit.cameo.domain.usecase.decideTracking
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val TRACK_TAG = "CameoTrack"
internal const val POLL_INTERVAL_MS = 10_000L
private const val ONGOING_ID = 1
private const val EXTRA_CAMEO_ID = "cameoId"

/**
 * 前景服務（dataSync）：每 [POLL_INTERVAL_MS] 用 `getCameo` 查所有追蹤中的訂單，
 * 到結束狀態就先認領（刪草稿）、認領成功才發完成通知。全部都用主執行緒（Dispatchers.Main），所以追蹤表不需要鎖。
 * 只存在記憶體；程序被殺就不再追蹤（不做持久化）。
 */
@AndroidEntryPoint
class CameoTrackService : Service() {
    @Inject lateinit var cameos: CameoRepository
    @Inject lateinit var claimFinished: ClaimFinishedCameoUseCase

    private val notifier by lazy { CompletionNotifier(this) }
    private var lastStartId = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** cameoId -> 開始追蹤的時間（elapsedRealtime）。 */
    private val tracked = LinkedHashMap<String, Long>()
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        notifier.ensureChannels()
        // startForegroundService 之後必須在時限內呼叫；每次收到命令都呼叫，確保一定有常駐通知
        try {
            ServiceCompat.startForeground(
                this, ONGOING_ID, ongoingNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException（API 31+）的父類別：App 不在可啟動前景服務的狀態。
            // 不崩潰、不追蹤；回前景時由 ResumeTrackingUseCase 接手
            Log.w(TRACK_TAG, "startForeground not allowed, stopping", e)
            stopSelf(startId)
            return START_NOT_STICKY
        }
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

    /** dataSync 前景服務到達系統時限（API 35+，約 6 小時）：必須停止，否則系統丟例外。未結束訂單由回前景恢復接手。 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TRACK_TAG, "foreground service timeout startId=$startId type=$fgsType")
        loop?.cancel()
        tracked.clear()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
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
                        finishOrder((result as DomainResult.Success).value)
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

    /** 先認領（刪草稿），成功才發通知；通知不可用（App 通知或結果頻道被關）時不認領，草稿留著等 App 開啟時補發。 */
    private suspend fun finishOrder(order: CameoOrder) {
        val id = order.cameo.id
        if (!notifier.isEnabled()) {
            Log.i(TRACK_TAG, "notifications disabled, keep draft id=$id")
            return
        }
        // 認領＋通知不可被取消打斷（服務被停掉時 scope 會取消）
        withContext(NonCancellable) {
            if (claimFinished(id)) notifier.notifyDone(order) else Log.i(TRACK_TAG, "already claimed id=$id")
        }
    }

    private fun finish() {
        Log.i(TRACK_TAG, "all finished, stopping")
        // 只停到最後一個 startId；還有新的命令在排隊時回 false，不能停，輪詢交給那個命令重啟
        if (stopSelfResult(lastStartId)) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } else {
            Log.i(TRACK_TAG, "newer start command pending, keep running")
        }
    }

    private fun describe(r: DomainResult<CameoOrder>) = when (r) {
        is DomainResult.Success -> "status=${r.value.cameo.status}"
        is DomainResult.Failure -> "error=${r.error.code}"
    }

    private fun ongoingNotification(): Notification =
        NotificationCompat.Builder(this, ONGOING_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentText("生成中，完成後會通知你")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    companion object {
        /** 啟動（或加入）追蹤；呼叫端必須在前景。 */
        fun start(context: Context, cameoId: String) {
            val intent = Intent(context, CameoTrackService::class.java).putExtra(EXTRA_CAMEO_ID, cameoId)
            context.startForegroundService(intent)
        }
    }
}
