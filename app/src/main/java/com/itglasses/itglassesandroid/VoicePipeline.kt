package com.itglasses.itglassesandroid

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Background voice turn for VoiceForegroundService (screen locked, app closed): record with automatic end of speech,
 * transcribe, ask the assistant, synthesize and play. One turn at a time.
 *
 * The Bluetooth route (SCO) is opened at the start of each turn and always released in its `finally`: it is never
 * open while the service waits for the next tap. [onTurnEnd] runs after every turn (the service then ignores media
 * buttons for a moment: the glasses send PLAY by themselves when the call route closes).
 *
 * A turn stops recording at the end of speech, on a tap, after BackgroundRecording.NO_SPEECH_TIMEOUT_MS without speech
 * (cancelled in silence, nothing sent) or at BackgroundRecording.MAX_RECORDING_MS. [cancel] drops the turn at any
 * stage: nothing more is sent or played, with the "cancelado" tone.
 *
 * TODO(unificar): this class duplicates on purpose the voice logic of MainActivity (recording loop, transcribeWavRaw
 * with the Whisper → Vosk retry, chatRequest/answerOf, synthesizeSpeech, playAudio) so that the demo-lentes-v1 mode is
 * not touched. Unify both into one class later, with MainActivity using it too.
 */
internal class VoicePipeline(
    context: Context,
    private val pairing: DevicePairing,
    private val glasses: GlassesAudio,
    private val onTurnEnd: () -> Unit = {},
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var turn: Job? = null
    @Volatile private var stopRequested = false
    @Volatile private var cancelRequested = false
    @Volatile private var call: Call? = null
    @Volatile private var playbackDone: (() -> Unit)? = null
    /** Assistant session of the background turns (in memory, like the app's own). */
    @Volatile private var session: String? = null
    private var player: MediaPlayer? = null

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .addInterceptor(BusyRetryInterceptor())
        .addInterceptor { chain -> pairing.authorize(chain) }
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    private class Cancelled : Exception()

    /** A tap (temple, notification or the app's "Hablar"): start listening, or stop and send. [source] is logged. */
    @Synchronized
    fun tap(source: String) {
        val stage = BackgroundTurns.stage.value
        when (TalkButton.forTap(stage)) {
            TalkAction.START -> if (turn?.isActive != true) {
                stopRequested = false
                cancelRequested = false
                turn = scope.launch { runTurn(source) }
            }
            TalkAction.STOP_AND_SEND -> {
                GlassesLog.d("background stop_and_send source=${GlassesLog.safe(source)}")
                stopRequested = true
            }
            else -> GlassesLog.d("background tap ignored stage=${stage.name} source=${GlassesLog.safe(source)}")
        }
    }

    /** Swipe on the temple or "Cancelar": drop the turn on its way; nothing more is sent or played. */
    @Synchronized
    fun cancel(source: String) {
        val stage = BackgroundTurns.stage.value
        if (TalkButton.forCancel(stage) != TalkAction.CANCEL || turn?.isActive != true) {
            GlassesLog.d("background cancel ignored stage=${stage.name} source=${GlassesLog.safe(source)}")
            return
        }
        GlassesLog.d("background cancel stage=${stage.name} source=${GlassesLog.safe(source)}")
        cancelRequested = true
        stopRequested = true
        call?.cancel()
        playbackDone?.invoke()
    }

    fun shutdown() {
        cancelRequested = true
        stopRequested = true
        call?.cancel()
        playbackDone?.invoke()
        scope.cancel()
        releasePlayer()
        glasses.release()
        BackgroundTurns.stage.value = TurnStage.READY
    }

    private fun checkpoint() {
        if (cancelRequested) throw Cancelled()
    }

    private suspend fun runTurn(source: String) {
        val useGlasses = GlassesSettings.enabled(app)
        var routed = false
        try {
            BackgroundTurns.stage.value = TurnStage.LISTENING
            routed = useGlasses && glasses.acquire()
            GlassesLog.d("background turn start source=${GlassesLog.safe(source)} glasses=$useGlasses routed=$routed")
            checkpoint()
            VoiceCues.play(VoiceCues.Cue.LISTENING, routed)
            delay(VoiceCues.Cue.LISTENING.ms + CUE_GAP_MS)  // The tone never reaches the recording.
            val pcm = record()
            checkpoint()
            if (pcm == null) return  // No speech in time: cancelled in silence (logged in record()).
            if (pcm.isEmpty()) {
                fail(R.string.background_no_speech, routed)
                return
            }
            BackgroundTurns.stage.value = TurnStage.THINKING
            VoiceCues.play(VoiceCues.Cue.THINKING, routed)
            val text = transcribe(WavCodec.encodePcmToWav(pcm, SAMPLE_RATE, 1, 16))
            checkpoint()
            if (text == null) {
                fail(R.string.background_no_speech, routed)
                return
            }
            BackgroundTurns.updates.tryEmit(TurnUpdate.Asked(text))
            val (answer, speech) = ask(text)
            checkpoint()
            BackgroundTurns.updates.tryEmit(TurnUpdate.Answered(answer))
            val audio = synthesize(SpokenAnswer.of(answer, speech))
            checkpoint()
            BackgroundTurns.stage.value = TurnStage.SPEAKING
            if (audio.isNotEmpty()) play(audio, routed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cancelRequested) {
                cancelled(routed)
            } else {
                val status = (e as? HttpFailure)?.status?.toString() ?: "none"
                GlassesLog.d("background turn result=error type=${GlassesLog.safe(e.javaClass.simpleName)} status=$status")
                fail(R.string.background_failed, routed)
            }
        } finally {
            call = null
            playbackDone = null
            releasePlayer()
            glasses.release()
            BackgroundTurns.stage.value = TurnStage.READY
            GlassesLog.d("background turn end cancelled=$cancelRequested")
            onTurnEnd()
        }
    }

    /** Error tone (before the route is released, so it is heard in the glasses) and the notice for the app. */
    private suspend fun fail(text: Int, routed: Boolean) {
        BackgroundTurns.updates.tryEmit(TurnUpdate.Failed(app.getString(text)))
        VoiceCues.play(VoiceCues.Cue.ERROR, routed)
        delay(VoiceCues.Cue.ERROR.ms + CUE_GAP_MS)
    }

    private suspend fun cancelled(routed: Boolean) {
        GlassesLog.d("background turn result=cancelled")
        BackgroundTurns.updates.tryEmit(TurnUpdate.Failed(app.getString(R.string.background_cancelled)))
        VoiceCues.play(VoiceCues.Cue.CANCELLED, routed)
        delay(VoiceCues.Cue.CANCELLED.ms + CUE_GAP_MS)
    }

    /**
     * Until the end of speech, a tap, or a BackgroundRecording limit. Null: no speech in time (cancel in silence).
     * Empty: nothing could be recorded.
     */
    @SuppressLint("MissingPermission")  // Checked just below.
    private fun record(): ByteArray? {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("microphone permission")
        }
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channel, encoding)
        if (bufferSize <= 0) throw IllegalStateException("microphone format")
        var record: AudioRecord? = null
        for (source in MicSources.order) {
            val candidate = try { AudioRecord(source, SAMPLE_RATE, channel, encoding, bufferSize) } catch (_: Exception) { null }
            if (candidate == null || candidate.state != AudioRecord.STATE_INITIALIZED) {
                candidate?.release()
                continue
            }
            glasses.input?.let { candidate.setPreferredDevice(it) }
            val recording = try {
                candidate.startRecording()
                candidate.recordingState == AudioRecord.RECORDSTATE_RECORDING
            } catch (_: IllegalStateException) { false }
            if (!recording) {
                candidate.release()
                continue
            }
            record = candidate
            GlassesLog.d("background record start mic=${MicSources.name(source)} preferred=${glasses.input?.type ?: "none"}")
            break
        }
        val active = record ?: throw IllegalStateException("microphone unavailable")
        val vad = EndOfSpeech()
        val out = ByteArrayOutputStream()
        val samples = ShortArray(bufferSize / 2)
        val bytes = ByteArray(samples.size * 2)
        val started = SystemClock.elapsedRealtime()
        // Diagnostics only (log): real audio after the first non-zero block, and digital silence before it.
        var audioMs = 0L
        var zeroMs = 0L
        var flowing = false
        var result = "stopped"
        try {
            while (!stopRequested) {
                val read = active.read(samples, 0, samples.size)
                if (read < 0) break
                if (read > 0) {
                    for (i in 0 until read) {
                        bytes[2 * i] = (samples[i].toInt() and 0xFF).toByte()
                        bytes[2 * i + 1] = (samples[i].toInt() shr 8 and 0xFF).toByte()
                    }
                    out.write(bytes, 0, read * 2)
                    val blockMs = EndOfSpeech.blockMs(read)
                    if (!flowing && BackgroundRecording.digitalSilence(samples, read)) zeroMs += blockMs else flowing = true
                    if (flowing) audioMs += blockMs
                    if (vad.onBlock(VoiceLevel.of(samples, read), blockMs)) {
                        GlassesLog.d("background vad end ms=${SystemClock.elapsedRealtime() - started}")
                        result = "vad_end"
                        break
                    }
                }
                val elapsed = SystemClock.elapsedRealtime() - started
                when (BackgroundRecording.stop(elapsed, vad.heard)) {
                    RecordingStop.NO_SPEECH -> {
                        GlassesLog.d(BackgroundRecording.describe("no_speech", elapsed, audioMs, zeroMs, vad))
                        return null
                    }
                    RecordingStop.MAX_DURATION -> {
                        result = "max_duration"
                        break
                    }
                    null -> Unit
                }
            }
            GlassesLog.d(BackgroundRecording.describe(result, SystemClock.elapsedRealtime() - started, audioMs, zeroMs, vad))
        } finally {
            try { active.stop() } catch (_: IllegalStateException) { }
            active.release()
        }
        return out.toByteArray()
    }

    /** One HTTP call that [cancel] can interrupt. */
    private fun execute(request: Request): Response {
        checkpoint()
        val current = client.newCall(request)
        call = current
        try {
            return current.execute()
        } finally {
            call = null
        }
    }

    /** Whisper first, one retry with Vosk after a 503 (SttEngine). Null when nothing usable was recognized. */
    private fun transcribe(wav: ByteArray): String? {
        val body = wav.toRequestBody("audio/wav".toMediaType())
        var engine: String? = SttEngine.first(true)
        while (true) {
            val builder = Request.Builder().url(pairing.origin + TRANSCRIBE_ENDPOINT).post(body)
            engine?.let { builder.header(SttEngine.HEADER, it) }
            val used = engine
            execute(builder.build()).use { response ->
                if (!response.isSuccessful) {
                    val retry = SttEngine.fallbackAfter(used, response.code) ?: throw CallHttpException(response.code)
                    engine = retry
                    return@use
                }
                val json = JSONObject(response.body?.string() ?: "")
                val text = json.optString("text", "").trim()
                return if (text.isEmpty() || json.optBoolean("needs_clarification", false)) null else text
            }
        }
    }

    /** The answer and the backend's `speech` (what to read aloud), in the background session. */
    private fun ask(message: String): Pair<String, String?> {
        val json = JSONObject().put("message", message)
        session?.let { json.put("session_id", it) }
        val request = Request.Builder().url(pairing.origin + ASSISTANT_ENDPOINT)
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()
        return execute(request).use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) throw CallHttpException(response.code)
            val reply = JSONObject(body)
            val returned = if (reply.isNull("session_id")) null else reply.optString("session_id")
            session = AssistantSession.next(session, returned)
            val speech = if (reply.isNull("speech")) null else reply.optString("speech")
            reply.optString("answer", body) to speech
        }
    }

    /** Same request as the app: Piper voice es_ES-sharvard-medium, speaker 0, normal speed. */
    private fun synthesize(text: String): ByteArray {
        val json = JSONObject().put("text", text).put("voice", "es_ES-sharvard-medium").put("speaker", 0).put("speed", 1.0)
        val request = Request.Builder().url(pairing.origin + SYNTHESIZE_ENDPOINT)
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()
        return execute(request).use { response ->
            if (!response.isSuccessful) throw CallHttpException(response.code)
            response.body?.bytes() ?: byteArrayOf()
        }
    }

    /** Plays the answer and waits until it ends, fails or is cancelled. With the route: call audio, to the glasses. */
    private suspend fun play(wav: ByteArray, routed: Boolean) {
        val file = File(app.cacheDir, "background-response.wav")
        file.writeBytes(wav)
        val media = MediaPlayer()
        player = media
        media.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(if (routed) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build())
        media.setDataSource(file.absolutePath)
        if (Build.VERSION.SDK_INT >= 28) glasses.output?.let { media.setPreferredDevice(it) }
        media.prepare()
        checkpoint()
        GlassesLog.d("background playback route=${if (routed) "bluetooth" else "phone"}")
        suspendCancellableCoroutine { done ->
            val finish = { if (done.isActive) done.resume(Unit) }
            playbackDone = {
                try { media.stop() } catch (_: IllegalStateException) { }
                finish()
            }
            media.setOnCompletionListener { finish() }
            media.setOnErrorListener { _, _, _ ->
                finish()
                true
            }
            done.invokeOnCancellation { try { media.stop() } catch (_: IllegalStateException) { } }
            media.start()
        }
        checkpoint()  // Cancelled while speaking: the "cancelado" tone.
    }

    private fun releasePlayer() {
        val media = player ?: return
        player = null
        try { media.stop() } catch (_: IllegalStateException) { }
        media.release()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CUE_GAP_MS = 80L
        const val TRANSCRIBE_ENDPOINT = "/api/v1/voice/transcribe"
        const val ASSISTANT_ENDPOINT = "/api/v1/assistant/chat"
        const val SYNTHESIZE_ENDPOINT = "/api/v1/voice/synthesize"
    }
}
