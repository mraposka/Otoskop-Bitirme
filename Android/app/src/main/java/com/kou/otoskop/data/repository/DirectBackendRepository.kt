package com.kou.otoskop.data.repository

import android.util.Base64
import com.kou.otoskop.core.AppError
import com.kou.otoskop.core.AppErrorKind
import com.kou.otoskop.core.AstroMath
import com.kou.otoskop.core.DebugLog
import com.kou.otoskop.core.Ephemeris
import com.kou.otoskop.core.Resource
import com.kou.otoskop.data.model.CelestialObject
import com.kou.otoskop.data.model.SkyArea
import com.kou.otoskop.data.model.VerifyResult
import com.kou.otoskop.data.network.AstroApi
import com.kou.otoskop.data.network.AstronomyApi
import com.kou.otoskop.data.network.GeminiApi
import com.kou.otoskop.data.network.GeminiContent
import com.kou.otoskop.data.network.GeminiGenConfig
import com.kou.otoskop.data.network.GeminiInlineData
import com.kou.otoskop.data.network.GeminiPart
import com.kou.otoskop.data.network.GeminiRequest
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Backend sunucu yerine doğrudan üçüncü-parti servisleri kullanır:
 *  - Gözlemlenebilir objeler (sırayla):
 *      1. Visible Planets API (keysiz, ücretsiz)
 *      2. Astronomy API (Application ID + Secret, Bağlantı ekranı)
 *      3. Yerel [Ephemeris] (internet yok / hepsi düştü)
 *  - Görüntü doğrulama: Google Gemini (multimodal), app'ten direkt çağrı.
 *
 * [BackendRepository] arayüzünü uygular; ViewModel'ler değişmeden çalışır.
 */
class DirectBackendRepository(
    private val astro: AstroApi,
    private val astronomy: AstronomyApi,
    private val astronomyConfigured: () -> Boolean,
    private val gemini: GeminiApi,
    private val model: String,
    private val apiKeyProvider: () -> String,
) : BackendRepository {

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    /**
     * Gözlemlenebilir objeler: Visible Planets → Astronomy API → yerel hesap.
     */
    override suspend fun observableObjects(
        latitude: Double,
        longitude: Double,
        datetime: Instant,
        area: SkyArea,
    ): Resource<List<CelestialObject>> {
        val all = tryVisiblePlanets(latitude, longitude, datetime)
            ?: tryAstronomyApi(latitude, longitude, datetime)
            ?: run {
                DebugLog.add("Tüm API'ler atlandı/başarısız -> yerel hesap")
                localObjects(latitude, longitude, datetime)
            }

        val inArea = all
            .filter {
                AstroMath.inWindow(
                    azimuth = it.azimuth,
                    altitude = it.altitude,
                    azMin = area.azimuthMin,
                    azMax = area.azimuthMax,
                    altMin = area.altitudeMin,
                    altMax = area.altitudeMax,
                )
            }
            .sortedBy { it.magnitude }
        return Resource.Success(inArea)
    }

    /** 1) Visible Planets (keysiz). */
    private suspend fun tryVisiblePlanets(
        latitude: Double,
        longitude: Double,
        datetime: Instant,
    ): List<CelestialObject>? = try {
        val resp = astro.visibleBodies(
            latitude = latitude,
            longitude = longitude,
            timeIso = datetime.toString(),
            aboveHorizon = true,
        )
        val mapped = resp.data.map { mapVisiblePlanet(it) }
        when {
            mapped.isEmpty() -> {
                DebugLog.add("Visible Planets boş -> Astronomy/yerel")
                null
            }
            else -> {
                DebugLog.add("Visible Planets OK: ${mapped.size} cisim")
                mapped
            }
        }
    } catch (t: Throwable) {
        DebugLog.add("Visible Planets hata (${t.message}) -> Astronomy/yerel")
        null
    }

    /** 2) Astronomy API (kimlik bilgisi Bağlantı ekranında). */
    private suspend fun tryAstronomyApi(
        latitude: Double,
        longitude: Double,
        datetime: Instant,
    ): List<CelestialObject>? {
        if (!astronomyConfigured()) {
            DebugLog.add("Astronomy API kimlik bilgisi yok (Bağlantı ekranı)")
            return null
        }
        return try {
            val utc = datetime.atZone(ZoneOffset.UTC)
            val date = utc.toLocalDate().toString()
            val time = utc.toLocalTime().format(timeFmt)
            val resp = astronomy.bodyPositions(
                latitude = latitude,
                longitude = longitude,
                elevation = 0,
                fromDate = date,
                toDate = date,
                time = time,
            )
            val mapped = resp.data?.rows
                ?.mapNotNull { mapAstronomyRow(it) }
                ?.filter { it.altitude > 0.0 }
                ?: emptyList()
            when {
                mapped.isEmpty() -> {
                    DebugLog.add("Astronomy API boş -> yerel hesap")
                    null
                }
                else -> {
                    DebugLog.add("Astronomy API OK: ${mapped.size} cisim")
                    mapped
                }
            }
        } catch (t: Throwable) {
            DebugLog.add("Astronomy API hata (${t.message}) -> yerel hesap")
            null
        }
    }

    private fun mapVisiblePlanet(it: com.kou.otoskop.data.network.VisiblePlanetBody) =
        CelestialObject(
            name = it.name,
            type = typeOf(it.name),
            azimuth = it.azimuth,
            altitude = it.altitude,
            magnitude = it.magnitude ?: 99.0,
            visible = it.altitude > 0.0,
        )

    private fun mapAstronomyRow(row: com.kou.otoskop.data.network.AstronomyBodyRow): CelestialObject? {
        val altAz = row.position?.altAz ?: return null
        val alt = altAz.altitude?.degrees?.toDoubleOrNull() ?: return null
        val az = altAz.azimuth?.degrees?.toDoubleOrNull() ?: return null
        val mag = row.extraInfo?.magnitude?.toDoubleOrNull() ?: 99.0
        return CelestialObject(
            name = row.name.ifBlank { row.id },
            type = typeOf(row.name.ifBlank { row.id }),
            azimuth = AstroMath.normalizeAzimuth(az),
            altitude = alt,
            magnitude = mag,
            visible = alt > 0.0,
        )
    }

    /** Tamamen çevrimdışı: cihaz içinde Güneş/Ay/gezegen konumlarını hesaplar. */
    private fun localObjects(
        latitude: Double,
        longitude: Double,
        datetime: Instant,
    ): List<CelestialObject> = Ephemeris.visibleBodies(
        latitude = latitude,
        longitude = longitude,
        timeUtc = datetime,
        aboveHorizonOnly = true,
    ).map {
        CelestialObject(
            name = it.name,
            type = it.type,
            azimuth = it.azimuth,
            altitude = it.altitude,
            magnitude = it.magnitude,
            visible = it.altitude > 0.0,
        )
    }

    override suspend fun verifyImage(
        targetName: String,
        latitude: Double,
        longitude: Double,
        azimuth: Double,
        altitude: Double,
        imageBytes: ByteArray,
    ): Resource<VerifyResult> {
        val key = apiKeyProvider().trim()
        if (key.isEmpty()) {
            return Resource.Failure(
                AppError(
                    AppErrorKind.TARGET_NOT_VERIFIED,
                    "Gemini API anahtarı girilmedi (Bağlantı ekranı)",
                )
            )
        }
        return try {
            val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            val request = GeminiRequest(
                contents = listOf(
                    GeminiContent(
                        parts = listOf(
                            GeminiPart(text = buildPrompt(targetName, azimuth, altitude)),
                            GeminiPart(
                                inlineData = GeminiInlineData(
                                    mimeType = "image/jpeg",
                                    data = base64,
                                )
                            ),
                        )
                    )
                ),
                generationConfig = GeminiGenConfig(),
            )
            val resp = gemini.generate(model = model, apiKey = key, body = request)
            val text = resp.candidates.firstOrNull()
                ?.content?.parts?.firstOrNull()?.text
                ?: return Resource.Failure(
                    AppError(AppErrorKind.TARGET_NOT_VERIFIED, "Gemini boş cevap döndü")
                )
            parseVerify(text, targetName)
        } catch (t: Throwable) {
            Resource.Failure(
                AppError(AppErrorKind.BACKEND_UNREACHABLE, "Gemini doğrulama hatası", t)
            )
        }
    }

    private fun parseVerify(raw: String, targetName: String): Resource<VerifyResult> = try {
        val json = JSONObject(stripFences(raw))
        val result = VerifyResult(
            verified = json.optBoolean("verified", false),
            targetName = targetName,
            azimuthCorrection = json.optDouble("azimuthCorrection", 0.0),
            altitudeCorrection = json.optDouble("altitudeCorrection", 0.0),
            message = json.optString("message", ""),
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
            AppError(AppErrorKind.TARGET_NOT_VERIFIED, "Gemini cevabı çözümlenemedi", t)
        )
    }

    private fun buildPrompt(targetName: String, azimuth: Double, altitude: Double): String = """
        Sen bir teleskop hedef-doğrulama asistanısın. Aşağıdaki görüntü bir teleskoba
        bağlı kameradan alındı. Hedef gök cismi: "$targetName"
        (yaklaşık azimut $azimuth°, yükseklik $altitude°).

        Görüntüyü incele ve SADECE şu JSON'u döndür (başka metin yok):
        {
          "verified": <true|false>,
          "azimuthCorrection": <derece, sola için negatif sağa için pozitif, -3..3>,
          "altitudeCorrection": <derece, aşağı için negatif yukarı için pozitif, -3..3>,
          "message": "<kısa Türkçe açıklama>"
        }

        Kurallar:
        - Hedef cisim görüntüde belirgin ve makul ölçüde merkezdeyse: verified=true,
          düzeltmeler 0.
        - Cisim görünüyor ama merkezde değilse: verified=false ve cismi merkeze almak
          için küçük düzeltmeler ver (kadranda sağdaysa azimuthCorrection pozitif,
          yukarıdaysa altitudeCorrection pozitif).
        - Cisim hiç görünmüyor / yanlış cisimse: verified=false, düzeltmeler 0,
          message ile açıkla.
    """.trimIndent()

    private fun stripFences(s: String): String {
        val t = s.trim()
        if (t.startsWith("```")) {
            return t.removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
        }
        return t
    }

    private fun typeOf(name: String): String = when (name.lowercase()) {
        "moon", "ay" -> "moon"
        "sun", "güneş", "gunes" -> "sun"
        else -> "planet"
    }
}
