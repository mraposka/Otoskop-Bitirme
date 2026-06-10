package com.kou.otoskop.data.repository

import com.kou.otoskop.core.AppError
import com.kou.otoskop.core.AppErrorKind
import com.kou.otoskop.core.Resource
import com.kou.otoskop.data.model.CelestialObject
import com.kou.otoskop.data.model.SkyArea
import com.kou.otoskop.data.model.VerifyResult
import com.kou.otoskop.data.network.BackendApi
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

class HttpBackendRepository(private val api: BackendApi) : BackendRepository {

    override suspend fun observableObjects(
        latitude: Double,
        longitude: Double,
        datetime: Instant,
        area: SkyArea,
    ): Resource<List<CelestialObject>> = try {
        Resource.Success(
            api.observableObjects(
                latitude = latitude,
                longitude = longitude,
                datetimeIso = datetime.toString(),
                azimuthMin = area.azimuthMin,
                azimuthMax = area.azimuthMax,
                altitudeMin = area.altitudeMin,
                altitudeMax = area.altitudeMax,
            )
        )
    } catch (t: Throwable) {
        Resource.Failure(
            AppError(AppErrorKind.BACKEND_UNREACHABLE, "Backend cevap vermiyor", t)
        )
    }

    override suspend fun verifyImage(
        targetName: String,
        latitude: Double,
        longitude: Double,
        azimuth: Double,
        altitude: Double,
        imageBytes: ByteArray,
    ): Resource<VerifyResult> = try {
        val text = "text/plain".toMediaTypeOrNull()
        val jpeg = "image/jpeg".toMediaTypeOrNull()
        val result = api.verifyImage(
            targetName = targetName.toRequestBody(text),
            latitude = latitude.toString().toRequestBody(text),
            longitude = longitude.toString().toRequestBody(text),
            azimuth = azimuth.toString().toRequestBody(text),
            altitude = altitude.toString().toRequestBody(text),
            image = MultipartBody.Part.createFormData(
                name = "image",
                filename = "snapshot.jpg",
                body = imageBytes.toRequestBody(jpeg),
            ),
        )
        if (!result.verified && !result.needsCorrection) {
            Resource.Failure(
                AppError(
                    AppErrorKind.TARGET_NOT_VERIFIED,
                    result.message.ifEmpty { "Hedef doğrulanamadı" },
                )
            )
        } else {
            Resource.Success(result)
        }
    } catch (t: Throwable) {
        Resource.Failure(
            AppError(
                AppErrorKind.BACKEND_UNREACHABLE,
                "Backend görüntü doğrulama hatası",
                t,
            )
        )
    }
}
