package com.kou.otoskop.data.repository

import com.kou.otoskop.core.Resource
import com.kou.otoskop.data.model.CelestialObject
import com.kou.otoskop.data.model.SkyArea
import com.kou.otoskop.data.model.VerifyResult
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * Backend olmadan nesne listesi ve doğrulama akışını test etmek için.
 */
class DemoBackendRepository : BackendRepository {

    override suspend fun observableObjects(
        latitude: Double,
        longitude: Double,
        datetime: Instant,
        area: SkyArea,
    ): Resource<List<CelestialObject>> {
        delay(400)
        val centerAz = area.centerAzimuth
        val centerAlt = area.centerAltitude
        val fake = listOf(
            CelestialObject(
                name = "Mars",
                type = "planet",
                azimuth = centerAz + 5.0,
                altitude = centerAlt.coerceIn(15.0, 75.0),
                magnitude = -1.2,
                visible = true,
            ),
            CelestialObject(
                name = "Jupiter",
                type = "planet",
                azimuth = centerAz - 8.0,
                altitude = (centerAlt + 10).coerceIn(10.0, 85.0),
                magnitude = -2.2,
                visible = true,
            ),
            CelestialObject(
                name = "Arcturus",
                type = "star",
                azimuth = centerAz + 12.0,
                altitude = (centerAlt - 5).coerceIn(10.0, 85.0),
                magnitude = 0.15,
                visible = true,
            ),
            CelestialObject(
                name = "Örnek DSO",
                type = "dso",
                azimuth = centerAz - 3.0,
                altitude = centerAlt.coerceIn(12.0, 78.0),
                magnitude = 8.5,
                visible = false,
            ),
        )
        return Resource.Success(fake)
    }

    override suspend fun verifyImage(
        targetName: String,
        latitude: Double,
        longitude: Double,
        azimuth: Double,
        altitude: Double,
        imageBytes: ByteArray,
    ): Resource<VerifyResult> {
        delay(600)
        return Resource.Success(
            VerifyResult(
                verified = true,
                targetName = targetName,
                azimuthCorrection = 0.0,
                altitudeCorrection = 0.0,
                message = "Demo: hedef doğrulandı",
            ),
        )
    }
}
