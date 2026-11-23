package com.itglasses.itglassesandroid

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * Demo lentes: short tones for "escuchando", "pensando" and "error" (with the glasses on nobody looks at the screen).
 * System tones, no audio files. Only used with "Usar lentes" on. Never throws.
 */
internal object VoiceCues {
    enum class Cue(val tone: Int, val ms: Int) {
        LISTENING(ToneGenerator.TONE_PROP_BEEP, 150),
        THINKING(ToneGenerator.TONE_PROP_ACK, 200),
        ERROR(ToneGenerator.TONE_PROP_NACK, 350),
    }

    /** Tone volume, 0..100. */
    const val VOLUME = 80

    /** [onCall]: the Bluetooth route is active, so the tone goes with the call audio (to the glasses). */
    fun play(cue: Cue, onCall: Boolean) {
        val stream = if (onCall) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
        val generator = try { ToneGenerator(stream, VOLUME) } catch (_: RuntimeException) { return }
        try {
            generator.startTone(cue.tone, cue.ms)
        } catch (_: RuntimeException) {
            generator.release()
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({ generator.release() }, cue.ms + RELEASE_DELAY_MS)
    }

    private const val RELEASE_DELAY_MS = 100L
}
