package com.kimhit.cameo.data.network

import com.kimhit.cameo.domain.model.DomainError
import com.kimhit.cameo.domain.model.DomainResult
import kotlinx.coroutines.CancellationException
import retrofit2.Response

/**
 * 把 Retrofit 回應轉成 [DomainResult]。
 * - 非 2xx：解析 `{code,message,retryable}`；不是 JSON 時 message 為 null，5xx 視為可重試。
 * - 網路例外或 2xx 但內容無法解析：message 為 null，視為可重試。
 */
internal suspend fun <T, R> safeCall(
    call: suspend () -> Response<T>,
    map: (T) -> R,
): DomainResult<R> = try {
    val response = call()
    val body = response.body()
    if (response.isSuccessful && body != null) {
        DomainResult.Success(map(body))
    } else {
        DomainResult.Failure(parseError(response))
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    DomainResult.Failure(DomainError(message = null, retryable = true))
}

private fun parseError(response: Response<*>): DomainError {
    val default = response.code() >= 500 || response.isSuccessful
    val raw = try {
        response.errorBody()?.string()
    } catch (e: Exception) {
        null
    }
    val parsed = raw?.let {
        try {
            ApiJson.decodeFromString(ErrorBody.serializer(), it)
        } catch (e: Exception) {
            null
        }
    }
    return DomainError(
        message = parsed?.message?.takeIf { it.isNotBlank() },
        retryable = parsed?.retryable ?: default,
    )
}
