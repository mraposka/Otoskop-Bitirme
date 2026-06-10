package com.kou.otoskop.data.repository

import com.kou.otoskop.core.Resource
import com.kou.otoskop.data.model.EspGps
import com.kou.otoskop.data.model.TelescopeStatus

enum class MoveStep(val wire: String) {
    SMALL("small"),
    MEDIUM("medium"),
    LARGE("large"),
}

enum class MoveDirection(val wire: String) {
    LEFT("left"),
    RIGHT("right"),
    UP("up"),
    DOWN("down"),
}

/**
 * ESP32 ile iletişim sözleşmesi. Üretimde [HttpEsp32Repository],
 * ESP bağlı değilken UI testleri için [DemoEsp32Repository].
 */
interface Esp32Repository {
    val streamUrl: String
    val host: String
    val port: Int

    /** [port] null ise mevcut port korunur (HTTP repo için). */
    fun setEndpoint(host: String, port: Int? = null)

    suspend fun status(): Resource<TelescopeStatus>

    /**
     * Teleskop üzerindeki GPS konumu. ESP GPS modülünde kilit yoksa
     * [EspGps.hasFix] false döner; çağıran taraf telefon GPS'ine düşer.
     */
    suspend fun gps(): Resource<EspGps>

    suspend fun snapshot(): Resource<ByteArray>

    suspend fun sendTarget(name: String, azimuth: Double, altitude: Double): Resource<Unit>

    suspend fun sendCorrection(azimuthCorrection: Double, altitudeCorrection: Double): Resource<Unit>

    suspend fun move(direction: MoveDirection, step: MoveStep): Resource<Unit>

    suspend fun calibrate(): Resource<Unit>

    suspend fun setTracking(enabled: Boolean): Resource<Unit>

    /** Demo modunda gerçek MJPEG yok; UI placeholder gösterir. */
    val supportsLiveStream: Boolean
}
