package com.itglasses.itglassesandroid

import android.media.MediaRecorder

/**
 * Microphone sources to try, in order (Voz 1). VOICE_RECOGNITION is tuned for speech recognition (no call
 * processing); MIC is the fallback. VOICE_COMMUNICATION is never used, and no audio effects are added.
 */
internal object MicSources {
    val order: List<Int> = listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)

    fun name(source: Int?): String = when (source) {
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.MIC -> "MIC"
        null -> "none"
        else -> "other"
    }
}
