package com.kimhit.cameo.domain.repository

import com.kimhit.cameo.domain.model.Clip
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.model.LedgerPage
import com.kimhit.cameo.domain.model.CameoOrder
import com.kimhit.cameo.domain.model.PendingCameo
import com.kimhit.cameo.domain.model.Points
import com.kimhit.cameo.domain.model.Quota

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
}

interface PendingCameoRepository {
    /** 同片段已有 CREATE 草稿就回傳它（重用同一個 key），否則建立新草稿。 */
    suspend fun getOrCreateDraft(clipId: String): PendingCameo
    suspend fun markCreated(clientRequestId: String, cameoId: String)
    suspend fun recordError(clientRequestId: String, message: String?)
    /** 同時刪除照片檔。 */
    suspend fun delete(clientRequestId: String)

    /** 只刪照片檔並把照片路徑設為 null，資料列保留；檔案不存在不算錯。 */
    suspend fun deletePhoto(clientRequestId: String)

    /**
     * 把 [sourcePath] 的檔案移到正式位置並更新草稿的照片路徑，覆蓋舊照片。成功回 true。
     * 來源檔不存在回 false，且不動既有照片與資料列。[sourcePath] 已是正式路徑時直接回 true。
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
