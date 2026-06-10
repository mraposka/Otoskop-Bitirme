package com.kou.otoskop.data.network

import com.squareup.moshi.Json
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Visible Planets API (https://api.visibleplanets.dev) - ücretsiz, key gerekmez.
 * Verilen konum/zaman için ufuk üstündeki gezegenler + Ay + Güneş'i
 * azimut/altitude/parlaklık ile döner. Backend sunucuya gerek kalmaz.
 */
interface AstroApi {

    @GET("v3")
    suspend fun visibleBodies(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("time") timeIso: String,
        @Query("aboveHorizon") aboveHorizon: Boolean = true,
    ): VisiblePlanetsResponse
}

data class VisiblePlanetsResponse(
    @Json(name = "data") val data: List<VisiblePlanetBody> = emptyList(),
)

data class VisiblePlanetBody(
    @Json(name = "name") val name: String = "",
    @Json(name = "constellation") val constellation: String? = null,
    @Json(name = "altitude") val altitude: Double = 0.0,
    @Json(name = "azimuth") val azimuth: Double = 0.0,
    @Json(name = "magnitude") val magnitude: Double? = null,
    @Json(name = "nakedEyeObject") val nakedEyeObject: Boolean? = null,
)
