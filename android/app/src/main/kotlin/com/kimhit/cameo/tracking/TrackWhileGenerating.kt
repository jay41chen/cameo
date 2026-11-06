package com.kimhit.cameo.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kimhit.cameo.domain.model.CameoOrder
import com.kimhit.cameo.domain.model.CameoStatus

private const val PREFS = "cameo_tracking"
private const val ASKED_KEY = "notification_permission_asked"

/**
 * 送出成功且訂單在 `QUEUED`／`GENERATING` 時，啟動背景追蹤服務。
 * 第一次（API 33+）先請求通知權限；不論同意與否都啟動服務；請求過一次就不再詢問。
 * 畫面不在前景（STARTED 以下）時不啟動，避免前景服務在背景啟動被系統拒絕。
 */
@Composable
internal fun TrackWhileGenerating(order: CameoOrder) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val cameoId = order.cameo.id
    val inProgress = order.cameo.status == CameoStatus.QUEUED || order.cameo.status == CameoStatus.GENERATING
    val start = {
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            runCatching { CameoTrackService.start(context, cameoId) }
                .onFailure { Log.w(TRACK_TAG, "start service failed id=$cameoId", it) }
        } else {
            Log.w(TRACK_TAG, "not in foreground, skip start id=$cameoId")
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Log.i(TRACK_TAG, "notification permission granted=$granted")
        start()
    }
    LaunchedEffect(cameoId, inProgress) {
        if (!inProgress) return@LaunchedEffect
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val needAsk = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean(ASKED_KEY, false)
        if (needAsk) {
            prefs.edit().putBoolean(ASKED_KEY, true).apply()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            start()
        }
    }
}
