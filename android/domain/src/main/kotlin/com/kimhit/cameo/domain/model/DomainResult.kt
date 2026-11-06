package com.kimhit.cameo.domain.model

/** [message] 為後端提供給使用者的文字；沒有時由呼叫端用通用文字。 */
data class DomainError(val message: String?, val retryable: Boolean)

sealed interface DomainResult<out T> {
    data class Success<T>(val value: T) : DomainResult<T>
    data class Failure(val error: DomainError) : DomainResult<Nothing>
}
