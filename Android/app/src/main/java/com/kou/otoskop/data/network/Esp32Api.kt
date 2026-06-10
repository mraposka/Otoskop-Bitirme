package com.kou.otoskop.data.network

import com.kou.otoskop.data.model.EspGps
import com.kou.otoskop.data.model.TelescopeStatus
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Streaming

/**
 * ESP32 cihazının HTTP API'si. Retrofit base URL'i çalışma anında
 * [Esp32Endpoint] üzerinden değişir; bu yüzden burada path'ler relative.
 */
interface Esp32Api {

    @GET("/status")
    suspend fun status(): TelescopeStatus

    /** Teleskop üzerindeki GPS modülünün konumu (fix yoksa fix=false). */
    @GET("/gps")
    suspend fun gps(): EspGps

    /** Tek kare JPEG (snapshot). */
    @Streaming
    @GET("/camera")
    suspend fun camera(): Response<ResponseBody>

    @POST("/target")
    suspend fun postTarget(@Body body: TargetBody): Response<Unit>

    @POST("/move")
    suspend fun postMove(@Body body: MoveBody): Response<Unit>

    @POST("/correction")
    suspend fun postCorrection(@Body body: CorrectionBody): Response<Unit>

    @POST("/calibrate")
    suspend fun postCalibrate(): Response<Unit>

    @POST("/target")
    suspend fun postTracking(@Body body: TrackingBody): Response<Unit>
}

data class TargetBody(val name: String, val azimuth: Double, val altitude: Double)
data class MoveBody(val direction: String, val step: String)
data class CorrectionBody(val azimuthCorrection: Double, val altitudeCorrection: Double)
data class TrackingBody(val tracking: Boolean)
