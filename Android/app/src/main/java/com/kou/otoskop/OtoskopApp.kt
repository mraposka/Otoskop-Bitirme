package com.kou.otoskop

import android.app.Application
import android.content.SharedPreferences
import com.kou.otoskop.core.AppConfig
import com.kou.otoskop.data.network.Esp32Endpoint
import com.kou.otoskop.data.network.GeminiConfig
import com.kou.otoskop.data.network.AstronomyConfig
import com.kou.otoskop.data.capture.CaptureRepository
import com.kou.otoskop.data.network.NetworkFactory
import com.kou.otoskop.data.repository.BackendRepository
import com.kou.otoskop.data.repository.DemoBackendRepository
import com.kou.otoskop.data.repository.DemoEsp32Repository
import com.kou.otoskop.data.repository.DirectBackendRepository
import com.kou.otoskop.data.repository.Esp32Repository
import com.kou.otoskop.data.repository.HttpEsp32Repository
import com.kou.otoskop.data.repository.SensorRepository

/**
 * Servis locator — modüler test edilebilirlik için her şeyi tek yerde
 * yönetiyoruz. Daha büyük projede Hilt/Koin uygundur; bu boyutta
 * minimal manual DI yeterli.
 *
 * **Demo modu:** ESP32/backend olmadan UI akışını denemek için
 * `setDemoMode(true)` çağrılır; repository örnekleri değiştirilir.
 */
class OtoskopApp : Application() {

    private lateinit var prefs: SharedPreferences

    lateinit var esp32Endpoint: Esp32Endpoint
        private set

    /** Aktif ESP32 repository (HTTP veya demo). */
    lateinit var esp32Repo: Esp32Repository
        private set

    lateinit var backendRepo: BackendRepository
        private set

    lateinit var sensorRepo: SensorRepository
        private set

    /** Çekilen foto/videoların yerel arşivi. */
    lateinit var captureRepo: CaptureRepository
        private set

    private lateinit var httpEsp32Repo: HttpEsp32Repository
    private lateinit var directBackendRepo: DirectBackendRepository
    private lateinit var demoEsp32Repo: DemoEsp32Repository
    private lateinit var demoBackendRepo: DemoBackendRepository

    /** Gemini API anahtarı holder'ı (görüntü doğrulama için). */
    lateinit var geminiConfig: GeminiConfig
        private set

    /** Astronomy API kimlik bilgisi (gök cismi listesi yedek kaynağı). */
    lateinit var astronomyConfig: AstronomyConfig
        private set

    val isDemoMode: Boolean
        get() = prefs.getBoolean(PREF_DEMO_MODE, false)

    val geminiApiKey: String
        get() = geminiConfig.apiKey

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        val savedHost = prefs.getString(PREF_ESP32_HOST, AppConfig.DEFAULT_ESP32_IP)
            ?.takeIf { it.isNotBlank() } ?: AppConfig.DEFAULT_ESP32_IP
        esp32Endpoint = Esp32Endpoint(
            host = savedHost,
            port = AppConfig.DEFAULT_ESP32_PORT,
        )
        geminiConfig = GeminiConfig(prefs.getString(PREF_GEMINI_KEY, "").orEmpty())
        astronomyConfig = AstronomyConfig(
            applicationId = prefs.getString(PREF_ASTRONOMY_APP_ID, "").orEmpty(),
            applicationSecret = prefs.getString(PREF_ASTRONOMY_APP_SECRET, "").orEmpty(),
        )

        httpEsp32Repo = HttpEsp32Repository(
            api = NetworkFactory.createEsp32(esp32Endpoint),
            endpoint = esp32Endpoint,
        )
        directBackendRepo = DirectBackendRepository(
            astro = NetworkFactory.createAstro(),
            astronomy = NetworkFactory.createAstronomy(astronomyConfig),
            astronomyConfigured = { astronomyConfig.isConfigured },
            gemini = NetworkFactory.createGemini(),
            model = AppConfig.GEMINI_MODEL,
            apiKeyProvider = { geminiConfig.apiKey },
        )
        demoEsp32Repo = DemoEsp32Repository()
        demoBackendRepo = DemoBackendRepository()

        if (isDemoMode) {
            esp32Repo = demoEsp32Repo
            backendRepo = demoBackendRepo
        } else {
            esp32Repo = httpEsp32Repo
            backendRepo = directBackendRepo
        }

        sensorRepo = SensorRepository(applicationContext)
        captureRepo = CaptureRepository(applicationContext)
    }

    fun setDemoMode(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_DEMO_MODE, enabled).apply()
        esp32Repo = if (enabled) demoEsp32Repo else httpEsp32Repo
        backendRepo = if (enabled) demoBackendRepo else directBackendRepo
    }

    fun setGeminiApiKey(key: String) {
        val trimmed = key.trim()
        geminiConfig.apiKey = trimmed
        prefs.edit().putString(PREF_GEMINI_KEY, trimmed).apply()
    }

    fun setAstronomyCredentials(applicationId: String, applicationSecret: String) {
        val id = applicationId.trim()
        val secret = applicationSecret.trim()
        astronomyConfig.applicationId = id
        astronomyConfig.applicationSecret = secret
        prefs.edit()
            .putString(PREF_ASTRONOMY_APP_ID, id)
            .putString(PREF_ASTRONOMY_APP_SECRET, secret)
            .apply()
    }

    /** ESP32 adresini (IP veya otoskop.local) kalıcı kaydeder. */
    fun setEsp32Host(host: String) {
        val h = host.trim()
        if (h.isNotEmpty()) prefs.edit().putString(PREF_ESP32_HOST, h).apply()
    }

    companion object {
        private const val PREFS_NAME = "otoskop_prefs"
        private const val PREF_DEMO_MODE = "demo_mode"
        private const val PREF_GEMINI_KEY = "gemini_api_key"
        private const val PREF_ASTRONOMY_APP_ID = "astronomy_app_id"
        private const val PREF_ASTRONOMY_APP_SECRET = "astronomy_app_secret"
        private const val PREF_ESP32_HOST = "esp32_host"
    }
}
