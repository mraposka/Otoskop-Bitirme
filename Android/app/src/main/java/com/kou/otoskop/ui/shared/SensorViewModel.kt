package com.kou.otoskop.ui.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.kou.otoskop.data.model.PhoneSensorData
import com.kou.otoskop.data.repository.SensorRepository
import kotlinx.coroutines.flow.StateFlow

class SensorViewModel(
    private val sensor: SensorRepository,
) : ViewModel() {

    val state: StateFlow<PhoneSensorData> = sensor.flow

    fun start() = sensor.start()
    fun stop() = sensor.stop()
    fun hasLocationPermission(): Boolean = sensor.hasLocationPermission()

    override fun onCleared() {
        sensor.stop()
        super.onCleared()
    }

    class Factory(private val sensor: SensorRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SensorViewModel(sensor) as T
    }
}
