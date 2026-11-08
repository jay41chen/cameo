package io.github.jay41chen.cameo.ui

/** [message] 為後端提供的文字；null 時畫面顯示通用文字。 */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Success<T>(val value: T) : Load<T>
    data class Error(val message: String?) : Load<Nothing>
}
