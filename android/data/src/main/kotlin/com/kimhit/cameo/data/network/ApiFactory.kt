package com.kimhit.cameo.data.network

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

internal val ApiJson = Json { ignoreUnknownKeys = true }

internal fun createApiService(config: DataConfig): ApiService {
    val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("Authorization", "Bearer ${config.authToken}")
                    .build(),
            )
        }
        .build()
    val base = if (config.baseUrl.endsWith("/")) config.baseUrl else config.baseUrl + "/"
    return Retrofit.Builder()
        .baseUrl(base)
        .client(client)
        .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ApiService::class.java)
}
