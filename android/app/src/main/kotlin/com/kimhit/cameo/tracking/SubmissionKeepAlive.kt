package com.kimhit.cameo.tracking

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 提交保持的最長時間；超過就由服務自動釋放，避免漏釋放讓前景服務一直在。 */
internal const val HOLD_LIMIT_MS = 5 * 60 * 1000L

/** 追蹤表與保持表都空才停服務。 */
internal fun shouldStopService(trackedEmpty: Boolean, holdsEmpty: Boolean): Boolean = trackedEmpty && holdsEmpty

/** 超過 [limitMs] 的保持 key；`now - t >= limitMs` 算過期。 */
internal fun expiredHolds(holds: Map<String, Long>, now: Long, limitMs: Long = HOLD_LIMIT_MS): Set<String> =
    holds.filterValues { now - it >= limitMs }.keys

/**
 * 提交期間讓前景服務保持存活（付款後上傳與送出需要在背景繼續）。
 * 上傳與送出由程序層級的 `SubmissionRunner`（`com.kimhit.cameo.submission`）執行，並呼叫 acquire／release／track；服務不接手。
 */
interface SubmissionKeepAlive {
    /** App 可能已在背景，啟動前景服務可能被拒；失敗時不丟例外，記 log，並移除該 key。 */
    fun acquire(key: String)

    /** 冪等；只改保持表，不發 Intent（背景時啟動服務可能被拒 [待確認]）。 */
    fun release(key: String)

    /** 可在背景呼叫，不要在呼叫前加前景判斷；背景只有已有前景服務時才可能成功 [待確認]；失敗時不丟例外。 */
    fun track(cameoId: String)
}

@Singleton
class ServiceSubmissionKeepAlive @Inject constructor(
    @ApplicationContext private val context: Context,
) : SubmissionKeepAlive {
    private val _holds = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** key -> 取得時間（elapsedRealtime），只在行程內。 */
    val holds: StateFlow<Map<String, Long>> = _holds.asStateFlow()

    override fun acquire(key: String) {
        _holds.update { it + (key to SystemClock.elapsedRealtime()) }
        runCatching { CameoTrackService.startHold(context) }
            .onFailure {
                _holds.update { m -> m - key }
                Log.w(TRACK_TAG, "start hold failed key=$key", it)
            }
    }

    override fun release(key: String) {
        _holds.update { it - key }
    }

    override fun track(cameoId: String) {
        runCatching { CameoTrackService.start(context, cameoId) }
            .onFailure { Log.w(TRACK_TAG, "start service failed id=$cameoId", it) }
    }

    internal fun clearAll() {
        _holds.value = emptyMap()
    }
}
