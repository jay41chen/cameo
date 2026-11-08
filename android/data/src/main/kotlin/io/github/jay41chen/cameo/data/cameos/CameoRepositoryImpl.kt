package io.github.jay41chen.cameo.data.cameos

import io.github.jay41chen.cameo.data.network.ApiService
import io.github.jay41chen.cameo.data.network.CameoDto
import io.github.jay41chen.cameo.data.network.CameoOrderResponse
import io.github.jay41chen.cameo.data.network.CreateCameoRequest
import io.github.jay41chen.cameo.data.network.DataConfig
import io.github.jay41chen.cameo.data.network.UploadApi
import io.github.jay41chen.cameo.data.network.safeCall
import io.github.jay41chen.cameo.domain.model.Cameo
import io.github.jay41chen.cameo.domain.model.CameoOrder
import io.github.jay41chen.cameo.domain.model.CameoPage
import io.github.jay41chen.cameo.domain.model.CameoShare
import io.github.jay41chen.cameo.domain.model.CameoStatus
import io.github.jay41chen.cameo.domain.model.CameoUpload
import io.github.jay41chen.cameo.domain.model.Clip
import io.github.jay41chen.cameo.domain.model.DomainError
import io.github.jay41chen.cameo.domain.model.DomainResult
import io.github.jay41chen.cameo.domain.model.Page
import io.github.jay41chen.cameo.domain.repository.CameoRepository
import io.github.jay41chen.cameo.domain.repository.CameoShareRepository
import io.github.jay41chen.cameo.domain.usecase.PHOTO_MISSING
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.Response

internal class CameoRepositoryImpl(
    private val api: ApiService,
    private val uploadApi: UploadApi,
    private val config: DataConfig,
) : CameoRepository, CameoShareRepository {
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

    override suspend fun listCameos(cursor: String?): DomainResult<CameoPage> =
        safeCall({ api.listCameos(cursor) }) { body ->
            Page(body.items.map { it.toDomain() }, body.nextCursor)
        }

    override suspend fun share(cameoId: String): DomainResult<CameoShare> =
        safeCall({ api.share(cameoId, config.shareDebugBaseUrl) }) { CameoShare(it.share.active, it.share.url) }

    /** 204 沒有 body，Retrofit 給 null；成功時換成 `Unit`，讓 [safeCall] 當成功處理。 */
    override suspend fun unshare(cameoId: String): DomainResult<Unit> =
        safeCall({
            val r = api.unshare(cameoId)
            if (r.isSuccessful) Response.success(Unit) else r
        }) { }
}

private val JPEG = "image/jpeg".toMediaType()

private fun toOrder(body: CameoOrderResponse) = CameoOrder(
    cameo = body.cameo.toDomain(),
    upload = body.upload?.let { CameoUpload(it.url, it.expiresAt) },
)

private fun CameoDto.toDomain() = Cameo(
    id = id,
    clip = Clip(clip.id, clip.title, clip.cost),
    status = runCatching { CameoStatus.valueOf(status) }.getOrDefault(CameoStatus.UNKNOWN),
    displayTitle = display?.title,
    displayMessage = display?.message,
    createdAt = createdAt,
    videoAvailable = videoAvailable,
    share = share?.let { CameoShare(it.active, it.url) },
)
