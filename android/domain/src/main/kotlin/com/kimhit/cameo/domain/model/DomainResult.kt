package com.kimhit.cameo.domain.model

/**
 * [message] 為後端提供給使用者的文字；沒有時由呼叫端用通用文字。
 * [code] 為後端的錯誤代碼（例如 `CAMEO_NOT_FOUND`），沒有時為 null。
 */
data class DomainError(val message: String?, val retryable: Boolean, val code: String? = null)

sealed interface DomainResult<out T> {
    data class Success<T>(val value: T) : DomainResult<T>
    data class Failure(val error: DomainError) : DomainResult<Nothing>
}
