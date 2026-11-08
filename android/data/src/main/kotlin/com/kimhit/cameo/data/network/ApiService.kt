package com.kimhit.cameo.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url
import okhttp3.RequestBody

@Serializable
internal data class ClipDto(val id: String, val title: String, val cost: Int)

@Serializable
internal data class ClipsResponse(val items: List<ClipDto>)

@Serializable
internal data class PointsResponse(val available: Int, val held: Int)

@Serializable
internal data class LedgerEntryDto(
    val id: String,
    val type: String,
    val amount: Int,
    @SerialName("cameo_id") val cameoId: String? = null,
    @SerialName("clip_title") val clipTitle: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
internal data class LedgerResponse(
    val items: List<LedgerEntryDto>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
internal data class QuotaResponse(
    val remaining: Int,
    @SerialName("resets_at") val resetsAt: String,
    val display: String,
)

@Serializable
internal data class CreateCameoRequest(@SerialName("clip_id") val clipId: String)

@Serializable
internal data class CameoDisplayDto(val title: String? = null, val message: String? = null)

@Serializable
internal data class CameoDto(
    val id: String,
    val clip: ClipDto,
    val status: String,
    val display: CameoDisplayDto? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("video_available") val videoAvailable: Boolean = false,
    val share: ShareDto? = null,
)

@Serializable
internal data class ShareDto(val active: Boolean = false, val url: String? = null)

@Serializable
internal data class ShareResponse(val share: ShareDto)

@Serializable
internal data class CameoListResponse(
    val items: List<CameoDto>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
internal data class UploadDto(val url: String, @SerialName("expires_at") val expiresAt: String)

@Serializable
internal data class CameoOrderResponse(val cameo: CameoDto, val upload: UploadDto? = null)

@Serializable
internal data class ErrorBody(
    val code: String? = null,
    val message: String? = null,
    val retryable: Boolean? = null,
)

internal interface ApiService {
    @GET("v1/clips")
    suspend fun clips(): Response<ClipsResponse>

    @GET("v1/points")
    suspend fun points(): Response<PointsResponse>

    @GET("v1/points/ledger")
    suspend fun ledger(@Query("cursor") cursor: String?): Response<LedgerResponse>

    @GET("v1/quota")
    suspend fun quota(): Response<QuotaResponse>

    @GET("v1/cameos")
    suspend fun listCameos(@Query("cursor") cursor: String?): Response<CameoListResponse>

    @POST("v1/cameos")
    suspend fun createCameo(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: CreateCameoRequest,
    ): Response<CameoOrderResponse>

    @GET("v1/cameos/{id}")
    suspend fun getCameo(@Path("id") id: String): Response<CameoOrderResponse>

    @POST("v1/cameos/{id}/submit")
    suspend fun submit(@Path("id") id: String): Response<CameoOrderResponse>

    /** [debugBaseUrl] 為 null 時 Retrofit 不帶這個 query。 */
    @PUT("v1/cameos/{id}/share")
    suspend fun share(
        @Path("id") id: String,
        @Query("debug_base_url") debugBaseUrl: String?,
    ): Response<ShareResponse>

    @DELETE("v1/cameos/{id}/share")
    suspend fun unshare(@Path("id") id: String): Response<Unit>
}

/** 只給上傳用，搭配逾時較長且不自動重送的獨立 client（見 [createUploadClient]）。 */
internal interface UploadApi {
    /** [url] 是後端回的相對路徑（`/v1/cameos/{id}/selfie`），由 Retrofit 解析到 base URL。 */
    @PUT
    suspend fun upload(@Url url: String, @Body body: RequestBody): Response<CameoOrderResponse>
}
