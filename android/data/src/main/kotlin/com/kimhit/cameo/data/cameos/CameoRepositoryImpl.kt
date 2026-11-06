package com.kimhit.cameo.data.cameos

import com.kimhit.cameo.data.network.ApiService
import com.kimhit.cameo.data.network.CameoOrderResponse
import com.kimhit.cameo.data.network.CreateCameoRequest
import com.kimhit.cameo.data.network.safeCall
import com.kimhit.cameo.domain.model.Cameo
import com.kimhit.cameo.domain.model.CameoOrder
import com.kimhit.cameo.domain.model.CameoStatus
import com.kimhit.cameo.domain.model.CameoUpload
import com.kimhit.cameo.domain.model.Clip
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.repository.CameoRepository

internal class CameoRepositoryImpl(private val api: ApiService) : CameoRepository {
    override suspend fun createCameo(clientRequestId: String, clipId: String): DomainResult<CameoOrder> =
        safeCall({ api.createCameo(clientRequestId, CreateCameoRequest(clipId)) }, ::toOrder)

    override suspend fun getCameo(id: String): DomainResult<CameoOrder> =
        safeCall({ api.getCameo(id) }, ::toOrder)
}

private fun toOrder(body: CameoOrderResponse) = CameoOrder(
    cameo = Cameo(
        id = body.cameo.id,
        clip = Clip(body.cameo.clip.id, body.cameo.clip.title, body.cameo.clip.cost),
        status = runCatching { CameoStatus.valueOf(body.cameo.status) }.getOrDefault(CameoStatus.UNKNOWN),
        displayTitle = body.cameo.display?.title,
        displayMessage = body.cameo.display?.message,
    ),
    upload = body.upload?.let { CameoUpload(it.url, it.expiresAt) },
)
