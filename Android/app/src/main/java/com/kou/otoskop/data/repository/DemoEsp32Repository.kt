package com.kou.otoskop.data.repository

import android.util.Base64
import com.kou.otoskop.core.Resource
import com.kou.otoskop.core.AppConfig
import com.kou.otoskop.data.model.EspGps
import com.kou.otoskop.data.model.TelescopeStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/**
 * ESP32 olmadan tüm ekranların akışını test etmek için sahte durum üretir.
 * Servo/azimuth davranışı basit bir simülasyon; gerçek firmware ile birebir değildir.
 */
class DemoEsp32Repository : Esp32Repository {

    private val mutex = Mutex()

    /** Gösterim için sabit adres (gerçek bağlantı yok). */
    private var fakeHost: String = "demo.local"

    override val streamUrl: String get() = "http://127.0.0.1:9/demo-no-stream"
    override val host: String get() = fakeHost
    override val port: Int get() = 80
    override val supportsLiveStream: Boolean get() = false

    override fun setEndpoint(host: String, port: Int?) {
        fakeHost = host.ifBlank { "demo.local" }
    }

    private var azimuth = 120.0
    private var altitude = 35.0
    private var servoAz = 120.0
    private var servoAlt = 35.0
    private var targetAz = 120.0
    private var targetAlt = 35.0
    private var tracking = false
    private var locked = true

    private val toleranceDeg = 1.5
    private val approachStepDeg = 1.2

    companion object {
        /** Minimal 1×1 JPEG (Base64), snapshot doğrulama zinciri için yeterli. */
        private val DEMO_JPEG_BYTES: ByteArray by lazy {
            Base64.decode(
                "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQH/2wBDAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQH/wAARCAABAAEDAREAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAX/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFQEBAQAAAAAAAAAAAAAAAAAAAAX/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIRAxEAPwCwAA8A/9k=",
                Base64.DEFAULT,
            )
        }

        fun stepDegrees(step: MoveStep): Double = when (step) {
            MoveStep.SMALL -> 0.8
            MoveStep.MEDIUM -> 2.5
            MoveStep.LARGE -> 6.0
        }
    }

    override suspend fun status(): Resource<TelescopeStatus> = mutex.withLock {
        stepTowardTargetIfNeeded()
        Resource.Success(
            TelescopeStatus(
                azimuth = azimuth,
                altitude = altitude,
                targetAzimuth = targetAz,
                targetAltitude = targetAlt,
                servoAz = servoAz,
                servoAlt = servoAlt,
                gpsFix = true,
                imuOk = true,
                tracking = tracking,
                targetLocked = locked,
            ),
        )
    }

    override suspend fun gps(): Resource<EspGps> = Resource.Success(
        EspGps(
            lat = AppConfig.DEMO_FALLBACK_LATITUDE,
            lon = AppConfig.DEMO_FALLBACK_LONGITUDE,
            fix = true,
        ),
    )

    override suspend fun snapshot(): Resource<ByteArray> =
        Resource.Success(DEMO_JPEG_BYTES.clone())

    override suspend fun sendTarget(@Suppress("UNUSED_PARAMETER") name: String, azimuth: Double, altitude: Double): Resource<Unit> =
        mutex.withLock {
            targetAz = azimuth.coerceIn(0.0, 360.0)
            targetAlt = altitude.coerceIn(0.0, 90.0)
            locked = false
            Resource.Success(Unit)
        }

    override suspend fun sendCorrection(
        azimuthCorrection: Double,
        altitudeCorrection: Double,
    ): Resource<Unit> = mutex.withLock {
        azimuth = (azimuth + azimuthCorrection).coerceIn(0.0, 360.0)
        altitude = (altitude + altitudeCorrection).coerceIn(0.0, 90.0)
        servoAz = azimuth
        servoAlt = altitude
        Resource.Success(Unit)
    }

    override suspend fun move(direction: MoveDirection, step: MoveStep): Resource<Unit> =
        mutex.withLock {
            val d = stepDegrees(step)
            when (direction) {
                MoveDirection.LEFT -> azimuth = (azimuth - d).coerceAtLeast(0.0)
                MoveDirection.RIGHT -> azimuth = (azimuth + d).coerceAtMost(360.0)
                MoveDirection.UP -> altitude = (altitude + d).coerceAtMost(90.0)
                MoveDirection.DOWN -> altitude = (altitude - d).coerceAtLeast(0.0)
            }
            servoAz = azimuth
            servoAlt = altitude
            locked = abs(azimuth - targetAz) < toleranceDeg &&
                abs(altitude - targetAlt) < toleranceDeg
            Resource.Success(Unit)
        }

    override suspend fun calibrate(): Resource<Unit> = Resource.Success(Unit)

    override suspend fun setTracking(enabled: Boolean): Resource<Unit> =
        mutex.withLock {
            tracking = enabled
            Resource.Success(Unit)
        }

    private fun stepTowardTargetIfNeeded() {
        if (locked) return
        val dAz = shortestAzimuthDelta(azimuth, targetAz)
        val dAlt = targetAlt - altitude
        if (abs(dAz) < toleranceDeg && abs(dAlt) < toleranceDeg) {
            azimuth = normalizeAzimuth(targetAz)
            altitude = targetAlt
            servoAz = azimuth
            servoAlt = altitude
            locked = true
            return
        }
        if (abs(dAz) >= toleranceDeg) {
            val step = approachStepDeg.coerceAtMost(abs(dAz))
            azimuth = normalizeAzimuth(azimuth + kotlin.math.sign(dAz) * step)
        }
        if (abs(dAlt) >= toleranceDeg) {
            val step = approachStepDeg.coerceAtMost(abs(dAlt))
            altitude += kotlin.math.sign(dAlt) * step
            altitude = altitude.coerceIn(0.0, 90.0)
        }
        servoAz = azimuth
        servoAlt = altitude
    }

    private fun normalizeAzimuth(a: Double): Double {
        var v = a % 360.0
        if (v < 0) v += 360.0
        return v
    }

    /** En kısa yönde azimuth farkı (-180..180). */
    private fun shortestAzimuthDelta(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }
}
