package com.kou.otoskop.data.model

/**
 * Telefon sensörlerinin birleşik snapshot'ı. SensorRepository bunu üretir,
 * tüm UI bu tek tipi tüketir.
 */
data class PhoneSensorData(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val gpsAccuracyM: Float? = null,
    /** 0..360 manyetik kuzeyden saat yönü. */
    val compassHeading: Float? = null,
    /** Pitch = yukarı/aşağı eğim => kullanıcının baktığı altitude. */
    val pitchDeg: Float? = null,
    val rollDeg: Float? = null,
    val compassCalibrated: Boolean = true,
) {
    val hasGps: Boolean get() = latitude != null && longitude != null
    val hasOrientation: Boolean get() = compassHeading != null && pitchDeg != null

    /** Pitch => altitude (telefon ufukla paralelken ~0, dik yukarı ~90). */
    val derivedAltitude: Double
        get() = (pitchDeg ?: 0f).toDouble().coerceIn(-90.0, 90.0)

    val derivedAzimuth: Double
        get() = ((compassHeading ?: 0f).toDouble() % 360.0 + 360.0) % 360.0
}
