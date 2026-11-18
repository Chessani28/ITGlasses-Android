package com.itglasses.itglassesandroid

import kotlin.math.min

/**
 * Geometry of the voice screen in dp, for a 260 x 300 dp drawing scaled down to fit (stage B2). Pure Kotlin.
 * With animations off the rings stay at rest; the bars still show the level (VoiceLevel.smooth gives it directly).
 */
internal object VoiceShapes {
    const val WIDTH = 260f
    const val HEIGHT = 300f
    const val MIC_RADIUS = 48f
    const val MIC_ICON = 40f
    const val CENTER_Y = 130f
    const val RINGS = 3
    const val RING_STROKE = 2f
    const val BARS = 5
    const val BAR_WIDTH = 6f
    const val BAR_GAP = 6f
    const val BAR_MIN = 6f
    const val BAR_RANGE = 36f
    const val BARS_CENTER_Y = 260f

    /** Never larger than 1: the drawing only shrinks, so "Detener y enviar" always fits below it. */
    fun scale(availableWidth: Float, availableHeight: Float): Float {
        if (availableWidth <= 0f || availableHeight <= 0f) return 0f
        return min(1f, min(availableWidth / WIDTH, availableHeight / HEIGHT))
    }

    fun ringRadius(index: Int, level: Float, animated: Boolean): Float =
        MIC_RADIUS + (12f + 28f * shown(level, animated)) * (index + 1) / RINGS

    fun ringAlpha(index: Int, level: Float, animated: Boolean): Float =
        ((0.45f - 0.12f * index) * (0.3f + 0.7f * shown(level, animated))).coerceIn(0f, 1f)

    fun barHeight(level: Float): Float = BAR_MIN + BAR_RANGE * level.coerceIn(0f, 1f)

    /** Left edge of bar [index], bars centred horizontally. */
    fun barLeft(index: Int): Float = (WIDTH - (BARS * BAR_WIDTH + (BARS - 1) * BAR_GAP)) / 2 + index * (BAR_WIDTH + BAR_GAP)

    private fun shown(level: Float, animated: Boolean) = if (animated) level.coerceIn(0f, 1f) else 0f
}
