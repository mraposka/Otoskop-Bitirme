package com.kou.otoskop.ui.shared

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kou.otoskop.OtoskopApp
import com.kou.otoskop.core.AppConfig
import com.kou.otoskop.core.AppError
import com.kou.otoskop.core.AppErrorKind
import com.kou.otoskop.core.DebugLog
import com.kou.otoskop.core.Resource
import com.kou.otoskop.data.model.CelestialObject
import com.kou.otoskop.data.model.PhoneSensorData
import com.kou.otoskop.data.model.TelescopeStatus
import com.kou.otoskop.data.model.VerifyResult
import com.kou.otoskop.data.repository.BackendRepository
import com.kou.otoskop.data.repository.Esp32Repository
import com.kou.otoskop.data.repository.MoveDirection
import com.kou.otoskop.data.repository.MoveStep
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class TelescopeUiState(
    val status: TelescopeStatus = TelescopeStatus.EMPTY,
    val selectedTarget: CelestialObject? = null,
    val lastVerify: VerifyResult? = null,
    val busy: Boolean = false,
    val error: AppError? = null,
)

class TelescopeViewModel(
    private val application: Application,
) : ViewModel() {

    private fun app(): OtoskopApp = application as OtoskopApp
    private fun esp32(): Esp32Repository = app().esp32Repo
    private fun backend(): BackendRepository = app().backendRepo

    private val _state = MutableStateFlow(TelescopeUiState())
    val state: StateFlow<TelescopeUiState> = _state

    /** Canlı ekrandaki debug konsolu (uygulama geneli paylaşımlı tampon). */
    val debugLog: StateFlow<List<String>> = DebugLog.lines

    val streamUrl: String get() = esp32().streamUrl

    private var pollJob: Job? = null

    private companion object {
        /** Bu kadar ardışık /status hatası olmadan "bağlantı koptu" gösterme. */
        const val STATUS_FAILURE_THRESHOLD = 3
    }

    private fun appendDebug(line: String) = DebugLog.add(line)

    fun clearDebug() = DebugLog.clear()

    /** Bir /status sonucunu konsola, Mega link teşhisiyle birlikte yazar. */
    private fun logStatus(s: TelescopeStatus) {
        val megaInfo = when {
            // Hiç ham byte gelmiyorsa fiziksel hat sorunu (en sık: ortak GND yok,
            // gerilim bölücü yanlış hatta, Mega TX1=pin18 değil, ESP RX=GPIO13 değil).
            s.megaBytes == 0L ->
                "FIZIKSEL HAT YOK: 0 byte (GND ortak mı? bölücü? Mega TX1=18?)"
            // Byte geliyor ama tam satır yok -> baud/format uyumsuz (115200 8N1 olmalı).
            s.megaLines == 0L ->
                "byte=${s.megaBytes} ama satır=0 -> BAUD/FORMAT (115200 8N1?)"
            else ->
                "bytes=${s.megaBytes} lines=${s.megaLines} age=${s.megaAgeMs}ms"
        }
        appendDebug(
            "az=%.1f alt=%.1f gps=%s imu=%s | %s".format(
                s.azimuth, s.altitude,
                if (s.gpsFix) "T" else "F",
                if (s.imuOk) "T" else "F",
                megaInfo,
            ),
        )
        if (s.megaRaw.isNotBlank()) appendDebug("  raw: ${s.megaRaw}")
    }

    fun startPolling() {
        if (pollJob?.isActive == true) return
        // Önceki ekrandan kalan bayat hatayı temizle (geri dönünce yanıp sönmesin).
        if (_state.value.error != null) {
            _state.value = _state.value.copy(error = null)
        }
        pollJob = viewModelScope.launch {
            var consecutiveFailures = 0
            while (true) {
                esp32().status().fold(
                    onSuccess = { s ->
                        consecutiveFailures = 0
                        _state.value = _state.value.copy(status = s, error = null)
                        logStatus(s)
                    },
                    onFailure = { err ->
                        // Yayın sırasında tek tük poll'lar zaman aşımına uğrayabilir;
                        // birkaç ardışık hata olmadan bağlantı koptu deme (UI titremesin).
                        consecutiveFailures++
                        appendDebug("/status HATA: ${err.message}")
                        if (consecutiveFailures >= STATUS_FAILURE_THRESHOLD) {
                            _state.value = _state.value.copy(error = err)
                        }
                    },
                )
                delay(AppConfig.STATUS_POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun selectTarget(obj: CelestialObject) {
        _state.value = _state.value.copy(selectedTarget = obj, busy = true)
        appendDebug("/target %s az=%.1f alt=%.1f gönderiliyor".format(obj.name, obj.azimuth, obj.altitude))
        viewModelScope.launch {
            val r = esp32().sendTarget(obj.name, obj.azimuth, obj.altitude)
            _state.value = when (r) {
                is Resource.Success -> {
                    appendDebug("/target OK")
                    _state.value.copy(busy = false, error = null)
                }
                is Resource.Failure -> {
                    appendDebug("/target HATA: ${r.error.message}")
                    _state.value.copy(busy = false, error = r.error)
                }
            }
        }
    }

    fun toggleTracking(enabled: Boolean) {
        appendDebug("/track on=$enabled gönderiliyor")
        viewModelScope.launch {
            val r = esp32().setTracking(enabled)
            if (r is Resource.Failure) {
                appendDebug("/track HATA: ${r.error.message}")
                _state.value = _state.value.copy(error = r.error)
            } else {
                appendDebug("/track OK")
            }
        }
    }

    fun manualMove(direction: MoveDirection, step: MoveStep) {
        appendDebug("/move dir=$direction step=$step gönderiliyor")
        viewModelScope.launch {
            val r = esp32().move(direction, step)
            if (r is Resource.Failure) {
                appendDebug("/move HATA: ${r.error.message}")
                _state.value = _state.value.copy(error = r.error)
            } else {
                appendDebug("/move OK (Mega taz/talt değişmeli)")
            }
        }
    }

    fun calibrate() {
        appendDebug("/calibrate gönderiliyor")
        viewModelScope.launch {
            when (val r = esp32().calibrate()) {
                is Resource.Success -> appendDebug("/calibrate OK")
                is Resource.Failure -> {
                    appendDebug("/calibrate HATA: ${r.error.message}")
                    _state.value = _state.value.copy(error = r.error)
                }
            }
        }
    }

    fun verifyAndCorrect(sensor: PhoneSensorData) {
        val target = _state.value.selectedTarget
        if (target == null) {
            appendDebug("DOĞRULA iptal: hedef seçili değil")
            _state.value = _state.value.copy(
                error = AppError(
                    AppErrorKind.UNKNOWN,
                    "Önce gözlem listesinden bir hedef seç, sonra doğrula",
                ),
            )
            return
        }

        _state.value = _state.value.copy(busy = true, error = null)
        appendDebug("DOĞRULA başladı (hedef=${target.name})")
        viewModelScope.launch {
            val location = resolveLocation(sensor)
            if (location == null) {
                appendDebug("DOĞRULA HATA: GPS konumu yok")
                _state.value = _state.value.copy(
                    busy = false,
                    error = AppError(
                        AppErrorKind.GPS_UNAVAILABLE,
                        "GPS konumu yok (teleskop/telefon), doğrulama yapılamaz",
                    ),
                )
                return@launch
            }
            val (lat, lon) = location

            appendDebug("/camera snapshot isteniyor")
            val snap = esp32().snapshot()
            val bytes = when (snap) {
                is Resource.Success -> {
                    appendDebug("snapshot OK (${snap.value.size} byte)")
                    snap.value
                }
                is Resource.Failure -> {
                    appendDebug("snapshot HATA: ${snap.error.message}")
                    _state.value = _state.value.copy(busy = false, error = snap.error)
                    return@launch
                }
            }

            val s = _state.value.status
            appendDebug("Gemini doğrulama gönderiliyor")
            val v = backend().verifyImage(
                targetName = target.name,
                latitude = lat,
                longitude = lon,
                azimuth = s.azimuth,
                altitude = s.altitude,
                imageBytes = bytes,
            )

            when (v) {
                is Resource.Success -> {
                    appendDebug(
                        "DOĞRULA sonuç: verified=${v.value.verified} dAz=%.1f dAlt=%.1f".format(
                            v.value.azimuthCorrection, v.value.altitudeCorrection,
                        ),
                    )
                    _state.value = _state.value.copy(
                        busy = false, error = null, lastVerify = v.value,
                    )
                    if (!v.value.verified && v.value.needsCorrection) {
                        appendDebug("/correction gönderiliyor")
                        esp32().sendCorrection(
                            v.value.azimuthCorrection,
                            v.value.altitudeCorrection,
                        )
                    }
                }
                is Resource.Failure -> {
                    appendDebug("DOĞRULA HATA: ${v.error.message}")
                    _state.value = _state.value.copy(busy = false, error = v.error)
                }
            }
        }
    }

    /**
     * Konumu çözer: önce teleskop (ESP) GPS'i, kilit yoksa telefon GPS'i,
     * o da yoksa demo modunda sabit konum. Hiçbiri yoksa null.
     */
    private suspend fun resolveLocation(sensor: PhoneSensorData): Pair<Double, Double>? {
        (esp32().gps() as? Resource.Success)?.value?.let { espGps ->
            if (espGps.hasFix) return espGps.lat to espGps.lon
        }
        if (sensor.latitude != null && sensor.longitude != null) {
            return sensor.latitude to sensor.longitude
        }
        if (app().isDemoMode) {
            return AppConfig.DEMO_FALLBACK_LATITUDE to AppConfig.DEMO_FALLBACK_LONGITUDE
        }
        return null
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TelescopeViewModel(application) as T
    }
}
