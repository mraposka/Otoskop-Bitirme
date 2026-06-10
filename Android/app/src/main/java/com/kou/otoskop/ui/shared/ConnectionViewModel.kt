package com.kou.otoskop.ui.shared

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kou.otoskop.OtoskopApp
import com.kou.otoskop.core.AppError
import com.kou.otoskop.core.AppErrorKind
import com.kou.otoskop.data.network.Esp32Discovery
import com.kou.otoskop.data.repository.Esp32Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class ConnectionStatus { IDLE, TESTING, CONNECTED, FAILED }

data class ConnectionUiState(
    val ip: String,
    val port: Int,
    val status: ConnectionStatus = ConnectionStatus.IDLE,
    val error: AppError? = null,
)

class ConnectionViewModel(
    private val application: Application,
) : ViewModel() {

    private fun app(): OtoskopApp = application as OtoskopApp
    private fun esp32(): Esp32Repository = app().esp32Repo

    private val discovery = Esp32Discovery(application)

    private val _state = MutableStateFlow(initialUiState())
    val state: StateFlow<ConnectionUiState> = _state

    private fun initialUiState(): ConnectionUiState {
        val e = esp32()
        val demo = app().isDemoMode
        return ConnectionUiState(
            ip = e.host,
            port = e.port,
            status = if (demo) ConnectionStatus.CONNECTED else ConnectionStatus.IDLE,
        )
    }

    /** Demo anahtarı veya repo değişiminden sonra UI durumunu yeniler. */
    fun refreshAfterDemoToggle() {
        val e = esp32()
        val demo = app().isDemoMode
        _state.value = ConnectionUiState(
            ip = e.host,
            port = e.port,
            status = if (demo) ConnectionStatus.CONNECTED else ConnectionStatus.IDLE,
            error = null,
        )
    }

    fun updateIp(ip: String) {
        esp32().setEndpoint(ip)
        app().setEsp32Host(ip)
        val idle =
            if (app().isDemoMode) ConnectionStatus.CONNECTED else ConnectionStatus.IDLE
        _state.value = _state.value.copy(ip = ip, status = idle)
    }

    /**
     * ESP'yi yerel ağda mDNS/NSD ile otomatik bulur. Bulunca IP'yi ayarlar,
     * kaydeder ve bağlantıyı test eder. Bulamazsa hata gösterir.
     */
    fun discover() {
        if (app().isDemoMode) {
            testConnection()
            return
        }
        _state.value = _state.value.copy(status = ConnectionStatus.TESTING, error = null)
        viewModelScope.launch {
            val host = discovery.discover()
            if (host == null) {
                _state.value = _state.value.copy(
                    status = ConnectionStatus.FAILED,
                    error = AppError(
                        AppErrorKind.ESP32_UNREACHABLE,
                        "Ağda otomatik bulunamadı. ESP ile aynı WiFi/hotspot'ta olduğundan " +
                            "emin ol ya da IP'yi elle gir.",
                    ),
                )
                return@launch
            }
            esp32().setEndpoint(host)
            app().setEsp32Host(host)
            _state.value = _state.value.copy(ip = host)
            esp32().status().fold(
                onSuccess = {
                    _state.value = _state.value.copy(status = ConnectionStatus.CONNECTED)
                },
                onFailure = { err ->
                    _state.value = _state.value.copy(
                        status = ConnectionStatus.FAILED, error = err,
                    )
                },
            )
        }
    }

    fun testConnection() {
        if (app().isDemoMode) {
            _state.value = _state.value.copy(
                status = ConnectionStatus.CONNECTED,
                error = null,
            )
            return
        }
        _state.value = _state.value.copy(status = ConnectionStatus.TESTING, error = null)
        viewModelScope.launch {
            esp32().status().fold(
                onSuccess = {
                    _state.value = _state.value.copy(status = ConnectionStatus.CONNECTED)
                },
                onFailure = { err ->
                    _state.value = _state.value.copy(
                        status = ConnectionStatus.FAILED, error = err,
                    )
                },
            )
        }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ConnectionViewModel(application) as T
    }
}
