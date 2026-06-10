package com.kou.otoskop.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.kou.otoskop.core.AstroMath
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Telefonun baktığı yönü (azimuth) gösteren basit pusula. Dış halka
 * dünyaya göre döner; üst ortadaki ok kullanıcının baktığı yönü işaret eder.
 */
class CompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var azimuth: Float = 0f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.argb(110, 255, 255, 255)
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFFFC107")
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 44f
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        isFakeBoldText = true
    }

    private val northPaint = Paint(textPaint).apply {
        color = Color.parseColor("#FFE53935")
    }

    private val labels = arrayOf("K", "D", "G", "B")
    private val bounds = Rect()

    fun setAzimuth(deg: Float) {
        azimuth = deg
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 12f

        canvas.drawCircle(cx, cy, r, ringPaint)

        for (i in 0..3) {
            val angleDeg = i * 90.0 - azimuth
            val rad = AstroMath.degToRad(angleDeg - 90)
            val px = cx + (r - 36f) * cos(rad).toFloat()
            val py = cy + (r - 36f) * sin(rad).toFloat()
            val paint = if (i == 0) northPaint else textPaint
            paint.getTextBounds(labels[i], 0, 1, bounds)
            canvas.drawText(labels[i], px, py + bounds.height() / 2f, paint)
        }

        // Üstte sabit duran "baktığım yön" oku.
        val path = Path().apply {
            moveTo(cx, cy - r + 4f)
            lineTo(cx - 16f, cy - r + 36f)
            lineTo(cx + 16f, cy - r + 36f)
            close()
        }
        canvas.drawPath(path, arrowPaint)
    }
}
