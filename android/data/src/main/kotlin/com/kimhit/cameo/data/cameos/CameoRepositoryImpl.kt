package com.kimhit.cameo.data.cameos

import com.kimhit.cameo.data.network.ApiService
import com.kimhit.cameo.data.network.CameoOrderResponse
import com.kimhit.cameo.data.network.CreateCameoRequest
import com.kimhit.cameo.data.network.UploadApi
import com.kimhit.cameo.data.network.safeCall
import com.kimhit.cameo.domain.model.Cameo
import com.kimhit.cameo.domain.model.CameoOrder
import com.kimhit.cameo.domain.model.CameoStatus
import com.kimhit.cameo.domain.model.CameoUpload
import com.kimhit.cameo.domain.model.Clip
import com.kimhit.cameo.domain.model.DomainError
import com.kimhit.cameo.domain.model.DomainResult
import com.kimhit.cameo.domain.repository.CameoRepository
import com.kimhit.cameo.domain.usecase.PHOTO_MISSING
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody

internal class CameoRepositoryImpl(
    private val api: ApiService,
    private val uploadApi: UploadApi,
) : CameoRepository {
    override suspend fun createCameo(clientRequestId: String, clipId: String): DomainResult<CameoOrder> =
        safeCall({ api.createCameo(clientRequestId, CreateCameoRequest(clipId)) }, ::toOrder)

    override suspend fun getCameo(id: String): DomainResult<CameoOrder> =
        safeCall({ api.getCameo(id) }, ::toOrder)

    override suspend fun uploadSelfie(url: String, photoPath: String): DomainResult<CameoOrder> {
        val file = File(photoPath)
        if (!file.isFile) {
            return DomainResult.Failure(DomainError(message = null, retryable = false, code = PHOTO_MISSING))
        }
        return safeCall({ uploadApi.upload(url, file.asRequestBody(JPEG)) }, ::toOrder)
    }

    override suspend fun submitCameo(cameoId: String): DomainResult<CameoOrder> =
        safeCall({ api.submit(cameoId) }, ::toOrder)
}

private val JPEG = "image/jpeg".toMediaType()

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
