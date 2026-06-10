package com.kou.otoskop

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) startSensors()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBarInsets()
        requestPermissionsIfNeeded()
    }

    /**
     * Android 15+ (targetSdk 35+) edge-to-edge'i zorunlu kılar: içerik durum
     * çubuğu (saat) ve gezinme çubuğu altına çizilir, üstteki başlık ile alttaki
     * butonlar sistem çubuklarının altında kalıp tıklanamaz hale gelir.
     * NavHost'a sistem çubuğu + çentik insetlerini padding olarak uygulayarak
     * tüm ekranları güvenli alana hapsediyoruz.
     */
    private fun applySystemBarInsets() {
        val navHost = findViewById<android.view.View>(R.id.nav_host)
        ViewCompat.setOnApplyWindowInsetsListener(navHost) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasLocationPermission()) startSensors()
    }

    override fun onStop() {
        try {
            (application as? OtoskopApp)?.sensorRepo?.stop()
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "sensorRepo.stop", e)
        }
        super.onStop()
    }

    private fun hasLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsIfNeeded() {
        if (hasLocationPermission()) return
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    private fun startSensors() {
        try {
            (application as OtoskopApp).sensorRepo.start()
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "sensorRepo.start", e)
        }
    }
}
