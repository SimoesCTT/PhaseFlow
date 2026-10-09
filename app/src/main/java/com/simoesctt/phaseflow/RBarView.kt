package com.simoesctt.phaseflow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * A horizontal bar showing the live Kuramoto order parameter r, with
 * an optional target marker.
 */
class RBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var value: Double = 0.0
        set(v) { field = v.coerceIn(0.0, 1.0); invalidate() }

    var target: Double = -1.0   // -1 means no target
        set(v) { field = v; invalidate() }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A2820")
        style = Paint.Style.FILL
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8A65")
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4DB6AC")
        textSize = 26f
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 8f

        // Background
        rect.set(pad, pad, w - pad, h - pad)
        canvas.drawRoundRect(rect, 12f, 12f, bgPaint)

        // Filled bar
        val fillW = (w - 2 * pad) * value.toFloat()
        rect.set(pad, pad, pad + fillW, h - pad)
        barPaint.color = when {
            value > 0.7 -> Color.parseColor("#69F0AE")
            value > 0.4 -> Color.parseColor("#64FFDA")
            value > 0.2 -> Color.parseColor("#4DB6AC")
            else -> Color.parseColor("#00796B")
        }
        canvas.drawRoundRect(rect, 12f, 12f, barPaint)

        // Target marker
        if (target in 0.0..1.0) {
            val tx = pad + (w - 2 * pad) * target.toFloat()
            canvas.drawLine(tx, pad, tx, h - pad, targetPaint)
        }

        // Value text
        val label = String.format("%.3f", value)
        canvas.drawText(label, w - 100f, h / 2 + 10f, textPaint)
    }
}
