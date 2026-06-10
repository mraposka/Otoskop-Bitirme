package com.kou.otoskop.core

import kotlin.math.PI

/**
 * Astronomik / geometrik küçük yardımcılar. Tüm metotlar pure - test edilebilir.
 */
object AstroMath {

    /** Açıyı 0..360 aralığına normalize eder. */
    fun normalizeAzimuth(deg: Double): Double {
        var v = deg % 360.0
        if (v < 0) v += 360.0
        return v
    }

    /** "K / KD / D / GD / G / GB / B / KB" — kullanıcıya dost yön etiketi. */
    fun cardinal(azimuth: Double): String {
        val a = normalizeAzimuth(azimuth)
        val labels = arrayOf("K", "KD", "D", "GD", "G", "GB", "B", "KB")
        val idx = (((a + 22.5) / 45).toInt()) % 8
        return labels[idx]
    }

    /** İki azimut arasındaki en kısa açısal fark (-180..180). */
    fun azimuthDelta(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    /**
     * Verilen azimut/altitude bir alan penceresi içinde mi? Azimut'ta
     * 360 etrafında wrap-around hesaba katılır (örn. 350°..10° kuzeyi içerir).
     */
    fun inWindow(
        azimuth: Double, altitude: Double,
        azMin: Double, azMax: Double,
        altMin: Double, altMax: Double,
    ): Boolean {
        if (altitude < altMin || altitude > altMax) return false
        val a = normalizeAzimuth(azimuth)
        val lo = normalizeAzimuth(azMin)
        val hi = normalizeAzimuth(azMax)
        return if (lo <= hi) a in lo..hi else (a >= lo || a <= hi)
    }

    fun degToRad(d: Double): Double = d * PI / 180.0
    fun radToDeg(r: Double): Double = r * 180.0 / PI
}
