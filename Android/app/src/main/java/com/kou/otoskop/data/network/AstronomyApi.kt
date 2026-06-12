package com.kou.otoskop.data.network

import com.squareup.moshi.Json
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * [Astronomy API](https://docs.astronomyapi.com/) v2 — ücretsiz tier, Application ID + Secret
 * ile Basic Auth. Gözlemlenebilir cisim konumları (alt/az) için `bodies/positions`.
 */
interface AstronomyApi {

    @GET("api/v2/bodies/positions")
    suspend fun bodyPositions(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("elevation") elevation: Int = 0,
        @Query("from_date") fromDate: String,
        @Query("to_date") toDate: String,
        @Query("time") time: String,
        /** `rows` = düz dizi; tablo yerine doğrudan body listesi. */
        @Query("output") output: String = "rows",
    ): AstronomyPositionsResponse
}

data class AstronomyPositionsResponse(
    @Json(name = "data") val data: AstronomyPositionsData? = null,
)

data class AstronomyPositionsData(
    @Json(name = "rows") val rows: List<AstronomyBodyRow> = emptyList(),
)

data class AstronomyBodyRow(
    @Json(name = "id") val id: String = "",
    @Json(name = "name") val name: String = "",
    @Json(name = "position") val position: AstronomyPosition? = null,
    @Json(name = "extraInfo") val extraInfo: AstronomyExtraInfo? = null,
)

data class AstronomyPosition(
    /** Dokümantasyonda sık yazım: "horizonal" (typo). */
    @Json(name = "horizonal") val horizonal: AstronomyAltAz? = null,
    @Json(name = "horizontal") val horizontal: AstronomyAltAz? = null,
) {
    val altAz: AstronomyAltAz? get() = horizonal ?: horizontal
}

data class AstronomyAltAz(
    @Json(name = "altitude") val altitude: AstronomyDegrees? = null,
    @Json(name = "azimuth") val azimuth: AstronomyDegrees? = null,
)

data class AstronomyDegrees(
    @Json(name = "degrees") val degrees: String? = null,
)

data class AstronomyExtraInfo(
    @Json(name = "magnitude") val magnitude: String? = null,
)
