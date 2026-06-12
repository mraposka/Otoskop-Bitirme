package com.kou.otoskop.data.network

import android.util.Base64
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Astronomy API Basic Auth: Base64(`applicationId:applicationSecret`).
 * Kimlik bilgisi yoksa istek Authorization'sız gider (403 döner, fallback devreye girer).
 */
class AstronomyAuthInterceptor(
    private val config: AstronomyConfig,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val authHeader = config.basicAuthHeader()
        val authed = if (authHeader != null) {
            request.newBuilder()
                .header("Authorization", authHeader)
                .build()
        } else {
            request
        }
        return chain.proceed(authed)
    }
}

/** Application ID + Secret holder (Bağlantı ekranından güncellenir). */
class AstronomyConfig(
    applicationId: String = "",
    applicationSecret: String = "",
) {
    @Volatile var applicationId: String = applicationId
    @Volatile var applicationSecret: String = applicationSecret

    val isConfigured: Boolean
        get() = applicationId.isNotBlank() && applicationSecret.isNotBlank()

    fun basicAuthHeader(): String? {
        if (!isConfigured) return null
        val raw = "${applicationId.trim()}:${applicationSecret.trim()}"
        val encoded = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "Basic $encoded"
    }
}
