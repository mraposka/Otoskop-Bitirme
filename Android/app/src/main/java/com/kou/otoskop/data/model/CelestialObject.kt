package com.kou.otoskop.data.model

import com.squareup.moshi.Json

/** Backend `/observable-objects` cevabındaki tek obje. */
data class CelestialObject(
    @Json(name = "name") val name: String,
    @Json(name = "type") val type: String = "unknown",
    @Json(name = "azimuth") val azimuth: Double,
    @Json(name = "altitude") val altitude: Double,
    @Json(name = "magnitude") val magnitude: Double = 99.0,
    @Json(name = "visible") val visible: Boolean = true,
)
