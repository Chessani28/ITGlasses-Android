package com.itglasses.itglassesandroid

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.view.KeyEvent

/**
 * Prueba de la patilla, fase A (diagnóstico, solo debug): ¿llega el toque en la patilla de los lentes a la app como
 * botón multimedia? Una MediaSession de la plataforma (sin dependencias) que SOLO registra en logcat (ITG-Glasses)
 * el código de cada tecla. No inicia ninguna acción. Se crea solo con su interruptor encendido.
 *
 * Android entrega los botones multimedia a la sesión que reprodujo multimedia más recientemente; por eso, al empezar,
 * se reproduce medio segundo de silencio como multimedia para "reclamar" el botón.
 */
internal class MediaButtonProbe(context: Context) {
    private val app = context.applicationContext
    private var session: MediaSession? = null

    fun start() {
        if (session != null) return
        val created = try {
            MediaSession(app, SESSION_TAG)
        } catch (e: RuntimeException) {
            GlassesLog.d("probe start result=error type=${GlassesLog.safe(e.javaClass.simpleName)}")
            return
        }
        created.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val event = keyEvent(mediaButtonIntent)
                GlassesLog.d(if (event == null) "probe media_button key=none"
                    else MediaButtons.describe(event.keyCode, event.action, event.repeatCount))
                return true  // Fase A: consumida y registrada; no hace nada más.
            }
        })
        created.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
            .build())
        created.isActive = true
        session = created
        val claimed = playSilence()
        GlassesLog.d("probe start result=ok silence=$claimed api=${Build.VERSION.SDK_INT}")
    }

    fun release() {
        val current = session ?: return
        session = null
        try {
            current.isActive = false
            current.release()
        } catch (_: RuntimeException) {
        }
        GlassesLog.d("probe stop")
    }

    /** Half a second of silence as media, so this app becomes the most recent media player. */
    private fun playSilence(): Boolean = try {
        val rate = 16_000
        val silence = ShortArray(rate / 2)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(silence.size * 2)
            .build()
        track.write(silence, 0, silence.size)
        track.setNotificationMarkerPosition(silence.size)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack) { t.release() }
            override fun onPeriodicNotification(t: AudioTrack) {}
        })
        track.play()
        true
    } catch (e: Exception) {
        GlassesLog.d("probe silence result=error type=${GlassesLog.safe(e.javaClass.simpleName)}")
        false
    }

    private fun keyEvent(intent: Intent): KeyEvent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)

    companion object {
        private const val SESSION_TAG = "ITGlassesProbe"
        private const val PREFS = "itglasses_glasses"
        private const val KEY_ENABLED = "media_button_probe"

        fun enabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun set(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }
    }
}

/** Pure: the log line of a media button (numbers and known names only). */
internal object MediaButtons {
    private val NAMES = mapOf(
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "PLAY_PAUSE",
        KeyEvent.KEYCODE_MEDIA_PLAY to "PLAY",
        KeyEvent.KEYCODE_MEDIA_PAUSE to "PAUSE",
        KeyEvent.KEYCODE_MEDIA_STOP to "STOP",
        KeyEvent.KEYCODE_MEDIA_NEXT to "NEXT",
        KeyEvent.KEYCODE_MEDIA_PREVIOUS to "PREVIOUS",
        KeyEvent.KEYCODE_HEADSETHOOK to "HEADSETHOOK",
        KeyEvent.KEYCODE_VOICE_ASSIST to "VOICE_ASSIST",
        KeyEvent.KEYCODE_ASSIST to "ASSIST",
    )

    fun name(keyCode: Int): String = NAMES[keyCode] ?: "OTHER"

    fun describe(keyCode: Int, action: Int, repeat: Int): String {
        val phase = when (action) {
            KeyEvent.ACTION_DOWN -> "down"
            KeyEvent.ACTION_UP -> "up"
            else -> "other"
        }
        return "probe media_button key=$keyCode name=${name(keyCode)} action=$phase repeat=${repeat.coerceAtLeast(0)}"
    }
}
