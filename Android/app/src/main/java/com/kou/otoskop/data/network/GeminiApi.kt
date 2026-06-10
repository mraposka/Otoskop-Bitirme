package com.kou.otoskop.data.network

import com.squareup.moshi.Json
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Google Gemini generateContent (multimodal) REST API.
 * En ucuz vizyon modeli kullanılır (bkz. AppConfig.GEMINI_MODEL).
 * API key header ile gönderilir: x-goog-api-key.
 */
interface GeminiApi {

    @POST("v1beta/models/{model}:generateContent")
    suspend fun generate(
        @Path("model") model: String,
        @Header("x-goog-api-key") apiKey: String,
        @Body body: GeminiRequest,
    ): GeminiResponse
}

data class GeminiRequest(
    @Json(name = "contents") val contents: List<GeminiContent>,
    @Json(name = "generationConfig") val generationConfig: GeminiGenConfig? = null,
)

data class GeminiContent(
    @Json(name = "parts") val parts: List<GeminiPart>,
)

data class GeminiPart(
    @Json(name = "text") val text: String? = null,
    @Json(name = "inline_data") val inlineData: GeminiInlineData? = null,
)

data class GeminiInlineData(
    @Json(name = "mime_type") val mimeType: String,
    @Json(name = "data") val data: String,
)

data class GeminiGenConfig(
    @Json(name = "response_mime_type") val responseMimeType: String = "application/json",
    @Json(name = "temperature") val temperature: Double = 0.1,
)

data class GeminiResponse(
    @Json(name = "candidates") val candidates: List<GeminiCandidate> = emptyList(),
)

data class GeminiCandidate(
    @Json(name = "content") val content: GeminiContentOut? = null,
)

data class GeminiContentOut(
    @Json(name = "parts") val parts: List<GeminiPartOut> = emptyList(),
)

data class GeminiPartOut(
    @Json(name = "text") val text: String? = null,
)
