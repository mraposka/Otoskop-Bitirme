package com.kou.otoskop.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import com.kou.otoskop.R
import com.kou.otoskop.core.AppError
import com.kou.otoskop.core.AppErrorKind

/**
 * Kullanıcı dostu hata banner'ı. `AppErrorKind` -> Türkçe etiket;
 * isteğe bağlı "Yeniden dene" butonu.
 */
class AppErrorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val text: TextView
    private val retry: AppCompatButton

    init {
        LayoutInflater.from(context).inflate(R.layout.view_app_error, this, true)
        text = findViewById(R.id.errorText)
        retry = findViewById(R.id.errorRetry)
        visibility = View.GONE
    }

    fun show(error: AppError, onRetry: (() -> Unit)? = null) {
        val label = when (error.kind) {
            AppErrorKind.ESP32_UNREACHABLE -> R.string.err_esp32_unreachable
            AppErrorKind.CAMERA_STREAM_FAILED -> R.string.err_camera_stream
            AppErrorKind.GPS_UNAVAILABLE -> R.string.err_gps_unavailable
            AppErrorKind.COMPASS_UNCALIBRATED -> R.string.err_compass_uncalibrated
            AppErrorKind.BACKEND_UNREACHABLE -> R.string.err_backend_unreachable
            AppErrorKind.TARGET_NOT_VERIFIED -> R.string.err_target_not_verified
            AppErrorKind.PERMISSION_DENIED -> R.string.err_permission_denied
            AppErrorKind.UNKNOWN -> R.string.err_unknown
        }
        text.text = "${context.getString(label)} — ${error.message}"
        if (onRetry != null) {
            retry.visibility = View.VISIBLE
            retry.setOnClickListener { onRetry() }
        } else {
            retry.visibility = View.GONE
        }
        visibility = View.VISIBLE
    }

    fun hide() {
        visibility = View.GONE
    }
}
