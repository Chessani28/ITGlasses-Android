package com.itglasses.itglassesandroid

import android.content.Context
import android.view.KeyEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Background voice ("Escuchar con el celular bloqueado"): the switch. Off by default. */
internal object BackgroundVoice {
    private const val PREFS = "itglasses_glasses"
    private const val KEY_ENABLED = "background_voice"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}

/** Stage of a background voice turn. */
internal enum class TurnStage { READY, LISTENING, THINKING, SPEAKING }

internal enum class TalkAction { START, STOP_AND_SEND, CANCEL, IGNORE }

/**
 * Pure: what a tap does. One tap on the glasses' temple (PLAY, PAUSE or PLAY_PAUSE; HEADSETHOOK too, since with the
 * call route open the same tap can arrive as the headset button), the notification's button or the app's "Hablar":
 * READY starts listening, LISTENING stops and sends, anything else is ignored. A swipe (NEXT) cancels the turn on its
 * way (listening, thinking or speaking) and does nothing outside a turn. Long presses never reach the app.
 */
internal object TalkButton {
    val TALK_KEYS = setOf(
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK,
    )
    const val CANCEL_KEY = KeyEvent.KEYCODE_MEDIA_NEXT

    fun forTap(stage: TurnStage): TalkAction = when (stage) {
        TurnStage.READY -> TalkAction.START
        TurnStage.LISTENING -> TalkAction.STOP_AND_SEND
        TurnStage.THINKING, TurnStage.SPEAKING -> TalkAction.IGNORE
    }

    fun forCancel(stage: TurnStage): TalkAction = if (stage == TurnStage.READY) TalkAction.IGNORE else TalkAction.CANCEL

    /** Only the key-down of a first press counts (the key-up and auto-repeats of the same tap are ignored). */
    fun forKey(keyCode: Int, action: Int, repeat: Int, stage: TurnStage): TalkAction = when {
        action != KeyEvent.ACTION_DOWN || repeat > 0 -> TalkAction.IGNORE
        keyCode == CANCEL_KEY -> forCancel(stage)
        keyCode in TALK_KEYS -> forTap(stage)
        else -> TalkAction.IGNORE
    }
}

/**
 * Pure: media buttons are ignored for [windowMs] after events that make the glasses send keys on their own (they send
 * PLAY about 0.5 s after the call route closes, to resume music): the end of a turn, a Bluetooth route or device
 * change, and the start of the service. Thread-safe; [now] is a monotonic clock in milliseconds.
 */
internal class ButtonGuard(private val now: () -> Long, private val windowMs: Long = WINDOW_MS) {
    private var until = 0L
    private var cause = "none"

    @Synchronized
    fun block(reason: String) {
        val end = now() + windowMs
        if (end >= until) {
            until = end
            cause = reason
        }
    }

    /** The reason of the active block, or null when keys are accepted. */
    @Synchronized
    fun blockedBy(): String? = if (now() < until) cause else null

    companion object {
        /** Keys ignored this long after a turn ends, the route changes or the service starts. */
        const val WINDOW_MS = 3000L
    }
}

internal enum class RecordingStop { NO_SPEECH, MAX_DURATION }

/**
 * Pure: when a background recording stops on its own (besides the end of speech and the user's taps). Shorter than the
 * app's 30 s on purpose: the service never records a whole conversation.
 */
internal object BackgroundRecording {
    /**
     * No voice at all in this much real audio (counted from the first block that is not digital silence, so a
     * Bluetooth microphone that starts late does not eat into it): cancel in silence, nothing is sent.
     */
    const val NO_SPEECH_TIMEOUT_MS = 5_000L
    /** Any voice for this long (in total) keeps the turn: it is then ended by the end of speech or the limit. */
    const val VOICE_SEEN_MS = 150L
    /** Hard limit of a background recording. */
    const val MAX_RECORDING_MS = 15_000L

    /** [wallMs] since recording started, [audioMs] of real (non-zero) audio, [vad] of this recording. */
    fun stop(wallMs: Long, audioMs: Long, vad: EndOfSpeech): RecordingStop? = when {
        !voiceSeen(vad) && audioMs >= NO_SPEECH_TIMEOUT_MS -> RecordingStop.NO_SPEECH
        wallMs >= MAX_RECORDING_MS -> RecordingStop.MAX_DURATION
        else -> null
    }

    fun voiceSeen(vad: EndOfSpeech): Boolean = vad.heard || vad.voiceMs >= VOICE_SEEN_MS

    /** Every sample is 0: the microphone is not delivering audio yet (common right after a SCO link opens). */
    fun digitalSilence(samples: ShortArray, count: Int): Boolean {
        for (i in 0 until count.coerceIn(0, samples.size)) if (samples[i].toInt() != 0) return false
        return true
    }

    /** One diagnostic line: levels 0..1 with two decimals, times in ms. Never audio or text. */
    fun describe(result: String, wallMs: Long, audioMs: Long, zeroMs: Long, vad: EndOfSpeech): String =
        "background record result=$result wall_ms=$wallMs audio_ms=$audioMs zero_ms=$zeroMs " +
            "peak=${two(vad.peak)} noise=${two(vad.noise)} threshold=${two(vad.threshold)} " +
            "hint=${two(vad.hintThreshold)} voice_ms=${vad.voiceMs} heard=${vad.heard}"

    private fun two(value: Float) = "%.2f".format(java.util.Locale.ROOT, value)
}

/** What the open app shows of a background turn (bubbles). */
internal sealed interface TurnUpdate {
    data class Asked(val text: String) : TurnUpdate
    data class Answered(val text: String) : TurnUpdate
    data class Failed(val notice: String) : TurnUpdate
}

/** Process-wide state shared by VoiceForegroundService, VoicePipeline and MainActivity. */
internal object BackgroundTurns {
    val running = MutableStateFlow(false)
    val stage = MutableStateFlow(TurnStage.READY)
    val updates = MutableSharedFlow<TurnUpdate>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** The service could not start in the foreground: the app turns the switch off and keeps the usual mode. */
    val startFailures = MutableSharedFlow<Unit>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}
