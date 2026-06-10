package com.kou.otoskop.data.repository

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.kou.otoskop.data.model.PhoneSensorData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Telefon sensörlerini (FusedLocation + rotation vector) tek bir StateFlow'a
 * birleştirir. UI bunu `collect` eder.
 *
 * Tasarım kararı: Android'in rotation vector sensörünü kullanıyoruz
 * (accel + manyetometre + gyro birleşik); fallback olarak accel + magnet
 * varyantı eklenebilir.
 */
class SensorRepository(private val context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "SensorRepository"
    }

    private val _flow = MutableStateFlow(PhoneSensorData())
    val flow: StateFlow<PhoneSensorData> = _flow

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val fused = LocationServices.getFusedLocationProviderClient(context)

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var lastAccuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
    private var registered = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            _flow.value = _flow.value.copy(
                latitude = loc.latitude,
                longitude = loc.longitude,
                gpsAccuracyM = loc.accuracy,
            )
        }
    }

    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (registered) return

        try {
            rotationSensor?.let {
                sensorManager.registerListener(
                    this, it, SensorManager.SENSOR_DELAY_UI,
                )
            }
            accelSensor?.let {
                sensorManager.registerListener(
                    this, it, SensorManager.SENSOR_DELAY_UI,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sensör kaydı başarısız", e)
            return
        }

        registered = true

        if (hasLocationPermission()) {
            try {
                val req = LocationRequest.Builder(
                    Priority.PRIORITY_HIGH_ACCURACY, 1_000L,
                )
                    .setMinUpdateDistanceMeters(1f)
                    .build()
                fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
            } catch (e: SecurityException) {
                Log.w(TAG, "Konum izni yok veya iptal edildi", e)
            } catch (e: Exception) {
                Log.e(TAG, "Google Play konumu kullanılamıyor (emülatör /.gms)", e)
            }
        }
    }

    fun stop() {
        if (!registered) return
        try {
            sensorManager.unregisterListener(this)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterListener", e)
        }
        try {
            fused.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            Log.w(TAG, "removeLocationUpdates", e)
        }
        registered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                // orientation[0] = azimuth (rad), [1] = pitch, [2] = roll
                var azDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
                if (azDeg < 0) azDeg += 360f
                val pitchDeg = Math.toDegrees(-orientation[1].toDouble()).toFloat()
                val rollDeg = Math.toDegrees(orientation[2].toDouble()).toFloat()
                _flow.value = _flow.value.copy(
                    compassHeading = azDeg,
                    pitchDeg = pitchDeg,
                    rollDeg = rollDeg,
                    compassCalibrated = lastAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
                )
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // Rotation vector yoksa fallback pitch: ufka göre eğim.
                if (rotationSensor == null) {
                    val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                    val pitch = Math.toDegrees(
                        atan2((-z).toDouble(), sqrt(x * x + y * y).toDouble()),
                    ).toFloat()
                    _flow.value = _flow.value.copy(pitchDeg = pitch)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            lastAccuracy = accuracy
            _flow.value = _flow.value.copy(
                compassCalibrated = accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
            )
        }
    }
}
