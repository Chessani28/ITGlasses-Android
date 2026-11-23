package com.itglasses.itglassesandroid

/**
 * Demo lentes: automatic end of speech (energy VAD) over the VoiceMeter level (0..1 from VoiceLevel.of, where 0 is
 * -50 dBFS and 1 is 0 dBFS). Pure Kotlin; recording thread only, one instance per recording.
 *
 * The first [calibrationMs] measure the background. Speech is a level at or above
 * max([speechLevel], min(background, NOISE_CAP) + [margin]) for at least [minSpeechMs]. Once speech was heard,
 * [silenceMs] below that threshold ends the turn. It never ends before speech was heard, so the manual
 * "Detener y enviar" and the 30 s limit keep working exactly as before.
 *
 * To tune it, change the constants in the companion object (SILENCE_MS and SPEECH_LEVEL first).
 */
internal class EndOfSpeech(
    private val silenceMs: Long = SILENCE_MS,
    private val speechLevel: Float = SPEECH_LEVEL,
    private val margin: Float = MARGIN,
    private val minSpeechMs: Long = MIN_SPEECH_MS,
    private val calibrationMs: Long = CALIBRATION_MS,
) {
    private var elapsedMs = 0L
    private var noiseSum = 0.0
    private var noiseMs = 0L
    private var speechMs = 0L
    private var silentMs = 0L

    /** Speech was heard: from now on a long enough silence ends the turn. */
    var heard = false
        private set

    /** Current speech threshold (0..1). */
    val threshold: Float
        get() {
            val noise = if (noiseMs > 0) (noiseSum / noiseMs).toFloat() else 0f
            return maxOf(speechLevel, minOf(noise, NOISE_CAP) + margin)
        }

    /** One block of [blockMs] at [level]. True when the speaker has finished (send the recording). */
    fun onBlock(level: Float, blockMs: Long): Boolean {
        if (blockMs <= 0) return false
        elapsedMs += blockMs
        if (elapsedMs <= calibrationMs) {
            noiseSum += level.toDouble() * blockMs
            noiseMs += blockMs
            return false
        }
        if (level >= threshold) {
            speechMs += blockMs
            silentMs = 0
            if (speechMs >= minSpeechMs) heard = true
        } else if (heard) {
            silentMs += blockMs
            if (silentMs >= silenceMs) return true
        } else {
            speechMs = 0  // A short blip before speaking does not count as speech.
        }
        return false
    }

    companion object {
        /** Silence after speech that ends the turn (same 1.1 s as the web voice panel). */
        const val SILENCE_MS = 1100L
        /** Minimum speech level: 0.40 is about -30 dBFS. Lower it if the glasses' microphone sounds quiet. */
        const val SPEECH_LEVEL = 0.40f
        /** Speech must be this far above the measured background. */
        const val MARGIN = 0.15f
        /** The measured background never counts above this (someone talking during calibration). */
        const val NOISE_CAP = 0.50f
        /** Speech shorter than this (a click, a cough) does not arm the end of speech. */
        const val MIN_SPEECH_MS = 200L
        /** Background measured at the start of each recording. */
        const val CALIBRATION_MS = 300L

        /** Duration of [samples] PCM samples at [sampleRate]. */
        fun blockMs(samples: Int, sampleRate: Int = 16_000): Long =
            if (samples <= 0 || sampleRate <= 0) 0L else samples * 1000L / sampleRate
    }
}
