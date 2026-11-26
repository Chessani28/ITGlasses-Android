package com.itglasses.itglassesandroid

/**
 * Why the voice was transcribed by a lesser engine, from /api/v1/voice/transcribe ("fallback_reason"): Vosk after a
 * Whisper 503 (GPU memory, GPU unusable, worker did not start) or Whisper Small on the CPU. The Activity shows a
 * fixed Spanish notice for each kind (never the body), once until the voice is back to normal.
 */
internal enum class EngineNotice {
    /** Vosk: not enough GPU memory for Whisper. */
    VRAM,
    /** Vosk: the GPU cannot be used right now. */
    GPU_UNAVAILABLE,
    /** Vosk: Whisper did not start on the GPU. */
    STARTUP,
    /** Vosk, for another reason. */
    VOSK,
    /** Whisper Small on the CPU: slower. */
    CPU;

    companion object {
        /** [basic]: transcribed by Vosk (SttEngine.isBasic); [reason]: the backend's fallback_reason or null. */
        fun of(basic: Boolean, reason: String?): EngineNotice? {
            val code = reason?.trim()?.takeIf { it.isNotEmpty() }
            if (basic) return when (code) {
                "voice_vram_limit" -> VRAM
                "voice_gpu_unavailable" -> GPU_UNAVAILABLE
                "voice_worker_startup" -> STARTUP
                else -> VOSK
            }
            return if (code != null) CPU else null
        }
    }
}

/** Shows each notice once: again only after a turn with the normal engine, or when the reason changes. UI thread. */
internal class EngineNotices {
    private var last: EngineNotice? = null

    /** The notice to show for this turn, or null (normal engine, or the same reason already shown). */
    fun next(current: EngineNotice?): EngineNotice? {
        val show = current?.takeIf { it != last }
        last = current
        return show
    }
}
