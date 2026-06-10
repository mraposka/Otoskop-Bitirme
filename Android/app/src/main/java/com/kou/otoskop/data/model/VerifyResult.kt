package com.kou.otoskop.data.model

import com.squareup.moshi.Json
import kotlin.math.abs

/** Backend `/verify-image` cevabı. */
data class VerifyResult(
    @Json(name = "verified") val verified: Boolean = false,
    @Json(name = "targetName") val targetName: String = "",
    @Json(name = "azimuthCorrection") val azimuthCorrection: Double = 0.0,
    @Json(name = "altitudeCorrection") val altitudeCorrection: Double = 0.0,
    @Json(name = "message") val message: String = "",
) {
    val needsCorrection: Boolean
        get() = !verified &&
                (abs(azimuthCorrection) > 0.001 || abs(altitudeCorrection) > 0.001)
}
