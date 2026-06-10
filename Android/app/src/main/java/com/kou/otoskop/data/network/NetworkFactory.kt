package com.kou.otoskop.data.network

import com.kou.otoskop.core.AppConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Retrofit + OkHttp factory. ESP32 base URL'i çalışma anında
 * [Esp32Endpoint.setHostPort] ile değişir; OkHttp interceptor'ı `__esp32__`
 * placeholder host'unu gerçek host:port ile yeniden yazar.
 *
 * Bu approach: tek `Esp32Api` instance kalır, IP değişiminde Retrofit'i
 * yeniden inşa etmek zorunda kalmayız.
 */
object NetworkFactory {

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val converter = MoshiConverterFactory.create(moshi)

    private val logger = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    fun createOkHttp(timeoutMs: Long): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .addInterceptor(logger)
        .build()

    fun createEsp32(endpoint: Esp32Endpoint): Esp32Api {
        val client = OkHttpClient.Builder()
            .connectTimeout(AppConfig.ESP32_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(AppConfig.ESP32_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(AppConfig.ESP32_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .addInterceptor(EndpointRewriteInterceptor(endpoint))
            .addInterceptor(logger)
            .build()

        return Retrofit.Builder()
            .baseUrl("http://placeholder.invalid/")
            .client(client)
            .addConverterFactory(converter)
            .build()
            .create(Esp32Api::class.java)
    }

    /**
     * Test'lerde [overrideClient] ile MockWebServer URL'ine direkt bağlanabiliriz.
     */
    fun createBackend(
        baseUrl: String = AppConfig.DEFAULT_BACKEND_BASE_URL,
        overrideClient: OkHttpClient? = null,
    ): BackendApi {
        val client = overrideClient ?: createOkHttp(AppConfig.BACKEND_TIMEOUT_MS)
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(converter)
            .build()
            .create(BackendApi::class.java)
    }

    fun createAstro(baseUrl: String = AppConfig.ASTRO_BASE_URL): AstroApi =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(createOkHttp(AppConfig.BACKEND_TIMEOUT_MS))
            .addConverterFactory(converter)
            .build()
            .create(AstroApi::class.java)

    fun createGemini(baseUrl: String = AppConfig.GEMINI_BASE_URL): GeminiApi =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(createOkHttp(AppConfig.BACKEND_TIMEOUT_MS))
            .addConverterFactory(converter)
            .build()
            .create(GeminiApi::class.java)
}

/**
 * Gemini API anahtarını thread-safe tutan basit holder. Bağlantı ekranında
 * güncellenir; sonraki doğrulama istekleri yeni anahtarla gider.
 */
class GeminiConfig(key: String = "") {
    @Volatile
    var apiKey: String = key
}

/**
 * ESP32 host/port'unu thread-safe şekilde tutan küçük holder.
 * UI bağlantı ekranında bunu güncellediğinde, sonraki istekler yeni
 * adresle gider.
 */
class Esp32Endpoint(
    host: String,
    port: Int,
    streamPort: Int = AppConfig.DEFAULT_ESP32_STREAM_PORT,
) {
    @Volatile private var _host: String = host
    @Volatile private var _port: Int = port
    @Volatile private var _streamPort: Int = streamPort

    val host: String get() = _host
    val port: Int get() = _port
    val streamPort: Int get() = _streamPort

    fun set(host: String, port: Int) {
        _host = host.trim().ifEmpty { AppConfig.DEFAULT_ESP32_IP }
        _port = port.coerceIn(1, 65535)
    }

    fun baseUrl(): HttpUrl = "http://$_host:$_port/".toHttpUrl()

    /** MJPEG yayın ayrı port'tan gelir (ESP WebServer tıkanmasını önler). */
    fun streamUrl(): String = "http://$_host:$_streamPort/stream"
}
