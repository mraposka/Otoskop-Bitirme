package com.kou.otoskop.data.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Retrofit base URL'i (placeholder) yerine [endpoint]'in mevcut host/port'unu
 * yazar. Böylece IP runtime'da değiştiğinde Retrofit'i yeniden kurmaya
 * gerek kalmaz.
 */
class EndpointRewriteInterceptor(
    private val endpoint: Esp32Endpoint,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val rewritten = original.url.newBuilder()
            .scheme("http")
            .host(endpoint.host)
            .port(endpoint.port)
            .build()
        return chain.proceed(original.newBuilder().url(rewritten).build())
    }
}
