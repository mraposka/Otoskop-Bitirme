package com.kou.otoskop.data.capture

import org.json.JSONObject

/**
 * Telefonda yerel olarak saklanan bir gözlem kaydı (foto/video) ve meta
 * verisi. Web sitesindeki `media` şemasıyla birebir hizalı tutuldu; ileride
 * olduğu gibi yüklenebilir.
 */
data class CaptureItem(
    val id: String,
    val type: String,            // "photo" | "video"
    val fileName: String,        // captures klasörüne göreceli
    val targetName: String?,
    val objectType: String?,
    val azimuth: Double?,
    val altitude: Double?,
    val gpsLat: Double?,
    val gpsLon: Double?,
    val magnitude: Double?,
    val aiVerified: Boolean,
    val aiConfidence: Double?,
    val aiMessage: String?,
    val fps: Double?,
    val durationSec: Double?,
    val fileSize: Long,
    val capturedAt: Long,        // epoch millis
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type)
        put("fileName", fileName)
        put("targetName", targetName ?: JSONObject.NULL)
        put("objectType", objectType ?: JSONObject.NULL)
        put("azimuth", azimuth ?: JSONObject.NULL)
        put("altitude", altitude ?: JSONObject.NULL)
        put("gpsLat", gpsLat ?: JSONObject.NULL)
        put("gpsLon", gpsLon ?: JSONObject.NULL)
        put("magnitude", magnitude ?: JSONObject.NULL)
        put("aiVerified", aiVerified)
        put("aiConfidence", aiConfidence ?: JSONObject.NULL)
        put("aiMessage", aiMessage ?: JSONObject.NULL)
        put("fps", fps ?: JSONObject.NULL)
        put("durationSec", durationSec ?: JSONObject.NULL)
        put("fileSize", fileSize)
        put("capturedAt", capturedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): CaptureItem = CaptureItem(
            id = o.getString("id"),
            type = o.getString("type"),
            fileName = o.getString("fileName"),
            targetName = o.optStringOrNull("targetName"),
            objectType = o.optStringOrNull("objectType"),
            azimuth = o.optDoubleOrNull("azimuth"),
            altitude = o.optDoubleOrNull("altitude"),
            gpsLat = o.optDoubleOrNull("gpsLat"),
            gpsLon = o.optDoubleOrNull("gpsLon"),
            magnitude = o.optDoubleOrNull("magnitude"),
            aiVerified = o.optBoolean("aiVerified", false),
            aiConfidence = o.optDoubleOrNull("aiConfidence"),
            aiMessage = o.optStringOrNull("aiMessage"),
            fps = o.optDoubleOrNull("fps"),
            durationSec = o.optDoubleOrNull("durationSec"),
            fileSize = o.optLong("fileSize", 0L),
            capturedAt = o.optLong("capturedAt", System.currentTimeMillis()),
        )

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key) || !has(key)) null else optString(key).ifEmpty { null }

        private fun JSONObject.optDoubleOrNull(key: String): Double? =
            if (isNull(key) || !has(key)) null else optDouble(key).let { if (it.isNaN()) null else it }
    }
}
