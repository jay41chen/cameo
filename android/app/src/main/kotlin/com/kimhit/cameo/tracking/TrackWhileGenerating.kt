package com.kimhit.cameo.tracking

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kimhit.cameo.domain.model.CameoOrder
import com.kimhit.cameo.domain.model.CameoStatus

/**
 * 送出成功且訂單在 `QUEUED`／`GENERATING` 時，啟動背景追蹤服務。不請求通知權限（權限在確認支付前檢查）。
 * 畫面不在前景（STARTED 以下）時不啟動，避免前景服務在背景啟動被系統拒絕；這種情況由 App 回前景時的恢復追蹤接手。
 * 服務本身用 Set 去重，和回前景恢復同時啟動同一筆不會重複輪詢。
 */
@Composable
internal fun TrackWhileGenerating(order: CameoOrder) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val cameoId = order.cameo.id
    val inProgress = order.cameo.status == CameoStatus.QUEUED || order.cameo.status == CameoStatus.GENERATING
    LaunchedEffect(cameoId, inProgress) {
        if (!inProgress) return@LaunchedEffect
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            runCatching { CameoTrackService.start(context, cameoId) }
                .onFailure { Log.w(TRACK_TAG, "start service failed id=$cameoId", it) }
        } else {
            Log.w(TRACK_TAG, "not in foreground, skip start id=$cameoId")
        }
    }
}
