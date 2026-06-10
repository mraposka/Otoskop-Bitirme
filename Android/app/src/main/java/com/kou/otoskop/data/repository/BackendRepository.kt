package com.kou.otoskop.data.repository

import com.kou.otoskop.core.Resource
import com.kou.otoskop.data.model.CelestialObject
import com.kou.otoskop.data.model.SkyArea
import com.kou.otoskop.data.model.VerifyResult
import java.time.Instant

interface BackendRepository {
    suspend fun observableObjects(
        latitude: Double,
        longitude: Double,
        datetime: Instant,
        area: SkyArea,
    ): Resource<List<CelestialObject>>

    suspend fun verifyImage(
        targetName: String,
        latitude: Double,
        longitude: Double,
        azimuth: Double,
        altitude: Double,
        imageBytes: ByteArray,
    ): Resource<VerifyResult>
}
