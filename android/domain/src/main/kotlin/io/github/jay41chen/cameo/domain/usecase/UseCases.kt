package io.github.jay41chen.cameo.domain.usecase

import io.github.jay41chen.cameo.domain.model.Clip
import io.github.jay41chen.cameo.domain.model.DomainError
import io.github.jay41chen.cameo.domain.model.DomainResult
import io.github.jay41chen.cameo.domain.model.LedgerPage
import io.github.jay41chen.cameo.domain.model.CameoOrder
import io.github.jay41chen.cameo.domain.model.CameoPage
import io.github.jay41chen.cameo.domain.model.CameoShare
import io.github.jay41chen.cameo.domain.model.CameoStatus
import io.github.jay41chen.cameo.domain.model.PendingStep
import io.github.jay41chen.cameo.domain.model.Points
import io.github.jay41chen.cameo.domain.model.Quota
import io.github.jay41chen.cameo.domain.model.UnfinishedCameo
import io.github.jay41chen.cameo.domain.repository.CameoRepository
import io.github.jay41chen.cameo.domain.repository.CameoShareRepository
import io.github.jay41chen.cameo.domain.repository.ClipRepository
import io.github.jay41chen.cameo.domain.repository.PendingCameoRepository
import io.github.jay41chen.cameo.domain.repository.PointsRepository
import io.github.jay41chen.cameo.domain.repository.QuotaRepository

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

/** [cursor] 為 null 取第一頁；之後用上一頁的 [CameoPage.nextCursor]。 */
class GetCameoPageUseCase(private val repository: CameoRepository) {
    suspend operator fun invoke(cursor: String? = null): DomainResult<CameoPage> =
        repository.listCameos(cursor)
}

/** 查單一訂單的最新狀態。 */
class GetCameoUseCase(private val repository: CameoRepository) {
    suspend operator fun invoke(cameoId: String): DomainResult<CameoOrder> = repository.getCameo(cameoId)
}

class ShareCameoUseCase(private val repository: CameoShareRepository) {
    suspend operator fun invoke(cameoId: String): DomainResult<CameoShare> = repository.share(cameoId)
}

class UnshareCameoUseCase(private val repository: CameoShareRepository) {
    suspend operator fun invoke(cameoId: String): DomainResult<Unit> = repository.unshare(cameoId)
}

class GetQuotaUseCase(private val repository: QuotaRepository) {
    suspend operator fun invoke(): DomainResult<Quota> = repository.getQuota()
}

/**
 * 確認支付並建單。順序固定：先寫本機草稿，再附上照片，才送 POST。
 * - [photoSourcePath] 不為 null 時先 attachPhoto；失敗，或沒帶路徑且草稿沒有照片，
 *   回 [PHOTO_MISSING]、不送 POST、保留草稿。
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
    suspend operator fun invoke(clipId: String, photoSourcePath: String?): DomainResult<CreatedCameo> {
        val draft = pending.getOrCreateDraft(clipId)
        if (photoSourcePath != null) {
            if (!pending.attachPhoto(draft.clientRequestId, photoSourcePath)) return photoMissing()
        } else if (draft.photoPath == null) {
            return photoMissing()
        }
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
        return when (result) {
            is DomainResult.Success -> DomainResult.Success(CreatedCameo(draft.clientRequestId, result.value))
            is DomainResult.Failure -> result
        }
    }
}

/** 建單成功的結果；[clientRequestId] 是本機草稿的 key，後續上傳與送出用它找回草稿。 */
data class CreatedCameo(val clientRequestId: String, val order: CameoOrder)

/** 本機照片遺失，無法送出；不是後端錯誤，也不會送 POST。 */
const val PHOTO_MISSING = "PHOTO_MISSING"

private fun photoMissing() =
    DomainResult.Failure(DomainError(message = null, retryable = false, code = PHOTO_MISSING))

/** 後端都在查過冪等鍵之後才回這些錯誤，所以確定此 key 沒有訂單，草稿可以刪。 */
private val NO_ORDER_CODES = setOf("INSUFFICIENT_POINTS", "DAILY_LIMIT_REACHED", "CLIP_NOT_FOUND")

/**
 * 列出本機未完成的客串，並向後端補上目前狀態。
 * 排除已送出（QUEUED、GENERATING、SUCCEEDED、FAILED）與查不到狀態但步驟為 TRACK 的草稿，這些草稿不刪；
 * CAMEO_NOT_FOUND 刪草稿。
 */
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
                is DomainResult.Success -> when (r.value.cameo.status) {
                    // 已送出：不列、不動草稿（草稿仍負責通知去重）
                    CameoStatus.QUEUED, CameoStatus.GENERATING, CameoStatus.SUCCEEDED, CameoStatus.FAILED -> Unit
                    // 過期訂單用不到照片，順手刪掉（資料列保留供使用者看到並選擇重來／移除）
                    CameoStatus.EXPIRED -> {
                        pending.deletePhoto(row.clientRequestId)
                        out += UnfinishedCameo(row.copy(photoPath = null), r.value)
                    }
                    CameoStatus.AWAITING_UPLOAD, CameoStatus.UPLOADED, CameoStatus.UNKNOWN ->
                        out += UnfinishedCameo(row, r.value)
                }
                is DomainResult.Failure -> when {
                    r.error.code == "CAMEO_NOT_FOUND" -> pending.delete(row.clientRequestId)
                    // 查不到狀態但本機已送出：不列、不動草稿
                    row.step == PendingStep.TRACK -> Unit
                    else -> out += UnfinishedCameo(row, null)
                }
            }
        }
        return out
    }
}

class DeletePendingCameoUseCase(private val pending: PendingCameoRepository) {
    suspend operator fun invoke(clientRequestId: String) = pending.delete(clientRequestId)
}

/** 訂單結束後認領草稿：刪除成功才回 true，呼叫端只在 true 時發完成通知。 */
class ClaimFinishedCameoUseCase(private val pending: PendingCameoRepository) {
    suspend operator fun invoke(cameoId: String): Boolean = pending.claimFinishedCameo(cameoId)
}
