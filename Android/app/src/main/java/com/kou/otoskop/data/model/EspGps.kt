package com.kou.otoskop.data.model

import com.squareup.moshi.Json

/**
 * ESP32 `/gps` cevabı. Teleskop üzerindeki GPS modülünün konumu.
 * [fix] false ise (modül yok / kilit yok), uygulama telefon GPS'ine düşer.
 */
data class EspGps(
    @Json(name = "lat") val lat: Double = 0.0,
    @Json(name = "lon") val lon: Double = 0.0,
    @Json(name = "fix") val fix: Boolean = false,
) {
    /** Kullanılabilir bir konum kilidi var mı? */
    val hasFix: Boolean get() = fix && (lat != 0.0 || lon != 0.0)
}
