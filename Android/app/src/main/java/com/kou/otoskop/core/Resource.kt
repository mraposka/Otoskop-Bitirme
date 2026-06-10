package com.kou.otoskop.core

/**
 * Servis katmanı çağrılarının sonuç tipi. UI bunu `when` ile ayrıştırır;
 * `try/catch` her yerde dağılmaz.
 */
sealed class Resource<out T> {
    data class Success<T>(val value: T) : Resource<T>()
    data class Failure(val error: AppError) : Resource<Nothing>()

    inline fun <R> fold(onSuccess: (T) -> R, onFailure: (AppError) -> R): R =
        when (this) {
            is Success -> onSuccess(value)
            is Failure -> onFailure(error)
        }

    fun getOrNull(): T? = (this as? Success)?.value
    fun errorOrNull(): AppError? = (this as? Failure)?.error
}
