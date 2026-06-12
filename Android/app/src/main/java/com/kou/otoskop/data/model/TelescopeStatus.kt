package com.kou.otoskop.data.model

import com.squareup.moshi.Json

/**
 * ESP32 `/status` cevabı.
 * Eksik alanlar default ile gelir.
 */
data class TelescopeStatus(
    @Json(name = "azimuth") val azimuth: Double = 0.0,
    @Json(name = "altitude") val altitude: Double = 0.0,
    @Json(name = "targetAzimuth") val targetAzimuth: Double = 0.0,
    @Json(name = "targetAltitude") val targetAltitude: Double = 0.0,
    @Json(name = "servoAz") val servoAz: Double = 0.0,
    @Json(name = "servoAlt") val servoAlt: Double = 0.0,
    @Json(name = "gpsFix") val gpsFix: Boolean = false,
    @Json(name = "imuOk") val imuOk: Boolean = false,
    @Json(name = "tracking") val tracking: Boolean = false,
    @Json(name = "targetLocked") val targetLocked: Boolean = false,
    /** Mega'dan gelen son ham telemetri satırı (debug konsolu için). */
    @Json(name = "megaRaw") val megaRaw: String = "",
    /** Son Mega satırının yaşı (ms). -1 = Mega'dan hiç veri gelmedi. */
    @Json(name = "megaAgeMs") val megaAgeMs: Long = -1,
    /** ESP'nin Mega'dan aldığı toplam tam satır sayısı. */
    @Json(name = "megaLines") val megaLines: Long = 0,
    /** Serial2'den okunan toplam ham byte. 0 = fiziksel hat sorunu. */
    @Json(name = "megaBytes") val megaBytes: Long = 0,
    /** Son ham byte'ın yaşı (ms). -1 = hiç byte gelmedi. */
    @Json(name = "megaByteAgeMs") val megaByteAgeMs: Long = -1,
    /** Aktif yön kalibrasyon offset'i (azimut, derece). */
    @Json(name = "azOff") val azOffset: Double = 0.0,
    /** Aktif yön kalibrasyon offset'i (altitude, derece). */
    @Json(name = "altOff") val altOffset: Double = 0.0,
    /** Aktif altitude yukarı limiti (derece). */
    @Json(name = "altMax") val altMax: Double = 90.0,
) {
    companion object {
        val EMPTY = TelescopeStatus()
    }
}
