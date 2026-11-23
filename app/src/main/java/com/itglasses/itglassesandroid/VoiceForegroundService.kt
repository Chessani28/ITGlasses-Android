package com.itglasses.itglassesandroid

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
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
 * VoicePipeline. While it runs it claims the media buttons (one tap = Hablar / Detener y enviar); when it stops, the
 * taps control music again. If it cannot go to the foreground it stops and the app keeps its usual mode.
 */
class VoiceForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pipeline: VoicePipeline? = null
    private var glasses: GlassesAudio? = null
    private var session: MediaSession? = null

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
            stopSelf()
            return START_NOT_STICKY
        }
        if (!goForeground()) return START_NOT_STICKY
        if (pipeline == null && !prepare()) return START_NOT_STICKY
        if (intent?.action == ACTION_TALK) pipeline?.tap()
        return START_NOT_STICKY  // Never restarted from the background: the microphone would be refused.
    }

    override fun onDestroy() {
        pipeline?.shutdown()
        pipeline = null
        glasses?.release()
        glasses = null
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
        val pairing = PairingAccess.get(applicationContext, BuildConfig.PAIRING_ORIGIN)
        if (!pairing.configured || !pairing.managed) {
            GlassesLog.d("background service start result=not_paired")
            BackgroundVoice.set(this, false)
            BackgroundTurns.startFailures.tryEmit(Unit)
            stopSelf()
            return false
        }
        val route = GlassesAudio(this)
        glasses = route
        pipeline = VoicePipeline(this, pairing, route)
        claimMediaButtons()
        BackgroundTurns.running.value = true
        scope.launch { BackgroundTurns.stage.collect { stage -> update(stage) } }
        GlassesLog.d("background service start result=ok")
        return true
    }

    /** One tap on the temple = Hablar / Detener y enviar. NEXT and other keys are consumed and do nothing (for now). */
    private fun claimMediaButtons() {
        val created = try { MediaSession(this, SESSION_TAG) } catch (e: RuntimeException) {
            GlassesLog.d("background media_session result=error type=${GlassesLog.safe(e.javaClass.simpleName)}")
            return
        }
        created.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val event = keyEvent(mediaButtonIntent) ?: return false
                val action = TalkButton.forKey(event.keyCode, event.action, event.repeatCount, BackgroundTurns.stage.value)
                if (event.action == KeyEvent.ACTION_DOWN) {
                    GlassesLog.d("background ${MediaButtons.describe(event.keyCode, event.action, event.repeatCount)} " +
                        "result=${action.name}")
                }
                if (action != TalkAction.IGNORE) pipeline?.tap()
                return true
            }
        })
        created.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
            .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
            .build())
        created.isActive = true
        session = created
        GlassesLog.d("background media_session result=ok silence=${MediaClaim.playSilence()}")
    }

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
        val talkLabel = if (stage == TurnStage.LISTENING) R.string.background_action_send else R.string.background_action_talk
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
        if (stage == TurnStage.READY || stage == TurnStage.LISTENING) {
            builder.addAction(NotificationCompat.Action.Builder(0, getString(talkLabel), command(ACTION_TALK, 1))
                .setAuthenticationRequired(false).build())
        }
        builder.addAction(NotificationCompat.Action.Builder(0, getString(R.string.background_action_off), command(ACTION_SHUTDOWN, 2))
            .setAuthenticationRequired(false).build())
        return builder.build()
    }

    private fun command(action: String, request: Int): PendingIntent = PendingIntent.getService(this, request,
        Intent(this, VoiceForegroundService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun keyEvent(intent: Intent): KeyEvent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)

    companion object {
        const val ACTION_START = "com.itglasses.itglassesandroid.background.START"
        const val ACTION_TALK = "com.itglasses.itglassesandroid.background.TALK"
        const val ACTION_SHUTDOWN = "com.itglasses.itglassesandroid.background.SHUTDOWN"
        private const val CHANNEL = "itglasses_voice"
        private const val NOTIFICATION_ID = 4107
        private const val SESSION_TAG = "ITGlassesVoice"

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
                context.startService(Intent(context, VoiceForegroundService::class.java).setAction(ACTION_TALK))
            } catch (e: Exception) {
                GlassesLog.d("background talk result=error type=${GlassesLog.safe(e.javaClass.simpleName)}")
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
