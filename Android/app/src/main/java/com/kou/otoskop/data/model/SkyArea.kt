package com.kou.otoskop.data.model

import com.kou.otoskop.core.AstroMath

/**
 * Gökyüzü penceresi: merkez azimut/altitude + yarı genişlik. UI bunu
 * köşelere açar.
 */
data class SkyArea(
    val centerAzimuth: Double,
    val centerAltitude: Double,
    val halfWidthDeg: Double,
) {
    val azimuthMin: Double
        get() = AstroMath.normalizeAzimuth(centerAzimuth - halfWidthDeg)
    val azimuthMax: Double
        get() = AstroMath.normalizeAzimuth(centerAzimuth + halfWidthDeg)
    val altitudeMin: Double
        get() = (centerAltitude - halfWidthDeg).coerceIn(-90.0, 90.0)
    val altitudeMax: Double
        get() = (centerAltitude + halfWidthDeg).coerceIn(-90.0, 90.0)
}
