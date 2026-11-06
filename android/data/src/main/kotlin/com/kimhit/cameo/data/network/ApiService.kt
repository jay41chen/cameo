package com.kimhit.cameo.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

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
}
