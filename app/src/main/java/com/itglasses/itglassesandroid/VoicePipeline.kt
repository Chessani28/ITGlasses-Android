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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
 * open while the service waits for the next tap.
 *
 * TODO(unificar): this class duplicates on purpose the voice logic of MainActivity (recording loop, transcribeWavRaw
 * with the Whisper → Vosk retry, chatRequest/answerOf, synthesizeSpeech, playAudio) so that the demo-lentes-v1 mode is
 * not touched. Unify both into one class later, with MainActivity using it too.
 */
internal class VoicePipeline(context: Context, private val pairing: DevicePairing, private val glasses: GlassesAudio) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var turn: Job? = null
    @Volatile private var stopRequested = false
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

    /** A tap (temple, notification or the app's "Hablar"): start listening, or stop and send. */
    @Synchronized
    fun tap() {
        val stage = BackgroundTurns.stage.value
        when (TalkButton.forTap(stage)) {
            TalkAction.START -> if (turn?.isActive != true) {
                stopRequested = false
                turn = scope.launch { runTurn() }
            }
            TalkAction.STOP_AND_SEND -> stopRequested = true
            TalkAction.IGNORE -> GlassesLog.d("background tap ignored stage=${stage.name}")
        }
    }

    fun shutdown() {
        stopRequested = true
        scope.cancel()
        releasePlayer()
        glasses.release()
        BackgroundTurns.stage.value = TurnStage.READY
    }

    private suspend fun runTurn() {
        val useGlasses = GlassesSettings.enabled(app)
        var routed = false
        try {
            BackgroundTurns.stage.value = TurnStage.LISTENING
            routed = useGlasses && glasses.acquire()
            GlassesLog.d("background turn start glasses=$useGlasses routed=$routed")
            VoiceCues.play(VoiceCues.Cue.LISTENING, routed)
            delay(VoiceCues.Cue.LISTENING.ms + CUE_GAP_MS)  // The tone never reaches the recording.
            val pcm = record()
            if (pcm.isEmpty()) {
                fail(R.string.background_no_speech, routed)
                return
            }
            BackgroundTurns.stage.value = TurnStage.THINKING
            VoiceCues.play(VoiceCues.Cue.THINKING, routed)
            val text = transcribe(WavCodec.encodePcmToWav(pcm, SAMPLE_RATE, 1, 16))
            if (text == null) {
                fail(R.string.background_no_speech, routed)
                return
            }
            BackgroundTurns.updates.tryEmit(TurnUpdate.Asked(text))
            val (answer, speech) = ask(text)
            BackgroundTurns.updates.tryEmit(TurnUpdate.Answered(answer))
            val audio = synthesize(SpokenAnswer.of(answer, speech))
            BackgroundTurns.stage.value = TurnStage.SPEAKING
            if (audio.isNotEmpty()) play(audio, routed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val status = (e as? HttpFailure)?.status?.toString() ?: "none"
            GlassesLog.d("background turn result=error type=${GlassesLog.safe(e.javaClass.simpleName)} status=$status")
            fail(R.string.background_failed, routed)
        } finally {
            releasePlayer()
            glasses.release()
            BackgroundTurns.stage.value = TurnStage.READY
            GlassesLog.d("background turn end")
        }
    }

    /** Error tone (before the route is released, so it is heard in the glasses) and the notice for the app. */
    private suspend fun fail(text: Int, routed: Boolean) {
        BackgroundTurns.updates.tryEmit(TurnUpdate.Failed(app.getString(text)))
        VoiceCues.play(VoiceCues.Cue.ERROR, routed)
        delay(VoiceCues.Cue.ERROR.ms + CUE_GAP_MS)
    }

    /** Until the end of speech, a second tap or the 30 s limit. Empty if nothing could be recorded. */
    @SuppressLint("MissingPermission")  // Checked just below.
    private fun record(): ByteArray {
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
                    if (vad.onBlock(VoiceLevel.of(samples, read), EndOfSpeech.blockMs(read))) {
                        GlassesLog.d("background vad end ms=${SystemClock.elapsedRealtime() - started}")
                        break
                    }
                }
                if (VoiceTiming.reachedMax(SystemClock.elapsedRealtime() - started)) break
            }
        } finally {
            try { active.stop() } catch (_: IllegalStateException) { }
            active.release()
        }
        return out.toByteArray()
    }

    /** Whisper first, one retry with Vosk after a 503 (SttEngine). Null when nothing usable was recognized. */
    private fun transcribe(wav: ByteArray): String? {
        val body = wav.toRequestBody("audio/wav".toMediaType())
        var engine: String? = SttEngine.first(true)
        while (true) {
            val builder = Request.Builder().url(pairing.origin + TRANSCRIBE_ENDPOINT).post(body)
            engine?.let { builder.header(SttEngine.HEADER, it) }
            val used = engine
            client.newCall(builder.build()).execute().use { response ->
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
        return client.newCall(request).execute().use { response ->
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
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw CallHttpException(response.code)
            response.body?.bytes() ?: byteArrayOf()
        }
    }

    /** Plays the answer and waits until it ends (or fails). With the route: call audio, to the glasses. */
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
        GlassesLog.d("background playback route=${if (routed) "bluetooth" else "phone"}")
        suspendCancellableCoroutine { done ->
            media.setOnCompletionListener { if (done.isActive) done.resume(Unit) }
            media.setOnErrorListener { _, _, _ ->
                if (done.isActive) done.resume(Unit)
                true
            }
            done.invokeOnCancellation { try { media.stop() } catch (_: IllegalStateException) { } }
            media.start()
        }
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
