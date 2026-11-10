package io.github.jay41chen.cameo.domain.repository

import io.github.jay41chen.cameo.domain.model.CameoPage
import io.github.jay41chen.cameo.domain.model.CameoShare
import io.github.jay41chen.cameo.domain.model.Clip
import io.github.jay41chen.cameo.domain.model.DomainResult
import io.github.jay41chen.cameo.domain.model.LedgerPage
import io.github.jay41chen.cameo.domain.model.CameoOrder
import io.github.jay41chen.cameo.domain.model.PendingCameo
import io.github.jay41chen.cameo.domain.model.Points
import io.github.jay41chen.cameo.domain.model.Quota

interface ClipRepository {
    suspend fun getClips(): DomainResult<List<Clip>>
}

interface PointsRepository {
    suspend fun getPoints(): DomainResult<Points>
    suspend fun getLedger(cursor: String?): DomainResult<LedgerPage>
}

interface QuotaRepository {
    suspend fun getQuota(): DomainResult<Quota>
}

interface CameoRepository {
    suspend fun createCameo(clientRequestId: String, clipId: String): DomainResult<CameoOrder>
    suspend fun getCameo(id: String): DomainResult<CameoOrder>

    /** PUT 照片到 [url]（後端回的相對路徑）；本機檔案不存在回 `PHOTO_MISSING`，且不打 API。 */
    suspend fun uploadSelfie(url: String, photoPath: String): DomainResult<CameoOrder>

    suspend fun submitCameo(cameoId: String): DomainResult<CameoOrder>

    /** [cursor] 為 null 取第一頁；之後用上一頁的 nextCursor。 */
    suspend fun listCameos(cursor: String?): DomainResult<CameoPage>
}

interface CameoShareRepository {
    /** 開啟分享並回傳連結；非 SUCCEEDED 回 `NOT_SHAREABLE`，訂單不存在回 `CAMEO_NOT_FOUND`。 */
    suspend fun share(cameoId: String): DomainResult<CameoShare>

    /** 取消分享，冪等；訂單不存在回 `CAMEO_NOT_FOUND`。 */
    suspend fun unshare(cameoId: String): DomainResult<Unit>
}

interface PendingCameoRepository {
    /** 同片段已有尚未建單的草稿（DRAFT 或 CREATE）就回傳它（重用同一個 key，不改它的 step），否則建立新的 DRAFT 草稿。 */
    suspend fun getOrCreateDraft(clipId: String): PendingCameo

    /** 按下確認支付：把 DRAFT 改成 CREATE；列不存在或已不是 DRAFT 時不寫。 */
    suspend fun markConfirming(clientRequestId: String)
    suspend fun markCreated(clientRequestId: String, cameoId: String)
    suspend fun recordError(clientRequestId: String, message: String?)
    /** 同時刪除照片檔。 */
    suspend fun delete(clientRequestId: String)

    /**
     * 訂單已結束（`SUCCEEDED`／`FAILED`）：原子刪除 [cameoId] 對應的草稿列，並刪除它的本機照片。
     * 真的刪到列才回 true；已被刪過或沒有對應草稿回 false。完成通知「只發一次」靠這個回傳值。
     */
    suspend fun claimFinishedCameo(cameoId: String): Boolean

    /** 只刪照片檔並把照片路徑設為 null，資料列保留；檔案不存在不算錯。 */
    suspend fun deletePhoto(clientRequestId: String)

    /**
     * 把 [sourcePath] 的檔案移到正式位置並更新草稿的照片路徑，覆蓋舊照片。成功回 true。
     * 來源檔不存在回 false，且不動既有照片與資料列；列不存在或搬檔期間列被刪（認領）也回 false，且不重建列。[sourcePath] 已是正式路徑時直接回 true。
     */
    suspend fun attachPhoto(clientRequestId: String, sourcePath: String): Boolean
    suspend fun list(): List<PendingCameo>

    /** 列不存在回 null。 */
    suspend fun get(clientRequestId: String): PendingCameo?

    /** 上傳成功：`step=SUBMIT`、刪照片檔、清空照片路徑與 `lastError`。列不存在不做事。 */
    suspend fun markUploaded(clientRequestId: String)

    /** 送出成功：`step=TRACK`、清 `lastError`。列不存在不做事。 */
    suspend fun markSubmitted(clientRequestId: String)
}
