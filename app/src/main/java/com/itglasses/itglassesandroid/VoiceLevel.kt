package com.itglasses.itglassesandroid

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Microphone level for the voice screen (stage B). Pure Kotlin. The recording thread computes the level of each
 * PCM16 block; the screen reads it every 50 ms and smooths it (or not, with animations off).
 */
internal object VoiceLevel {
    /** At or below this the level is 0 (silence); 0 dBFS is 1. */
    const val FLOOR_DB = -50.0
    /** Rises fast, falls slowly. */
    const val ATTACK = 0.5f
    const val RELEASE = 0.15f

    /** RMS of the first [count] samples as dBFS mapped to 0..1. No allocation: safe in the recording loop. */
    fun of(samples: ShortArray, count: Int = samples.size): Float {
        val n = count.coerceIn(0, samples.size)
        if (n == 0) return 0f
        var sum = 0.0
        for (i in 0 until n) {
            val sample = samples[i] / 32768.0
            sum += sample * sample
        }
        val rms = sqrt(sum / n)
        if (rms <= 0.0) return 0f
        val db = 20 * log10(rms)
        return ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0.0, 1.0).toFloat()
    }

    /** Next shown level. With animations off the bars show the level directly. */
    fun smooth(previous: Float, target: Float, animated: Boolean): Float {
        if (!animated) return target
        val factor = if (target > previous) ATTACK else RELEASE
        return previous + (target - previous) * factor
    }
}

/** Written by the recording thread, read by the UI. */
internal class VoiceMeter {
    @Volatile var level: Float = 0f
        private set

    fun onSamples(samples: ShortArray, count: Int) {
        level = VoiceLevel.of(samples, count)
    }

    fun reset() {
        level = 0f
    }
}

/** Last [size] levels for the bars, oldest first. UI thread only. */
internal class LevelHistory(val size: Int = 5) {
    private val values = ArrayDeque<Float>()

    init {
        require(size > 0)
    }

    fun push(level: Float) {
        values.addLast(level.coerceIn(0f, 1f))
        while (values.size > size) values.removeFirst()
    }

    /** Always [size] values: zeros first until enough levels have arrived. */
    fun snapshot(): List<Float> = List(size - values.size) { 0f } + values

    fun clear() = values.clear()
}
