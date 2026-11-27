package com.itglasses.itglassesandroid

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * "Escuchar con el celular bloqueado": a microphone foreground service with a persistent notification. It must be
 * started while the app is visible (Android forbids starting a microphone foreground service from the background);
 * once running, a tap on the glasses' temple, the notification's "Hablar" or the app's "Hablar" runs a turn in
 * VoicePipeline. While it runs it claims the media buttons (one tap = Hablar / Detener y enviar, swipe = Cancelar);
 * when it stops, the taps control music again. If it cannot go to the foreground it stops and the app keeps its usual
 * mode.
 *
 * ButtonGuard: the glasses send PLAY by themselves about 0.5 s after the call route closes (to resume music), and
 * when they reconnect. Media buttons are ignored for ButtonGuard.WINDOW_MS after the service starts, after each turn,
 * and after any Bluetooth device or communication route change. The notification's buttons are never blocked.
 */
class VoiceForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val guard = ButtonGuard({ SystemClock.elapsedRealtime() })
    private val main = Handler(Looper.getMainLooper())
    private var pipeline: VoicePipeline? = null
    private var glasses: GlassesAudio? = null
    private var session: MediaSession? = null
    private var audio: AudioManager? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private var routeListener: AudioManager.OnCommunicationDeviceChangedListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.background_channel))
                .build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHUTDOWN) {
            BackgroundVoice.set(this, false)  // "Apagar" stays off: the app does not restart it on its next start.
            stopSelf()
            return START_NOT_STICKY
        }
        if (!goForeground()) return START_NOT_STICKY
        if (pipeline == null && !prepare()) return START_NOT_STICKY
        val source = intent?.getStringExtra(EXTRA_SOURCE) ?: "intent"
        when (intent?.action) {
            ACTION_TALK -> pipeline?.tap(source)
            ACTION_CANCEL -> pipeline?.cancel(source)
            ACTION_RECLAIM -> reclaimMediaButtons(source)
        }
        return START_NOT_STICKY  // Never restarted from the background: the microphone would be refused.
    }

    override fun onDestroy() {
        pipeline?.shutdown()
        pipeline = null
        glasses?.release()
        glasses = null
        main.removeCallbacksAndMessages(null)
        stopWatchingRoutes()
        session?.let {
            try {
                it.isActive = false
                it.release()
            } catch (_: RuntimeException) {
            }
        }
        session = null
        scope.cancel()
        BackgroundTurns.running.value = false
        GlassesLog.d("background service stop")
        super.onDestroy()
    }

    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(BackgroundTurns.stage.value), type)
        true
    } catch (e: Exception) {
        // SecurityException, ForegroundServiceStartNotAllowedException…: back to the usual mode, nothing breaks.
        GlassesLog.d("background service start result=error type=${GlassesLog.safe(e.javaClass.simpleName)}")
        BackgroundVoice.set(this, false)
        BackgroundTurns.startFailures.tryEmit(Unit)
        stopSelf()
        false
    }

    private fun prepare(): Boolean {
        val pairing = PairingAccess.current(applicationContext)
        if (!pairing.configured || !pairing.managed) {
            GlassesLog.d("background service start result=not_paired")
            BackgroundVoice.set(this, false)
            BackgroundTurns.startFailures.tryEmit(Unit)
            stopSelf()
            return false
        }
        guard.block("service_start")
        val route = GlassesAudio(this)
        glasses = route
        pipeline = VoicePipeline(this, pairing, route, onTurnEnd = {
            guard.block("turn_end")
            // Take the temple tap back once the glasses have switched from the call route to music.
            main.postDelayed({ reclaimMediaButtons("turn_end") }, RECLAIM_AFTER_TURN_MS)
        })
        watchRoutes()
        claimMediaButtons()
        BackgroundTurns.running.value = true
        scope.launch { BackgroundTurns.stage.collect { stage -> update(stage) } }
        GlassesLog.d("background service start result=ok")
        return true
    }

    /** Bluetooth devices connecting or leaving, and communication route changes (ours included), arm the guard. */
    private fun watchRoutes() {
        val am = getSystemService(AudioManager::class.java) ?: return
        audio = am
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                if (added.any { it.type in BLUETOOTH_TYPES }) guard.block("bt_device_added")
            }

            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                if (removed.any { it.type in BLUETOOTH_TYPES }) guard.block("bt_device_removed")
            }
        }
        am.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        deviceCallback = callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val listener = AudioManager.OnCommunicationDeviceChangedListener { guard.block("route_change") }
            am.addOnCommunicationDeviceChangedListener(ContextCompat.getMainExecutor(this), listener)
            routeListener = listener
        }
    }

    private fun stopWatchingRoutes() {
        val am = audio ?: return
        deviceCallback?.let { try { am.unregisterAudioDeviceCallback(it) } catch (_: RuntimeException) { } }
        deviceCallback = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            routeListener?.let { try { am.removeOnCommunicationDeviceChangedListener(it) } catch (_: RuntimeException) { } }
        }
        routeListener = null
        audio = null
    }

    /** One tap on the temple = Hablar / Detener y enviar; swipe (NEXT) = Cancelar. Other keys do nothing. */
    private fun claimMediaButtons() {
        val created = try { MediaSession(this, SESSION_TAG) } catch (e: RuntimeException) {
            GlassesLog.d("background media_session result=error type=${GlassesLog.safe(e.javaClass.simpleName)}")
            return
        }
        created.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val event = keyEvent(mediaButtonIntent) ?: return false
                if (event.action != KeyEvent.ACTION_DOWN) return true
                val key = MediaButtons.describe(event.keyCode, event.action, event.repeatCount)
                val blocked = guard.blockedBy()
                if (blocked != null) {
                    GlassesLog.d("background $key result=IGNORED guard=$blocked")
                    return true
                }
                val action = TalkButton.forKey(event.keyCode, event.action, event.repeatCount, BackgroundTurns.stage.value)
                GlassesLog.d("background $key result=${action.name}")
                val source = "key:${event.keyCode}"
                when (action) {
                    TalkAction.START, TalkAction.STOP_AND_SEND -> pipeline?.tap(source)
                    TalkAction.CANCEL -> pipeline?.cancel(source)
                    TalkAction.IGNORE -> Unit
                }
                return true
            }
        })
        created.setPlaybackState(playingState())
        created.isActive = true
        session = created
        GlassesLog.d("background media_session result=ok silence=${MediaClaim.playSilence()}")
    }

    /**
     * Re-assert the session (active, playing, half a second of silence as media) so Android hands the media
     * buttons back to it after a video or music app, or the diagnostic probe, took them. Main thread.
     */
    private fun reclaimMediaButtons(reason: String) {
        val current = session
        if (current == null) {
            claimMediaButtons()
            return
        }
        try {
            current.isActive = true
            current.setPlaybackState(playingState())
        } catch (e: RuntimeException) {
            GlassesLog.d("background reclaim result=error type=${GlassesLog.safe(e.javaClass.simpleName)}")
            return
        }
        GlassesLog.d("background reclaim reason=${GlassesLog.safe(reason)} silence=${MediaClaim.playSilence()}")
    }

    private fun playingState(): PlaybackState = PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT)
        .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
        .build()

    private fun update(stage: TurnStage) {
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(stage))
        } catch (_: SecurityException) {
            // Notifications not allowed: the service and the temple tap keep working.
        }
    }

    private fun notification(stage: TurnStage): Notification {
        val text = when (stage) {
            TurnStage.READY -> R.string.background_ready
            TurnStage.LISTENING -> R.string.background_listening
            TurnStage.THINKING -> R.string.background_thinking
            TurnStage.SPEAKING -> R.string.background_speaking
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.background_title))
            .setContentText(getString(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        when (stage) {
            TurnStage.READY -> builder.addAction(action(R.string.background_action_talk, ACTION_TALK, 1))
            TurnStage.LISTENING -> {
                builder.addAction(action(R.string.background_action_send, ACTION_TALK, 1))
                builder.addAction(action(R.string.background_action_cancel, ACTION_CANCEL, 3))
            }
            TurnStage.THINKING, TurnStage.SPEAKING -> builder.addAction(action(R.string.background_action_cancel, ACTION_CANCEL, 3))
        }
        builder.addAction(action(R.string.background_action_off, ACTION_SHUTDOWN, 2))
        return builder.build()
    }

    private fun action(label: Int, intentAction: String, request: Int) =
        NotificationCompat.Action.Builder(0, getString(label), command(intentAction, request))
            .setAuthenticationRequired(false).build()

    private fun command(action: String, request: Int): PendingIntent = PendingIntent.getService(this, request,
        Intent(this, VoiceForegroundService::class.java).setAction(action).putExtra(EXTRA_SOURCE, "notification"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun keyEvent(intent: Intent): KeyEvent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)

    companion object {
        const val ACTION_START = "com.itglasses.itglassesandroid.background.START"
        const val ACTION_TALK = "com.itglasses.itglassesandroid.background.TALK"
        const val ACTION_CANCEL = "com.itglasses.itglassesandroid.background.CANCEL"
        const val ACTION_SHUTDOWN = "com.itglasses.itglassesandroid.background.SHUTDOWN"
        const val ACTION_RECLAIM = "com.itglasses.itglassesandroid.background.RECLAIM"
        /** After a turn, wait for the glasses to leave the call route before re-asserting the session. */
        private const val RECLAIM_AFTER_TURN_MS = 1000L
        private const val EXTRA_SOURCE = "source"
        private const val CHANNEL = "itglasses_voice"
        private const val NOTIFICATION_ID = 4107
        private const val SESSION_TAG = "ITGlassesVoice"
        /** Classic Bluetooth (SCO, A2DP) and LE Audio headsets (TYPE_BLE_HEADSET = 26). */
        private val BLUETOOTH_TYPES = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26)

        /** Only while the app is visible. False when Android refuses it (the app keeps its usual mode). */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, VoiceForegroundService::class.java).setAction(ACTION_START))
            true
        } catch (e: Exception) {
            GlassesLog.d("background service request result=error type=${GlassesLog.safe(e.javaClass.simpleName)}")
            false
        }

        /** The app's "Hablar" while the service runs (only one microphone user at a time). */
        fun talk(context: Context) {
            if (!BackgroundTurns.running.value) return
            try {
                context.startService(Intent(context, VoiceForegroundService::class.java).setAction(ACTION_TALK)
                    .putExtra(EXTRA_SOURCE, "app"))
            } catch (e: Exception) {
                GlassesLog.d("background talk result=error type=${GlassesLog.safe(e.javaClass.simpleName)}")
            }
        }

        /** Back to the app, or the probe turned off: take the temple tap back (only while the service runs). */
        fun reclaim(context: Context, source: String) {
            if (!BackgroundTurns.running.value) return
            try {
                context.startService(Intent(context, VoiceForegroundService::class.java).setAction(ACTION_RECLAIM)
                    .putExtra(EXTRA_SOURCE, source))
            } catch (e: Exception) {
                GlassesLog.d("background reclaim request result=error type=${GlassesLog.safe(e.javaClass.simpleName)}")
            }
        }

        fun shutdown(context: Context) {
            if (!BackgroundTurns.running.value) return
            try {
                context.startService(Intent(context, VoiceForegroundService::class.java).setAction(ACTION_SHUTDOWN))
            } catch (_: Exception) {
            }
        }
    }
}
