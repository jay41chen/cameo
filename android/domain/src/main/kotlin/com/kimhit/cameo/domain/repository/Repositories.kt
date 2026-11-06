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
}

interface PendingCameoRepository {
    /** 同片段已有 CREATE 草稿就回傳它（重用同一個 key），否則建立新草稿。 */
    suspend fun getOrCreateDraft(clipId: String): PendingCameo
    suspend fun markCreated(clientRequestId: String, cameoId: String)
    suspend fun recordError(clientRequestId: String, message: String?)
    suspend fun delete(clientRequestId: String)
    suspend fun list(): List<PendingCameo>
}
