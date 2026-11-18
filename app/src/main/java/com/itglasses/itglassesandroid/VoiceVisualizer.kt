package com.itglasses.itglassesandroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Microphone with rings and bars that follow the real recording level (stage B2). Decorative for TalkBack.
 * Draws VoiceShapes scaled to fit, centred; onDraw allocates nothing.
 */
class VoiceVisualizer @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.itg_accent)
    private val micPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; style = Paint.Style.STROKE }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; style = Paint.Style.FILL }
    private val bar = RectF()
    private val mic = ContextCompat.getDrawable(context, R.drawable.ic_mic)?.mutate()
    private val bars = FloatArray(VoiceShapes.BARS)
    private var level = 0f

    /** False with animations off: rings at rest; the bars still get the level directly. */
    var animated = true
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setLevels(level: Float, values: List<Float>) {
        var changed = this.level != level
        this.level = level
        for (i in bars.indices) {
            val v = values.getOrElse(i) { 0f }
            if (bars[i] != v) { bars[i] = v; changed = true }
        }
        if (changed) invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize((VoiceShapes.WIDTH * density).toInt() + paddingLeft + paddingRight, widthMeasureSpec)
        val h = resolveSize((VoiceShapes.HEIGHT * density).toInt() + paddingTop + paddingBottom, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val innerW = (width - paddingLeft - paddingRight).toFloat()
        val innerH = (height - paddingTop - paddingBottom).toFloat()
        val s = VoiceShapes.scale(innerW / density, innerH / density) * density
        if (s <= 0f) return
        val left = paddingLeft + (innerW - VoiceShapes.WIDTH * s) / 2
        val top = paddingTop + (innerH - VoiceShapes.HEIGHT * s) / 2
        val cx = left + VoiceShapes.WIDTH / 2 * s
        val cy = top + VoiceShapes.CENTER_Y * s

        ringPaint.strokeWidth = VoiceShapes.RING_STROKE * s
        for (i in VoiceShapes.RINGS - 1 downTo 0) {
            ringPaint.alpha = (VoiceShapes.ringAlpha(i, level, animated) * 255).toInt()
            canvas.drawCircle(cx, cy, VoiceShapes.ringRadius(i, level, animated) * s, ringPaint)
        }
        canvas.drawCircle(cx, cy, VoiceShapes.MIC_RADIUS * s, micPaint)
        mic?.let {
            val half = (VoiceShapes.MIC_ICON / 2 * s).toInt()
            it.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
            it.draw(canvas)
        }

        val radius = VoiceShapes.BAR_WIDTH / 2 * s
        for (i in bars.indices) {
            val h = VoiceShapes.barHeight(bars[i]) * s
            val x = left + VoiceShapes.barLeft(i) * s
            val y = top + VoiceShapes.BARS_CENTER_Y * s
            bar.set(x, y - h / 2, x + VoiceShapes.BAR_WIDTH * s, y + h / 2)
            canvas.drawRoundRect(bar, radius, radius, barPaint)
        }
    }
}
