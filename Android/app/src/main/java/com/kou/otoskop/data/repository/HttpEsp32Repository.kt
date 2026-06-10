package com.kou.otoskop.data.repository

import com.kou.otoskop.core.AppError
import com.kou.otoskop.core.AppErrorKind
import com.kou.otoskop.core.Resource
import com.kou.otoskop.data.model.EspGps
import com.kou.otoskop.data.model.TelescopeStatus
import com.kou.otoskop.data.network.CorrectionBody
import com.kou.otoskop.data.network.Esp32Api
import com.kou.otoskop.data.network.Esp32Endpoint
import com.kou.otoskop.data.network.MoveBody
import com.kou.otoskop.data.network.TargetBody
import com.kou.otoskop.data.network.TrackingBody
import kotlinx.coroutines.CancellationException
import retrofit2.Response

class HttpEsp32Repository(
    private val api: Esp32Api,
    private val endpoint: Esp32Endpoint,
) : Esp32Repository {

    override val streamUrl: String get() = endpoint.streamUrl()
    override val host: String get() = endpoint.host
    override val port: Int get() = endpoint.port
    override val supportsLiveStream: Boolean get() = true

    override fun setEndpoint(host: String, port: Int?) {
        endpoint.set(host.trim(), port ?: endpoint.port)
    }

    override suspend fun status(): Resource<TelescopeStatus> = runEsp32 { api.status() }

    override suspend fun gps(): Resource<EspGps> = runEsp32 { api.gps() }

    override suspend fun snapshot(): Resource<ByteArray> = try {
        val res = api.camera()
        if (!res.isSuccessful) {
            Resource.Failure(
                AppError(
                    AppErrorKind.CAMERA_STREAM_FAILED,
                    "Kamera snapshot alınamadı (HTTP ${res.code()})",
                )
            )
        } else {
            val bytes = res.body()?.bytes()
            if (bytes == null || bytes.isEmpty()) {
                Resource.Failure(
                    AppError(AppErrorKind.CAMERA_STREAM_FAILED, "Boş kamera cevabı")
                )
            } else Resource.Success(bytes)
        }
    } catch (t: CancellationException) {
        throw t
    } catch (t: Throwable) {
        Resource.Failure(
            AppError(AppErrorKind.CAMERA_STREAM_FAILED, "Kamera erişilemiyor", t)
        )
    }

    override suspend fun sendTarget(name: String, azimuth: Double, altitude: Double) =
        runEsp32Empty { api.postTarget(TargetBody(name, azimuth, altitude)) }

    override suspend fun sendCorrection(az: Double, alt: Double) =
        runEsp32Empty { api.postCorrection(CorrectionBody(az, alt)) }

    override suspend fun move(direction: MoveDirection, step: MoveStep) =
        runEsp32Empty { api.postMove(MoveBody(direction.wire, step.wire)) }

    override suspend fun calibrate() = runEsp32Empty { api.postCalibrate() }

    override suspend fun setTracking(enabled: Boolean) =
        runEsp32Empty { api.postTracking(TrackingBody(enabled)) }

    private suspend fun <T> runEsp32(block: suspend () -> T): Resource<T> = try {
        Resource.Success(block())
    } catch (t: CancellationException) {
        // Coroutine iptali (ekran değişimi/stopPolling) bir bağlantı hatası
        // DEĞİL. Yutmazsak sahte "ESP32 ulaşılamıyor" hatası state'e yapışır.
        throw t
    } catch (t: Throwable) {
        Resource.Failure(
            AppError(AppErrorKind.ESP32_UNREACHABLE, "ESP32 cihazına ulaşılamıyor", t)
        )
    }

    private suspend fun runEsp32Empty(
        block: suspend () -> Response<Unit>,
    ): Resource<Unit> = try {
        val res = block()
        if (res.isSuccessful) Resource.Success(Unit) else Resource.Failure(
            AppError(AppErrorKind.ESP32_UNREACHABLE, "ESP32 HTTP ${res.code()}")
        )
    } catch (t: CancellationException) {
        throw t
    } catch (t: Throwable) {
        Resource.Failure(
            AppError(AppErrorKind.ESP32_UNREACHABLE, "ESP32 isteği başarısız", t)
        )
    }
}
