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
import com.kou.otoskop.data.model.SkyArea
import com.kou.otoskop.data.repository.BackendRepository
import com.kou.otoskop.data.repository.Esp32Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant

data class ObjectsUiState(
    val area: SkyArea? = null,
    val objects: List<CelestialObject> = emptyList(),
    val loading: Boolean = false,
    val error: AppError? = null,
)

class ObjectsViewModel(
    private val application: Application,
) : ViewModel() {

    private fun app(): OtoskopApp = application as OtoskopApp
    private fun backend(): BackendRepository = app().backendRepo
    private fun esp32(): Esp32Repository = app().esp32Repo

    private val _state = MutableStateFlow(ObjectsUiState())
    val state: StateFlow<ObjectsUiState> = _state

    fun setArea(area: SkyArea) {
        _state.value = _state.value.copy(area = area)
    }

    fun scanArea(sensor: PhoneSensorData) {
        val area = _state.value.area ?: return

        _state.value = _state.value.copy(loading = true, error = null)
        DebugLog.add(
            "TARAMA başladı Az %.0f-%.0f Alt %.0f-%.0f".format(
                area.azimuthMin, area.azimuthMax, area.altitudeMin, area.altitudeMax,
            ),
        )
        viewModelScope.launch {
            val location = resolveLocation(sensor)
            if (location == null) {
                DebugLog.add("TARAMA HATA: GPS yok (teleskop/telefon)")
                _state.value = _state.value.copy(
                    loading = false,
                    error = AppError(
                        AppErrorKind.GPS_UNAVAILABLE,
                        "GPS yok (ne teleskopta ne telefonda). Gözlem listesi alınamaz",
                    ),
                )
                return@launch
            }
            val (lat, lon) = location
            DebugLog.add("Konum: %.4f, %.4f -> gök cismi servisi sorgulanıyor".format(lat, lon))
            val r = backend().observableObjects(
                latitude = lat,
                longitude = lon,
                datetime = Instant.now(),
                area = area,
            )
            _state.value = when (r) {
                is Resource.Success -> {
                    DebugLog.add(
                        if (r.value.isEmpty())
                            "TARAMA bitti: bu alanda obje YOK (servis sadece Güneş/Ay/gezegen döner)"
                        else "TARAMA bitti: ${r.value.size} obje -> liste açılıyor",
                    )
                    _state.value.copy(loading = false, objects = r.value)
                }
                is Resource.Failure -> {
                    DebugLog.add("TARAMA HATA: ${r.error.message}")
                    _state.value.copy(loading = false, error = r.error)
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

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ObjectsViewModel(application) as T
    }
}
