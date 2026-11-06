package com.kimhit.cameo.domain.usecase

import com.kimhit.cameo.domain.model.CameoOrder
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.repository.CameoRepository
import com.kimhit.cameo.domain.repository.PendingCameoRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * App 回到前景時，對所有有 `cameoId` 的草稿查一次訂單，回傳要啟動前景服務追蹤的 cameoId：
 * - `SUCCEEDED`／`FAILED`：認領草稿；認領成功就**立刻**呼叫 [onFinished]（認領失敗不呼叫）。
 *   「認領＋[onFinished]」包在同一個 `withContext(NonCancellable)`，取消不會讓已認領的訂單漏通知；
 *   只有認領之後、通知之前程序被殺才會少發。
 *   [notificationsEnabled] 為 false 時不認領、不呼叫，草稿保留到通知可用時再補發。
 * - `QUEUED`／`GENERATING`：放進回傳清單。
 * - 其他狀態與查詢失敗：不動，下次再查。
 */
class ResumeTrackingUseCase(
    private val pending: PendingCameoRepository,
    private val cameos: CameoRepository,
) {
    suspend operator fun invoke(
        notificationsEnabled: Boolean,
        onFinished: (CameoOrder) -> Unit,
    ): List<String> {
        val track = mutableListOf<String>()
        for (row in pending.list()) {
            val cameoId = row.cameoId ?: continue
            val result = cameos.getCameo(cameoId)
            when (decideTracking(result, elapsedMs = 0)) {
                TrackDecision.NOTIFY_AND_STOP ->
                    if (notificationsEnabled) {
                        val order = (result as DomainResult.Success).value
                        withContext(NonCancellable) {
                            if (pending.claimFinishedCameo(cameoId)) onFinished(order)
                        }
                    }
                TrackDecision.CONTINUE ->
                    if (result is DomainResult.Success) track += cameoId
                TrackDecision.STOP -> Unit
            }
        }
        return track
    }
}
