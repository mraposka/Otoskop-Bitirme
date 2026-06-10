package com.kou.otoskop.data.capture

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Çekilen foto/videoları telefonun uygulamaya özel dış depolama alanında
 * (`Android/data/<pkg>/files/captures`) saklar; meta verileri `index.json`
 * dosyasında tutar. Room/DB gerektirmez, sahada dayanıklıdır.
 */
class CaptureRepository(context: Context) {

    private val dir: File =
        File(context.applicationContext.getExternalFilesDir(null), "captures").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val mutex = Mutex()

    private val _items = MutableStateFlow<List<CaptureItem>>(emptyList())
    val items: StateFlow<List<CaptureItem>> = _items

    init {
        _items.value = loadIndex()
    }

    fun fileOf(item: CaptureItem): File = File(dir, item.fileName)

    /** Video kaydedicinin içine yazacağı yeni dosya (henüz index'e eklenmez). */
    fun newVideoFile(): Pair<String, File> {
        val name = "vid_${stamp()}_${shortId()}.mp4"
        return name to File(dir, name)
    }

    suspend fun addPhoto(jpeg: ByteArray, meta: CaptureMeta): CaptureItem =
        withContext(Dispatchers.IO) {
            val name = "img_${stamp()}_${shortId()}.jpg"
            File(dir, name).writeBytes(jpeg)
            val item = meta.toItem(type = "photo", fileName = name, fileSize = jpeg.size.toLong())
            append(item)
            item
        }

    /** Kaydedici dosyayı yazdıktan sonra index'e ekler. */
    suspend fun addVideo(fileName: String, meta: CaptureMeta): CaptureItem =
        withContext(Dispatchers.IO) {
            val f = File(dir, fileName)
            val item = meta.toItem(
                type = "video",
                fileName = fileName,
                fileSize = if (f.exists()) f.length() else 0L,
            )
            append(item)
            item
        }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val item = _items.value.firstOrNull { it.id == id } ?: return@withLock
            runCatching { File(dir, item.fileName).delete() }
            _items.value = _items.value.filterNot { it.id == id }
            persist(_items.value)
        }
    }

    private suspend fun append(item: CaptureItem) = mutex.withLock {
        val updated = listOf(item) + _items.value
        _items.value = updated
        persist(updated)
    }

    private fun loadIndex(): List<CaptureItem> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length())
                .map { CaptureItem.fromJson(arr.getJSONObject(it)) }
                .filter { File(dir, it.fileName).exists() }
                .sortedByDescending { it.capturedAt }
        }.getOrDefault(emptyList())
    }

    private fun persist(list: List<CaptureItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        runCatching { indexFile.writeText(arr.toString()) }
    }

    private fun stamp(): String =
        java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())

    private fun shortId(): String = UUID.randomUUID().toString().take(6)
}

/** Bir kayda iliştirilecek meta veri (foto/video ortak). */
data class CaptureMeta(
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
    val fps: Double? = null,
    val durationSec: Double? = null,
    val capturedAt: Long = System.currentTimeMillis(),
) {
    fun toItem(type: String, fileName: String, fileSize: Long): CaptureItem = CaptureItem(
        id = UUID.randomUUID().toString(),
        type = type,
        fileName = fileName,
        targetName = targetName,
        objectType = objectType,
        azimuth = azimuth,
        altitude = altitude,
        gpsLat = gpsLat,
        gpsLon = gpsLon,
        magnitude = magnitude,
        aiVerified = aiVerified,
        aiConfidence = aiConfidence,
        aiMessage = aiMessage,
        fps = fps,
        durationSec = durationSec,
        fileSize = fileSize,
        capturedAt = capturedAt,
    )
}
