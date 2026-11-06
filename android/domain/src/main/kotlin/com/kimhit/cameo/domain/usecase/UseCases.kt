package com.kimhit.cameo.domain.usecase

import com.kimhit.cameo.domain.model.Clip
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.model.LedgerPage
import com.kimhit.cameo.domain.model.CameoOrder
import com.kimhit.cameo.domain.model.CameoStatus
import com.kimhit.cameo.domain.model.Points
import com.kimhit.cameo.domain.model.Quota
import com.kimhit.cameo.domain.model.UnfinishedCameo
import com.kimhit.cameo.domain.repository.CameoRepository
import com.kimhit.cameo.domain.repository.ClipRepository
import com.kimhit.cameo.domain.repository.PendingCameoRepository
import com.kimhit.cameo.domain.repository.PointsRepository
import com.kimhit.cameo.domain.repository.QuotaRepository

class GetClipsUseCase(private val repository: ClipRepository) {
    suspend operator fun invoke(): DomainResult<List<Clip>> = repository.getClips()
}

class GetPointsUseCase(private val repository: PointsRepository) {
    suspend operator fun invoke(): DomainResult<Points> = repository.getPoints()
}

/** [cursor] 為 null 取第一頁；之後用上一頁的 [LedgerPage.nextCursor]。 */
class GetLedgerPageUseCase(private val repository: PointsRepository) {
    suspend operator fun invoke(cursor: String? = null): DomainResult<LedgerPage> =
        repository.getLedger(cursor)
}

class GetQuotaUseCase(private val repository: QuotaRepository) {
    suspend operator fun invoke(): DomainResult<Quota> = repository.getQuota()
}

/**
 * 確認支付並建單。順序固定：先寫本機草稿，才送 POST。
 * - 成功且 EXPIRED：後端不會再接受這張單，刪草稿。
 * - 成功其他狀態：草稿標記為已建立（等待上傳）。
 * - 失敗且 code 屬於 [NO_ORDER_CODES]：確定沒有建單，刪草稿，下次用新 key。
 * - 其他失敗：不確定有沒有建單，記錄錯誤並保留草稿，下次重用同一個 key。
 * - 取消（使用者離開畫面）：結果未知，直接往外丟，草稿不動。
 */
class ConfirmCameoUseCase(
    private val cameos: CameoRepository,
    private val pending: PendingCameoRepository,
) {
    suspend operator fun invoke(clipId: String): DomainResult<CameoOrder> {
        val draft = pending.getOrCreateDraft(clipId)
        val result = cameos.createCameo(draft.clientRequestId, clipId)
        when (result) {
            is DomainResult.Success ->
                if (result.value.cameo.status == CameoStatus.EXPIRED) {
                    pending.delete(draft.clientRequestId)
                } else {
                    pending.markCreated(draft.clientRequestId, result.value.cameo.id)
                }
            is DomainResult.Failure ->
                if (result.error.code in NO_ORDER_CODES) {
                    pending.delete(draft.clientRequestId)
                } else {
                    pending.recordError(draft.clientRequestId, result.error.message)
                }
        }
        return result
    }
}

/** 後端都在查過冪等鍵之後才回這些錯誤，所以確定此 key 沒有訂單，草稿可以刪。 */
private val NO_ORDER_CODES = setOf("INSUFFICIENT_POINTS", "DAILY_LIMIT_REACHED", "CLIP_NOT_FOUND")

/** 列出本機未完成的客串，並向後端補上目前狀態。 */
class GetUnfinishedCameosUseCase(
    private val pending: PendingCameoRepository,
    private val cameos: CameoRepository,
) {
    suspend operator fun invoke(): List<UnfinishedCameo> {
        val rows = pending.list()
        val out = mutableListOf<UnfinishedCameo>()
        for (row in rows) {
            val cameoId = row.cameoId
            if (cameoId == null) {
                out += UnfinishedCameo(row, null)
                continue
            }
            when (val r = cameos.getCameo(cameoId)) {
                is DomainResult.Success -> out += UnfinishedCameo(row, r.value)
                is DomainResult.Failure ->
                    if (r.error.code == "CAMEO_NOT_FOUND") {
                        pending.delete(row.clientRequestId)
                    } else {
                        out += UnfinishedCameo(row, null)
                    }
            }
        }
        return out
    }
}

class DeletePendingCameoUseCase(private val pending: PendingCameoRepository) {
    suspend operator fun invoke(clientRequestId: String) = pending.delete(clientRequestId)
}
