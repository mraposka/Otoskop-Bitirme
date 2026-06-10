package com.kou.otoskop.ui.shared

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kou.otoskop.OtoskopApp
import com.kou.otoskop.core.DebugLog
import com.kou.otoskop.core.Resource
import com.kou.otoskop.data.capture.CaptureItem
import com.kou.otoskop.data.capture.CaptureMeta
import com.kou.otoskop.data.capture.Mp4Recorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Çekim anındaki bağlam (telemetri + konum) — foto/video meta verisi için. */
data class CaptureContext(
    val targetName: String?,
    val objectType: String?,
    val azimuth: Double?,
    val altitude: Double?,
    val gpsLat: Double?,
    val gpsLon: Double?,
    val magnitude: Double?,
)

data class CaptureUiState(
    val recording: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
)

/** AI'nın kadraj değerlendirmesi. */
private data class VerifyOutcome(
    val present: Boolean,    // kadrajda hedef görünüyor mu (merkezde olmasa da)
    val centered: Boolean,   // merkezde mi (verified)
    val message: String?,
)

class CaptureViewModel(application: Application) : ViewModel() {

    private val app = application as OtoskopApp

    val items: StateFlow<List<CaptureItem>> get() = app.captureRepo.items

    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state

    private var recorder: Mp4Recorder? = null
    private var pendingVideoFileName: String? = null
    private var pendingVideoMeta: CaptureMeta? = null

    /** MjpegView'dan gelen her kare; kayıt aktifse kodlayıcıya iletilir. */
    fun onStreamFrame(bitmap: Bitmap) {
        recorder?.encodeFrame(bitmap)
    }

    /** Fotoğraf çek: ESP'den tam kare al, AI ile kadrajı değerlendir, kaydet. */
    fun capturePhoto(ctx: CaptureContext) {
        if (_state.value.busy || _state.value.recording) return
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch {
            DebugLog.add("FOTO: ESP'den kare isteniyor")
            when (val snap = app.esp32Repo.snapshot()) {
                is Resource.Failure -> {
                    DebugLog.add("FOTO HATA: ${snap.error.message}")
                    _state.value = _state.value.copy(busy = false, message = "Foto alınamadı")
                }
                is Resource.Success -> {
                    val bytes = snap.value
                    val v = verifyFrame(ctx, bytes)
                    val meta = ctx.toMeta(v)
                    app.captureRepo.addPhoto(bytes, meta)
                    val tag = if (v == null) "AI yok" else if (v.present) "AI ✓" else "AI ✗"
                    DebugLog.add("FOTO kaydedildi (${bytes.size} byte, $tag)")
                    _state.value = _state.value.copy(busy = false, message = "Fotoğraf kaydedildi ($tag)")
                }
            }
        }
    }

    /** Video kaydı başlat: önce AI ile kadrajda hedef var mı kontrol et. */
    fun startRecording(ctx: CaptureContext, force: Boolean = false) {
        if (_state.value.recording || _state.value.busy) return
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch {
            DebugLog.add("VIDEO: kayıt öncesi AI kadraj kontrolü")
            val v = when (val snap = app.esp32Repo.snapshot()) {
                is Resource.Success -> verifyFrame(ctx, snap.value)
                is Resource.Failure -> null
            }
            val present = v?.present == true
            if (!present && !force) {
                val why = v?.message ?: "AI doğrulayamadı"
                DebugLog.add("VIDEO iptal: kadrajda hedef yok ($why)")
                _state.value = _state.value.copy(
                    busy = false,
                    message = "Kadrajda hedef yok — kayıt başlamadı ($why)",
                )
                return@launch
            }
            val (name, file) = app.captureRepo.newVideoFile()
            pendingVideoFileName = name
            pendingVideoMeta = ctx.toMeta(v)
            recorder = Mp4Recorder(file)
            DebugLog.add("VIDEO kaydı başladı")
            _state.value = _state.value.copy(busy = false, recording = true, message = "Kayıt sürüyor…")
        }
    }

    fun stopRecording() {
        val rec = recorder ?: return
        recorder = null
        _state.value = _state.value.copy(recording = false, busy = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { rec.stop() }
            val name = pendingVideoFileName
            val baseMeta = pendingVideoMeta
            pendingVideoFileName = null
            pendingVideoMeta = null
            if (result.ok && name != null && baseMeta != null) {
                app.captureRepo.addVideo(
                    name,
                    baseMeta.copy(fps = result.fps, durationSec = result.durationSec),
                )
                DebugLog.add("VIDEO kaydedildi (${result.frames} kare, %.1f sn)".format(result.durationSec))
                _state.value = _state.value.copy(busy = false, message = "Video kaydedildi")
            } else {
                DebugLog.add("VIDEO HATA: kayıt boş/başarısız")
                _state.value = _state.value.copy(busy = false, message = "Video kaydı başarısız")
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { app.captureRepo.delete(id) }
    }

    private suspend fun verifyFrame(ctx: CaptureContext, bytes: ByteArray): VerifyOutcome? {
        val target = ctx.targetName ?: return null
        return when (
            val v = app.backendRepo.verifyImage(
                targetName = target,
                latitude = ctx.gpsLat ?: 0.0,
                longitude = ctx.gpsLon ?: 0.0,
                azimuth = ctx.azimuth ?: 0.0,
                altitude = ctx.altitude ?: 0.0,
                imageBytes = bytes,
            )
        ) {
            // Success: hedef kadrajda (merkezde ya da kenarda). Failure: yok/hata.
            is Resource.Success -> VerifyOutcome(true, v.value.verified, v.value.message)
            is Resource.Failure -> VerifyOutcome(false, false, v.error.message)
        }
    }

    private fun CaptureContext.toMeta(v: VerifyOutcome?): CaptureMeta = CaptureMeta(
        targetName = targetName,
        objectType = objectType,
        azimuth = azimuth,
        altitude = altitude,
        gpsLat = gpsLat,
        gpsLon = gpsLon,
        magnitude = magnitude,
        aiVerified = v?.present ?: false,
        aiConfidence = null,
        aiMessage = v?.message,
    )

    override fun onCleared() {
        runCatching { recorder?.stop() }
        recorder = null
        super.onCleared()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CaptureViewModel(application) as T
    }
}
