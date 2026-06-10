package com.kou.otoskop.core

/**
 * Uygulamanın kullanıcıya gösterebileceği hata türleri. UI bu enum'a göre
 * özelleştirilmiş mesaj/aksiyon gösterir; servis katmanından UI'a kadar
 * exception fırlatmak yerine bu tip taşınır.
 */
enum class AppErrorKind {
    ESP32_UNREACHABLE,
    CAMERA_STREAM_FAILED,
    GPS_UNAVAILABLE,
    COMPASS_UNCALIBRATED,
    BACKEND_UNREACHABLE,
    TARGET_NOT_VERIFIED,
    PERMISSION_DENIED,
    UNKNOWN,
}

data class AppError(
    val kind: AppErrorKind,
    val message: String,
    val cause: Throwable? = null,
)
