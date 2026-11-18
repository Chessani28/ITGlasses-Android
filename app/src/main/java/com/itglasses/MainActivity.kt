package com.itglasses.itglassesandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.itglasses.itglassesandroid.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var permissionLauncher: ActivityResultLauncher<String>
    private lateinit var wearablesIntegration: WearablesIntegration

    private val pairing by lazy { DevicePairing(applicationContext, BuildConfig.PAIRING_ORIGIN) }
    private val pairingController by lazy {
        PairingController(
            DevicePairingGateway(pairing, { DeviceInfo.name(applicationContext) }, DeviceInfo::model),
            PairingMachine.start(pairing.configured, pairing.id.isNotEmpty(), pairing.managed,
                pairing.code.takeIf { it.isNotEmpty() }?.let { PairingState.Pending(it, pairing.expires) }),
        )
    }
    // Pairing mode: failed chat and voice calls -> fixed Spanish notices (never the exception or the body).
    private val callErrors = CallErrors()
    private val notice = TransientNotice()
    private var noticeJob: kotlinx.coroutines.Job? = null
    // Stage A: pairing card tone (dot and border colour) and the dot's pulse.
    private val pulse by lazy { Pulse(binding.statusDot) }
    private val dotColor by lazy {
        ColorTransition(this) { binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(it) }
    }
    private val strokeColor by lazy { ColorTransition(this) { binding.cardPairing.strokeColor = it } }
    // Shared-token mode only for an APK without pairing origin; with one, the stored token is kept but never sent.
    private val legacyAuth = AuthInterceptor { activeToken }
    private val backendUrl: String?
        get() = if (pairing.configured) pairing.origin else BackendConfiguration.getBaseUrl(this)
    private val STATUS_ENDPOINT = "/api/v1/status"
    private val CHAT_ENDPOINT = "/api/v1/chat"
    private val TRANSCRIBE_ENDPOINT = "/api/v1/voice/transcribe"
    private val SYNTHESIZE_ENDPOINT = "/api/v1/voice/synthesize"

    @Volatile private var activeToken: String? = null

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                if (pairing.configured) {
                    // OkHttp thread: only queues work for the controller (403 -> status check, 410 -> register again).
                    try {
                        pairing.authorize(chain).also { response -> pairingController.onCallResponse(response.code) }
                    } catch (e: PairingError) {
                        pairingController.onCallResponse(e.status)
                        throw e
                    }
                } else legacyAuth.intercept(chain)
            }
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    // Microphone recording state
    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private var recordingThread: Thread? = null
    private val recordedPcm = ByteArrayOutputStream()
    private var recordingStartMs = 0L
    private val MAX_DURATION_MS = 30_000L

    // Voice pipeline processing state. Guards against duplicate submissions while a
    // recording flows through transcribe → Ollama → Piper → playback.
    @Volatile private var isProcessing = false

    private enum class UiState {
        Idle, Recording, Transcribing, Thinking, Synthesizing, Speaking, Ready, Error
    }
    @Volatile private var uiState = UiState.Idle

    private var mediaPlayer: MediaPlayer? = null

    private fun setState(state: UiState) {
        uiState = state
        runOnUiThread {
            val busy = state != UiState.Idle && state != UiState.Ready && state != UiState.Error
            binding.btnVoice.isEnabled = !busy && voiceAllowed()
            binding.tvStatus.text = when (state) {
                UiState.Recording -> getString(R.string.voice_state_recording, 0)
                UiState.Transcribing -> getString(R.string.voice_state_transcribing)
                UiState.Thinking -> getString(R.string.voice_state_thinking)
                UiState.Synthesizing -> getString(R.string.voice_state_synthesizing)
                UiState.Speaking -> getString(R.string.voice_state_speaking)
                UiState.Ready -> getString(R.string.voice_state_ready)
                UiState.Error -> getString(R.string.voice_state_error)
                UiState.Idle -> binding.tvStatus.text.toString()
            }
        }
    }

    override fun onDestroy() {
        if (::wearablesIntegration.isInitialized) wearablesIntegration.onDestroy()
        super.onDestroy()
        releaseMediaPlayer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startRecording()
            } else {
                updateStatus(getString(R.string.voice_mic_denied))
            }
        }
        activeToken = BackendConfiguration.getStoredToken(this)
        val initialUrl = BackendConfiguration.getBaseUrl(this)
        binding.tvBackendUrl.text = initialUrl?.let { getString(R.string.backend_url, it) }
            ?: getString(R.string.backend_url_not_configured)
        binding.etBackendUrl.setText(initialUrl ?: "")
        binding.etApiToken.setText(activeToken ?: "")

        binding.btnConnect.setOnClickListener {
            val urlInput = binding.etBackendUrl.text?.toString()?.trim() ?: ""
            val tokenInput = binding.etApiToken.text?.toString()?.trim() ?: ""

            if (urlInput.isNotEmpty()) {
                val success = BackendConfiguration.setCustomUrl(this, urlInput)
                if (!success) {
                    updateStatus("Invalid backend URL. Only private LAN or loopback addresses allowed in debug.")
                    return@setOnClickListener
                }
            } else {
                BackendConfiguration.setCustomUrl(this, "")
            }

            BackendConfiguration.saveToken(this, tokenInput)
            activeToken = tokenInput.takeIf { it.isNotEmpty() }
            binding.tvBackendUrl.text = backendUrl?.let { getString(R.string.backend_url, it) }
                ?: getString(R.string.backend_url_not_configured)
            connectToBackend()
        }
        binding.btnDisconnect.setOnClickListener { disconnectFromBackend() }
        binding.btnSend.setOnClickListener { sendMessage() }
        binding.btnVoice.setOnClickListener { toggleRecording() }
        wearablesIntegration = WearablesIntegrationFactory.create(this, binding)
        wearablesIntegration.onCreate(intent)
        binding.btnMetaRegister.setOnClickListener { wearablesIntegration.register() }
        binding.btnMetaSession.setOnClickListener { wearablesIntegration.startSession() }
        binding.btnMetaStop.setOnClickListener { wearablesIntegration.stopSession() }
        binding.tvTranscription.text = getString(R.string.transcription_pending)
        binding.tvAiResponse.text = ""

        // Without a pairing origin the controller is never created: shared-token mode as before.
        renderPairing(if (pairing.configured) pairingController.state.value else PairingState.Unconfigured)
        if (pairing.configured) {
            binding.tvStatus.text = null  // No "Loading..." in pairing mode: the card says how things stand.
            binding.btnPair.setOnClickListener { pairingController.onEvent(PairingEvent.UserRetry) }
            // Pairing runs only while the app is visible: repeatOnLifecycle cancels it on onStop.
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch { pairingController.run() }
                    launch { pairingController.state.collect { renderPairing(it) } }
                    launch {
                        // No looping animation outside the foreground; the next render restarts it.
                        try { awaitCancellation() } finally { pulse.stop() }
                    }
                    launch {
                        // Result of the status check after a 403 (drop: do not repeat an old one on resume).
                        pairingController.callCheck.drop(1).collect { check ->
                            if (check == CallCheck.UNAVAILABLE || check == CallCheck.RESOLVED) updateStatus(callCheckMessage(check))
                        }
                    }
                    launch {
                        while (true) {
                            delay(1000)
                            val state = pairingController.state.value
                            if (state is PairingState.Pending) renderPairing(state)
                        }
                    }
                }
            }
        } else connectToBackend()
    }

    /** Draws the pairing card. The countdown uses server time, because expires_at is server time. */
    private fun renderPairing(state: PairingState) {
        val view = PairingScreen.view(state, pairing.clock.serverNow(System.currentTimeMillis()), DeviceInfo.model())
        val legacy = if (view.sharedTokenMode) android.view.View.VISIBLE else android.view.View.GONE
        for (field in listOf(binding.etBackendUrl, binding.etApiToken, binding.btnConnect,
            binding.btnDisconnect, binding.tvBackendUrl)) field.visibility = legacy
        binding.btnSend.isEnabled = view.canChat
        binding.btnVoice.isEnabled = view.canChat && !isProcessing
        showPairingText(binding.tvPairingTitle, view.title?.let { pairingText(it) })
        showPairingText(binding.tvPairingCode, view.code)
        binding.tvPairingCode.contentDescription = view.spokenCode?.let { pairingText(PairingText.CODE_SPOKEN, it) }
        showPairingText(binding.tvPairing, view.detail?.let { pairingText(it, view.detailArg) })
        showPairingText(binding.tvPairingNote, view.note?.let { pairingText(it, view.noteArg) })
        showPairingText(binding.btnPair, view.button?.let { pairingText(it) })
        // Tone: the dot only when there is a title (none in shared-token mode); colour change of dot and border.
        val dotShown = view.title != null
        binding.statusDot.visibility = if (dotShown) android.view.View.VISIBLE else android.view.View.GONE
        dotColor.to(ContextCompat.getColor(this, PairingTones.dot(view.tone)))
        strokeColor.to(ContextCompat.getColor(this, PairingTones.stroke(view.tone)))
        if (view.pulse && dotShown) pulse.start() else pulse.stop()
    }

    /** Voice and Send only while Connected; always in shared-token mode (controller never created there). */
    private fun voiceAllowed(): Boolean = !pairing.configured || pairingController.state.value == PairingState.Connected

    /** 403 with a pairing origin: wait for the controller's status check instead of guessing. */
    private fun callCheckMessage(check: CallCheck): String = when (check) {
        CallCheck.UNAVAILABLE -> pairingText(PairingText.FORBIDDEN_CALL)
        CallCheck.RESOLVED -> PairingScreen.view(pairingController.state.value, 0L, null).title?.let { pairingText(it) }.orEmpty()
        CallCheck.IDLE, CallCheck.CHECKING -> pairingText(PairingText.CALL_CHECKING)
    }

    /** Text for a failed call in pairing mode; 403 waits for the controller's status check. */
    private fun callFailureText(outcome: CallOutcome): String = when (outcome) {
        CallOutcome.DeviceCheck -> callCheckMessage(pairingController.callCheck.value)
        is CallOutcome.Notice ->
            if (outcome.arg == null) getString(CallTexts.of(outcome.text)) else getString(CallTexts.of(outcome.text), outcome.arg)
    }

    /** UI thread. The failed message stays in the conversation with the notice instead of an answer. */
    private fun showChatFailure(message: String, outcome: CallOutcome) {
        val text = callFailureText(outcome)
        binding.tvChatOutput.append("\n$message -> \u26A0 $text")
        showNotice(text)
    }

    /**
     * UI thread. Status-line notice for 8 s; a newer one replaces it and restarts the time. A network failure
     * never touches the pairing card. The line is cleared only if it still shows this notice.
     */
    private fun showNotice(text: String) {
        val ticket = notice.show(text)
        binding.tvStatus.text = text
        noticeJob?.cancel()
        noticeJob = lifecycleScope.launch {
            delay(notice.durationMs)
            if (notice.expire(ticket) && binding.tvStatus.text.toString() == text) binding.tvStatus.text = null
        }
    }

    private fun pairingText(text: PairingText, arg: String? = null): String =
        if (arg == null) getString(PairingTexts.of(text)) else getString(PairingTexts.of(text), arg)

    private fun showPairingText(target: android.widget.TextView, value: String?) {
        target.text = value.orEmpty()
        target.visibility = if (value == null) android.view.View.GONE else android.view.View.VISIBLE
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        wearablesIntegration.onNewIntent(intent)
    }

    private fun connectToBackend() {
        val baseUrl = backendUrl
        if (baseUrl == null) {
            updateStatus("Backend URL is not configured or invalid.")
            return
        }
        updateStatus("Connecting to $baseUrl...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl$STATUS_ENDPOINT")
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""
                    runOnUiThread {
                        when (response.code) {
                            200 -> {
                                val provider = try {
                                    org.json.JSONObject(responseBody).optString("provider", "")
                                } catch (_: Exception) { "" }
                                val model = try {
                                    org.json.JSONObject(responseBody).optString("model", "")
                                } catch (_: Exception) { "" }
                                val details = if (provider.isNotEmpty()) " ($provider: $model)" else ""
                                updateStatus("Connected to backend$details")
                                binding.btnConnect.isEnabled = false
                                binding.btnDisconnect.isEnabled = true
                            }
                            401 -> {
                                updateStatus("Authentication failed (HTTP 401): Check API Bearer token.")
                            }
                            503 -> {
                                updateStatus("Backend service unavailable (HTTP 503): LLM provider offline.")
                            }
                            else -> {
                                updateStatus("Backend returned HTTP ${response.code}")
                            }
                        }
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                runOnUiThread { updateStatus("Connection timed out connecting to $baseUrl") }
            } catch (e: java.net.ConnectException) {
                runOnUiThread { updateStatus("Backend unreachable at $baseUrl: Connection refused") }
            } catch (e: java.net.UnknownHostException) {
                runOnUiThread { updateStatus("Backend host unknown: ${e.message}") }
            } catch (e: Exception) {
                runOnUiThread { updateStatus("Connection error: ${e.javaClass.simpleName}: ${e.message}") }
            }
        }
    }

    private fun authenticationMessage() = if (pairing.managed)
        "Autorización del dispositivo no válida" else "Authentication failed (HTTP 401): Check API Bearer token."

    private fun disconnectFromBackend() {
        updateStatus("Disconnected")
        binding.btnConnect.isEnabled = true
        binding.btnDisconnect.isEnabled = false
    }

    private fun sendMessage() {
        val baseUrl = backendUrl
        if (baseUrl == null) {
            updateStatus("Backend URL is not configured for this build.")
            return
        }
        val message = binding.etMessage.text.toString().trim()
        if (message.isEmpty()) return

        binding.btnSend.isEnabled = false
        updateStatus(if (pairing.configured) getString(R.string.call_generating) else "Generating response...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val json = org.json.JSONObject().put("message", message).toString()
                val body = json.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$baseUrl$CHAT_ENDPOINT")
                    .post(body)
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    runOnUiThread {
                        if (response.isSuccessful && responseBody != null) {
                            val answer = try {
                                org.json.JSONObject(responseBody).optString("answer", responseBody)
                            } catch (e: Exception) { responseBody }
                            binding.tvChatOutput.text = "${binding.tvChatOutput.text}\n$message -> $answer"
                            binding.etMessage.text?.clear()
                            if (pairing.configured) {
                                callErrors.onSuccess()
                                updateStatus("")
                            } else updateStatus("Connected")
                        } else if (pairing.configured) {
                            showChatFailure(message, callErrors.describe(response.code))
                        } else if (response.code == 401) {
                            binding.tvChatOutput.text = "${binding.tvChatOutput.text}\nError: ${authenticationMessage()}"
                            updateStatus("Authentication failed (HTTP 401)")
                        } else {
                            binding.tvChatOutput.text = "${binding.tvChatOutput.text}\nError: HTTP ${response.code} ${responseBody ?: ""}"
                            updateStatus("Connected")
                        }
                        binding.btnSend.isEnabled = voiceAllowed()
                    }
                }
            } catch (e: Exception) {
                if (pairing.configured) {
                    val outcome = callErrors.describe(e)  // A CancellationException is rethrown: nothing is shown.
                    runOnUiThread {
                        showChatFailure(message, outcome)
                        binding.btnSend.isEnabled = voiceAllowed()
                    }
                    return@launch
                }
                runOnUiThread {
                    binding.tvChatOutput.text = "${binding.tvChatOutput.text}\nError: ${e.javaClass.simpleName}: ${e.message}"
                    updateStatus("Connected")
                    binding.btnSend.isEnabled = true
                }
            }
        }
    }

    private fun toggleRecording() {
        // Ignore taps while a recording is being uploaded or transcribed.
        if (isProcessing) return
        if (isRecording) {
            stopRecording()
        } else if (ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        if (isRecording) return
        if (ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) != PackageManager.PERMISSION_GRANTED) {
            updateStatus(getString(R.string.voice_mic_denied))
            return
        }

        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (bufferSize <= 0) {
            updateStatus(getString(R.string.voice_mic_unsupported))
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            updateStatus(getString(R.string.voice_mic_unavailable))
            audioRecord?.release()
            audioRecord = null
            return
        }

        recordedPcm.reset()
        isRecording = true
        recordingStartMs = System.currentTimeMillis()
        binding.btnVoice.text = getString(R.string.voice_button_stop)
        updateStatus(getString(R.string.voice_state_recording, 0))
        audioRecord?.startRecording()

        recordingThread = Thread {
            val buffer = ShortArray(bufferSize / 2)
            var lastStatusMs = 0L
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    val bb = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until read) bb.putShort(buffer[i])
                    synchronized(recordedPcm) {
                        recordedPcm.write(bb.array(), 0, read * 2)
                    }
                }

                val now = System.currentTimeMillis()
                val elapsed = now - recordingStartMs

                // Maximum recording duration of 30 seconds: stop and save automatically.
                if (elapsed >= MAX_DURATION_MS) {
                    isRecording = false
                    runOnUiThread {
                        binding.btnVoice.text = getString(R.string.voice_button_start)
                        finishRecording()
                    }
                    break
                }

                // Recording state and duration display, updated once per second.
                if (now - lastStatusMs >= 1000) {
                    lastStatusMs = now
                    runOnUiThread { updateStatus(getString(R.string.voice_state_recording, (elapsed / 1000).toInt())) }
                }
            }
        }
        recordingThread?.start()
    }

    private fun stopRecording() {
        isRecording = false
        recordingThread?.join(1000)
        recordingThread = null
        finishRecording()
    }

    private fun finishRecording() {
        if (audioRecord == null) return // Already finalized (e.g. auto-stop path)

        // Proper microphone resource cleanup: stop then release.
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        binding.btnVoice.text = getString(R.string.voice_button_start)
        val pcm = synchronized(recordedPcm) { recordedPcm.toByteArray() }
        if (pcm.isEmpty()) {
            updateStatus(getString(R.string.voice_recording_empty))
            return
        }

        updateStatus(getString(R.string.voice_saving))
        val wav = pcmToWav(pcm, 16000, 1, 16)
        try {
            // Temporary private app storage (cache dir).
            val file = File(cacheDir, "recording.wav")
            FileOutputStream(file).use { it.write(wav) }
            val duration = pcm.size / (16000 * 2)
            updateStatus(getString(R.string.voice_saved, duration))
            // Run the full voice pipeline off the UI thread:
            // transcribe (Vosk) → Ollama chat → Piper synthesis → MediaPlayer playback.
            isProcessing = true
            binding.btnVoice.isEnabled = false
            setState(UiState.Transcribing)
            runVoicePipeline(wav)
        } catch (e: Exception) {
            updateStatus(getString(R.string.voice_save_failed))
            isProcessing = false
            binding.btnVoice.isEnabled = voiceAllowed()
        }
    }

    private fun pcmToWav(pcm: ByteArray, sampleRate: Int, channels: Int, bits: Int): ByteArray {
        return WavCodec.encodePcmToWav(pcm, sampleRate, channels, bits)
    }

    // Full voice pipeline: transcribe → Ollama → Piper → playback.
    // Runs entirely off the main UI thread on a single coroutine.
    private fun runVoicePipeline(wav: ByteArray) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Transcribe via Vosk.
                val transcript = transcribeWavRaw(wav)
                runOnUiThread { binding.tvTranscription.text = transcript.text }

                if (transcript.text.isBlank()) {
                    runOnUiThread {
                        binding.tvTranscription.text = getString(R.string.transcription_empty)
                        setState(UiState.Ready)
                        updateStatus(getString(R.string.transcription_empty))
                    }
                    return@launch
                }

                // Do not send silence or low-quality transcriptions automatically when
                // the backend requests clarification.
                if (transcript.needsClarification) {
                    runOnUiThread {
                        binding.tvTranscription.text = transcript.clarification
                        setState(UiState.Ready)
                        updateStatus(getString(R.string.voice_clarification))
                    }
                    return@launch
                }

                // 2. Ollama chat.
                runOnUiThread { setState(UiState.Thinking) }
                val answer = sendToOllama(transcript.text)
                runOnUiThread {
                    binding.tvAiResponse.text = answer
                    updateStatus(getString(R.string.voice_answer_received))
                }

                // 3. Piper synthesis.
                runOnUiThread { setState(UiState.Synthesizing) }
                val audioBytes = synthesizeSpeech(answer)
                callErrors.onSuccess()

                // 4. MediaPlayer playback.
                playAudio(audioBytes)

            } catch (e: Exception) {
                if (pairing.configured) {
                    val outcome = callErrors.describe(e)  // A CancellationException is rethrown: nothing is shown.
                    runOnUiThread {
                        val text = callFailureText(outcome)
                        binding.tvAiResponse.text = text
                        setState(UiState.Error)
                        showNotice(text)
                    }
                } else runOnUiThread {
                    binding.tvAiResponse.text = getString(R.string.transcription_error)
                    setState(UiState.Error)
                    updateStatus("Error: ${e.javaClass.simpleName}: ${e.message ?: ""}")
                }
            } finally {
                runOnUiThread {
                    isProcessing = false
                    binding.btnVoice.isEnabled = voiceAllowed()
                }
            }
        }
    }

    /** Recognized-text result from the Vosk endpoint, including clarification policy. */
    private data class TranscriptResult(
        val text: String,
        val needsClarification: Boolean,
        val clarification: String
    )

    /** POST raw WAV to /api/v1/voice/transcribe and parse the JSON response. */
    private fun transcribeWavRaw(wav: ByteArray): TranscriptResult {
        val baseUrl = backendUrl ?: throw IllegalStateException("Backend URL is not configured.")
        val body = wav.toRequestBody("audio/wav".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl$TRANSCRIBE_ENDPOINT")
            .post(body)
            .build()
        return okHttpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                if (pairing.configured) throw CallHttpException(response.code)  // Only the status, never the body.
                if (response.code == 401) {
                    throw RuntimeException(authenticationMessage())
                }
                throw RuntimeException("Transcribe HTTP ${response.code} ${response.message ?: ""}")
            }
            val obj = org.json.JSONObject(responseBody)
            TranscriptResult(
                text = obj.optString("text", "").trim(),
                needsClarification = obj.optBoolean("needs_clarification", false),
                clarification = obj.optString("clarification", "")
            )
        }
    }

    /** POST recognized text to the existing Ollama chat endpoint /api/v1/chat. */
    private fun sendToOllama(message: String): String {
        val baseUrl = backendUrl ?: throw IllegalStateException("Backend URL is not configured.")
        val json = org.json.JSONObject().put("message", message).toString()
        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl$CHAT_ENDPOINT")
            .post(body)
            .build()
        return okHttpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                if (pairing.configured) throw CallHttpException(response.code)  // Only the status, never the body.
                if (response.code == 401) {
                    throw RuntimeException(authenticationMessage())
                }
                throw RuntimeException("Chat HTTP ${response.code} ${response.message ?: ""}")
            }
            org.json.JSONObject(responseBody).optString("answer", responseBody)
        }
    }

    /** POST text to the Piper synthesis endpoint /api/v1/voice/synthesize and return WAV bytes. */
    private fun synthesizeSpeech(text: String): ByteArray {
        val baseUrl = backendUrl ?: throw IllegalStateException("Backend URL is not configured.")
        val json = org.json.JSONObject()
            .put("text", text)
            .put("voice", "es_ES-sharvard-medium")
            .put("speaker", 0)
            .put("speed", 1.0)
            .toString()
        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl$SYNTHESIZE_ENDPOINT")
            .post(body)
            .build()
        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (pairing.configured) throw CallHttpException(response.code)  // Only the status, never the body.
                if (response.code == 401) {
                    throw RuntimeException(authenticationMessage())
                }
                throw RuntimeException("Synthesize HTTP ${response.code} ${response.message ?: ""}")
            }
            response.body?.bytes() ?: byteArrayOf()
        }
    }

    /** Save WAV bytes to private cache and play via MediaPlayer. */
    private fun playAudio(wav: ByteArray) {
        if (wav.isEmpty()) {
            runOnUiThread {
                setState(UiState.Ready)
                updateStatus(getString(R.string.voice_no_audio))
            }
            return
        }
        val file = File(cacheDir, "response.wav")
        FileOutputStream(file).use { it.write(wav) }
        runOnUiThread {
            releaseMediaPlayer()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnPreparedListener { mp ->
                    mp.start()
                    setState(UiState.Speaking)
                    updateStatus(getString(R.string.voice_playing))
                }
                setOnCompletionListener {
                    releaseMediaPlayer()
                    setState(UiState.Ready)
                    updateStatus(getString(R.string.voice_playback_done))
                }
                setOnErrorListener { _, _, _ ->
                    releaseMediaPlayer()
                    setState(UiState.Error)
                    updateStatus(getString(R.string.voice_playback_failed))
                    false
                }
                prepareAsync()
            }
        }
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.let {
            try { it.stop() } catch (_: Exception) { }
            it.release()
        }
        mediaPlayer = null
    }

    private fun updateStatus(text: String) {
        runOnUiThread { binding.tvStatus.text = text }
    }
}