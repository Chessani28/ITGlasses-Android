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

internal enum class TalkAction { START, STOP_AND_SEND, IGNORE }

/**
 * Pure: what a tap does. One tap on the glasses' temple (PLAY, PAUSE or PLAY_PAUSE; HEADSETHOOK too, since with the
 * call route open the same tap can arrive as the headset button), the notification's button or the app's "Hablar":
 * READY starts listening, LISTENING stops and sends, anything else is ignored. NEXT and long presses do nothing.
 */
internal object TalkButton {
    val TALK_KEYS = setOf(
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK,
    )

    fun forTap(stage: TurnStage): TalkAction = when (stage) {
        TurnStage.READY -> TalkAction.START
        TurnStage.LISTENING -> TalkAction.STOP_AND_SEND
        TurnStage.THINKING, TurnStage.SPEAKING -> TalkAction.IGNORE
    }

    /** Only the key-down of a first press counts (the key-up and auto-repeats of the same tap are ignored). */
    fun forKey(keyCode: Int, action: Int, repeat: Int, stage: TurnStage): TalkAction =
        if (action != KeyEvent.ACTION_DOWN || repeat > 0 || keyCode !in TALK_KEYS) TalkAction.IGNORE else forTap(stage)
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
