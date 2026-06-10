package com.kou.otoskop.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.kou.otoskop.R

/**
 * Yeşil/kırmızı nokta + etiket + opsiyonel detay göstergesi.
 */
class StatusChipView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val dot: android.view.View
    private val labelView: TextView
    private val detailView: TextView

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_status_chip)
        val padH = (10 * resources.displayMetrics.density).toInt()
        val padV = (6 * resources.displayMetrics.density).toInt()
        setPadding(padH, padV, padH, padV)
        LayoutInflater.from(context).inflate(R.layout.view_status_chip, this, true)
        dot = findViewById(R.id.chipDot)
        labelView = findViewById(R.id.chipLabel)
        detailView = findViewById(R.id.chipDetail)
    }

    fun setLabel(text: CharSequence) {
        labelView.text = text
    }

    fun setOk(ok: Boolean) {
        val color = if (ok) R.color.otoskop_success else R.color.otoskop_error
        dot.backgroundTintList = ColorStateList.valueOf(
            resources.getColor(color, context.theme),
        )
    }

    fun setDetail(text: CharSequence?) {
        if (text.isNullOrBlank()) {
            detailView.visibility = GONE
        } else {
            detailView.visibility = VISIBLE
            detailView.text = text
        }
    }
}
