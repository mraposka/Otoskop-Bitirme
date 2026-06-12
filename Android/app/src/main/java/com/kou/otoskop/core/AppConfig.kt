package com.kou.otoskop.core

/**
 * Uygulama genelinde sabitler. Kullanıcı bağlantı ekranında ESP32 IP'sini
 * değiştirebilir; geri kalan değerler debug için sabit kalır.
 */
object AppConfig {
    /**
     * Varsayılan ESP32 adresi. ESP kendini mDNS ile "otoskop.local" olarak
     * yayınlar; çoğu cihazda doğrudan çözümlenir. Çözümlenmezse bağlantı
     * ekranındaki "Otomatik bul" (NSD) keşfi gerçek IP'yi bulur, yoksa kullanıcı
     * elle IP girer (örn. 192.168.4.1 — ESP'nin Otoskop AP'si).
     */
    const val DEFAULT_ESP32_IP: String = "otoskop.local"
    const val DEFAULT_ESP32_PORT: Int = 80

    /**
     * MJPEG canlı yayın ESP tarafında ayrı bir port'ta (81) servis edilir.
     * Tek iş parçacıklı ESP WebServer'ında /stream sonsuz döngüsü API port'unu
     * (80) tıkar; ayrı port + döngü içinde API pompalama ile /status ve komutlar
     * yayın sırasında da yanıt verir.
     */
    const val DEFAULT_ESP32_STREAM_PORT: Int = 81

    /** Geliştirme aşamasında local backend; emulator'dan host'a 10.0.2.2. */
    const val DEFAULT_BACKEND_BASE_URL: String = "http://10.0.2.2:8000/"

    /** Gözlemlenebilir objeler — keysiz yedek (sık 503). */
    const val ASTRO_BASE_URL: String = "https://api.visibleplanets.dev/"

    /** Astronomy API — ücretsiz tier, Application ID + Secret gerekir. */
    const val ASTRONOMY_BASE_URL: String = "https://api.astronomyapi.com/"

    /** Gemini multimodal API tabanı + en ucuz vizyon modeli. */
    const val GEMINI_BASE_URL: String = "https://generativelanguage.googleapis.com/"
    const val GEMINI_MODEL: String = "gemini-2.5-flash-lite"

    const val ESP32_TIMEOUT_MS: Long = 8_000
    const val BACKEND_TIMEOUT_MS: Long = 30_000

    /** Telescope status polling sıklığı. */
    const val STATUS_POLL_INTERVAL_MS: Long = 1_000

    /** Demo modunda GPS yoksa kullanılan sabit konum (İstanbul yaklaşık). */
    const val DEMO_FALLBACK_LATITUDE: Double = 41.0082
    const val DEMO_FALLBACK_LONGITUDE: Double = 28.9784

    /** SkyArea tarama alanı için varsayılan yarı genişlik (derece). */
    const val DEFAULT_AREA_HALF_WIDTH_DEG: Float = 15.0f
}
