package com.itglasses.itglassesandroid

/** Stage B voice flow. Pure Kotlin; UI thread only. The screen is open only while LISTENING. */
internal enum class VoiceStage { IDLE, LISTENING, TRANSCRIBING, THINKING, SYNTHESIZING, SPEAKING, DONE, CANCELLED, FAILED }

/** The call (or playback) a failure belongs to: decides "No enviado" in ChatLog.voiceFailed. */
internal enum class VoiceStep { TRANSCRIBE, CHAT, SYNTHESIS, PLAYBACK }

internal enum class VoiceEvent {
    START,
    /** "Detener y enviar", or the 30 s limit (same thing). */
    STOP,
    /** "Cancelar" or Back while listening: nothing is sent. */
    CANCEL,
    TRANSCRIBED,
    /** "No se reconoció ninguna voz." */
    NO_SPEECH,
    /** The backend asks to clarify: shown as an answer, no voice. */
    CLARIFICATION,
    ANSWERED,
    AUDIO_READY,
    /** Synthesis returned no audio. */
    NO_AUDIO,
    PLAYBACK_DONE,
    FAILED,
}

internal class VoiceFlow {
    var stage: VoiceStage = VoiceStage.IDLE
        private set

    /** Step of the last failure; null if the last run did not fail in a call (or failed while listening). */
    var failedStep: VoiceStep? = null
        private set

    val screenOpen: Boolean get() = stage == VoiceStage.LISTENING

    /** Back cancels only while the voice screen is open; afterwards it does the usual. */
    val backCancels: Boolean get() = screenOpen

    /** A question is on its way (or being spoken): no new recording meanwhile. */
    val busy: Boolean get() = stage in BUSY

    /** Applies [event]; false (and no change) if it is not valid in the current stage. */
    fun on(event: VoiceEvent): Boolean {
        val next = TRANSITIONS[stage]?.get(event) ?: return false
        if (event == VoiceEvent.START) failedStep = null
        if (next == VoiceStage.FAILED) failedStep = STEP_OF[stage]
        stage = next
        return true
    }

    private companion object {
        val BUSY = setOf(VoiceStage.TRANSCRIBING, VoiceStage.THINKING, VoiceStage.SYNTHESIZING, VoiceStage.SPEAKING)
        val STEP_OF = mapOf(
            VoiceStage.TRANSCRIBING to VoiceStep.TRANSCRIBE,
            VoiceStage.THINKING to VoiceStep.CHAT,
            VoiceStage.SYNTHESIZING to VoiceStep.SYNTHESIS,
            VoiceStage.SPEAKING to VoiceStep.PLAYBACK,
        )
        private val start = mapOf(VoiceEvent.START to VoiceStage.LISTENING)
        val TRANSITIONS: Map<VoiceStage, Map<VoiceEvent, VoiceStage>> = mapOf(
            VoiceStage.IDLE to start,
            VoiceStage.DONE to start,
            VoiceStage.CANCELLED to start,
            VoiceStage.FAILED to start,
            VoiceStage.LISTENING to mapOf(
                VoiceEvent.STOP to VoiceStage.TRANSCRIBING,
                VoiceEvent.CANCEL to VoiceStage.CANCELLED,
                VoiceEvent.FAILED to VoiceStage.FAILED,
            ),
            VoiceStage.TRANSCRIBING to mapOf(
                VoiceEvent.TRANSCRIBED to VoiceStage.THINKING,
                VoiceEvent.NO_SPEECH to VoiceStage.DONE,
                VoiceEvent.CLARIFICATION to VoiceStage.DONE,
                VoiceEvent.FAILED to VoiceStage.FAILED,
            ),
            VoiceStage.THINKING to mapOf(
                VoiceEvent.ANSWERED to VoiceStage.SYNTHESIZING,
                VoiceEvent.FAILED to VoiceStage.FAILED,
            ),
            VoiceStage.SYNTHESIZING to mapOf(
                VoiceEvent.AUDIO_READY to VoiceStage.SPEAKING,
                VoiceEvent.NO_AUDIO to VoiceStage.DONE,
                VoiceEvent.FAILED to VoiceStage.FAILED,
            ),
            VoiceStage.SPEAKING to mapOf(
                VoiceEvent.PLAYBACK_DONE to VoiceStage.DONE,
                VoiceEvent.FAILED to VoiceStage.FAILED,
            ),
        )
    }
}

/** Seconds counter of the voice screen and the 30 s limit (same value as MainActivity's MAX_DURATION_MS). */
internal object VoiceTiming {
    const val MAX_MS = 30_000L

    fun seconds(elapsedMs: Long): Int = (elapsedMs.coerceIn(0L, MAX_MS) / 1000).toInt()

    /** At the limit recording stops on its own, exactly like "Detener y enviar". */
    fun reachedMax(elapsedMs: Long): Boolean = elapsedMs >= MAX_MS
}
