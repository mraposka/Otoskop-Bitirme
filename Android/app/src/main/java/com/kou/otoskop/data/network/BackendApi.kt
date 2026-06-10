package com.kou.otoskop.data.network

import com.kou.otoskop.data.model.CelestialObject
import com.kou.otoskop.data.model.VerifyResult
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

/** Backend (gözlemlenebilir obje listesi + AI plate-solving) HTTP API'si. */
interface BackendApi {

    @GET("observable-objects")
    suspend fun observableObjects(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("datetime") datetimeIso: String,
        @Query("azimuthMin") azimuthMin: Double,
        @Query("azimuthMax") azimuthMax: Double,
        @Query("altitudeMin") altitudeMin: Double,
        @Query("altitudeMax") altitudeMax: Double,
    ): List<CelestialObject>

    @Multipart
    @POST("verify-image")
    suspend fun verifyImage(
        @Part("targetName") targetName: RequestBody,
        @Part("latitude") latitude: RequestBody,
        @Part("longitude") longitude: RequestBody,
        @Part("azimuth") azimuth: RequestBody,
        @Part("altitude") altitude: RequestBody,
        @Part image: MultipartBody.Part,
    ): VerifyResult
}
