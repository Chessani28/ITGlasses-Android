package com.itglasses.itglassesandroid

/**
 * Speech-recognition engine requested from /api/v1/voice/transcribe (Voz 1). Pure Kotlin.
 * Pairing mode asks for Whisper; one retry with Vosk only after a 503 (Whisper not configured, not enough free
 * memory…). Shared-token mode sends no header: the backend uses Vosk, as before.
 */
internal object SttEngine {
    const val HEADER = "X-ITGlasses-STT-Engine"
    const val WHISPER = "whisper-small"
    const val VOSK = "vosk"

    fun first(pairing: Boolean): String? = if (pairing) WHISPER else null

    /** The engine to retry with, or null: only Whisper → Vosk, only after 503, so never more than one retry. */
    fun fallbackAfter(engine: String?, status: Int): String? = if (engine == WHISPER && status == 503) VOSK else null

    /** "Motor de voz básico" is shown only when the transcript really came from the Vosk fallback. */
    fun isBasic(engine: String?): Boolean = engine == VOSK
}

/** Debug-only timing line for logcat (tag ITG-Voice): step, milliseconds, engine and microphone. Never text or audio. */
internal object VoiceDebugLog {
    const val TAG = "ITG-Voice"
    private val safe = Regex("[a-z_]{1,24}")

    fun line(step: String, millis: Long, engine: String?, mic: String?): String {
        require(safe.matches(step)) { "step must be a short identifier" }
        return "step=$step ms=${millis.coerceAtLeast(0)} engine=${engine ?: "none"} mic=${mic ?: "none"}"
    }
}
