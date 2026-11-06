package com.kimhit.cameo.data.network

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

internal val ApiJson = Json { ignoreUnknownKeys = true }

internal fun createMainClient(config: DataConfig): OkHttpClient =
    OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("Authorization", "Bearer ${config.authToken}")
                    .build(),
            )
        }
        .build()

/**
 * 上傳專用 client，由 [main] 衍生。只有上傳關閉 `retryOnConnectionFailure`：
 * 連線中斷時 OkHttp 不會自己重送整個 body，是否重傳由呼叫端決定。
 * 主 client 不全域關閉：觀察到全域關閉重試後，閒置約 8 秒再開首頁出現 network error（4/4；改回 3/3 正常）。
 * 成因 [待確認]。
 */
internal fun createUploadClient(main: OkHttpClient): OkHttpClient =
    main.newBuilder()
        .retryOnConnectionFailure(false)
        .writeTimeout(UPLOAD_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(UPLOAD_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

private const val UPLOAD_TIMEOUT_SEC = 60L

private fun retrofit(client: OkHttpClient, config: DataConfig): Retrofit {
    val base = if (config.baseUrl.endsWith("/")) config.baseUrl else config.baseUrl + "/"
    return Retrofit.Builder()
        .baseUrl(base)
        .client(client)
        .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
        .build()
}

internal fun createApiService(config: DataConfig, client: OkHttpClient = createMainClient(config)): ApiService =
    retrofit(client, config).create(ApiService::class.java)

internal fun createUploadApi(config: DataConfig, main: OkHttpClient = createMainClient(config)): UploadApi =
    retrofit(createUploadClient(main), config).create(UploadApi::class.java)
