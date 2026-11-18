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
import androidx.activity.OnBackPressedCallback
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
    // Stage A: pairing card tone (dot and border colour) and the dot's pulse.
    private val pulse by lazy { Pulse(binding.statusDot) }
    private val dotColor by lazy {
        ColorTransition(this) { binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(it) }
    }
    private val strokeColor by lazy { ColorTransition(this) { binding.cardPairing.strokeColor = it } }
    // Stage C: pairing-mode conversation in bubbles (shared-token mode keeps tv_chat_output).
    private val chatLog = ChatLog()
    private val chatBubbles by lazy { ChatBubbles(binding.chatMessages, binding.mainScroll) }
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
    // Stage B2 (pairing mode): full voice screen with the real level; the 30 s limit lives in VoiceTiming.
    private val voiceFlow = VoiceFlow()
    private val voiceMeter = VoiceMeter()
    private val voiceScreen by lazy { VoiceScreen(this, binding.root, onStop = { stopVoice() }, onCancel = { cancelRecording() }) }
    // Stage B3 (pairing mode): voice in the conversation; typed chat and voice never overlap (InputLock).
    private var chatInFlight = false
    private var voiceMessage = 0L
    private var voiceAnswer: Long? = null
    /** Back = "Cancelar" only while the voice screen is open. */
    private val voiceBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = cancelRecording()
    }

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
            updateInputs()
            if (pairing.configured) return@runOnUiThread  // Pairing mode: voice states live in the bubbles.
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

    /** Leaving the app (or rotating) while listening cancels: nothing is sent. */
    override fun onStop() {
        if (voiceFlow.screenOpen) cancelRecording()
        super.onStop()
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
        // The message bar is fixed at the bottom: resize the content so the keyboard never covers it.
        @Suppress("DEPRECATION")
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startRecording()
            } else {
                voiceStartFailed(getString(R.string.voice_mic_denied))
            }
        }
        onBackPressedDispatcher.addCallback(this, voiceBack)
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
            binding.chatMessages.visibility = android.view.View.VISIBLE
            binding.tvChatOutput.visibility = android.view.View.GONE
            // Stage B3: voice goes into the conversation; these stay for shared-token mode only.
            binding.tvTranscription.visibility = android.view.View.GONE
            binding.tvAiResponse.visibility = android.view.View.GONE
            binding.btnPair.setOnClickListener { pairingController.onEvent(PairingEvent.UserRetry) }
            // Pairing runs only while the app is visible: repeatOnLifecycle cancels it on onStop.
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch { pairingController.run() }
                    launch { pairingController.state.collect { renderPairing(it) } }
                    launch {
                        // No looping animation outside the foreground; the next render restarts it.
                        try {
                            chatBubbles.resumeAnimations()
                            awaitCancellation()
                        } finally {
                            pulse.stop()
                            chatBubbles.pauseAnimations()
                        }
                    }
                    launch {
                        // Result of the status check after a 403 (drop: do not repeat an old one on resume).
                        pairingController.callCheck.drop(1).collect { check ->
                            // Typed and voice "Comprobando…" are both bubbles now.
                            ChatFailures.resolution(check)?.let { resolveChatCheck(it) }
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
        updateInputs()
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
        renderChatEmpty(state == PairingState.Connected)
    }

    /** Welcome only in pairing mode, while Connected and before the first message; voice does not hide it. */
    private fun renderChatEmpty(connected: Boolean) =
        fadeVisibility(binding.chatEmpty, ChatEmpty.visible(pairing.configured, connected, chatLog.isEmpty))

    /** Voice and Send only while Connected; always in shared-token mode (controller never created there). */
    private fun voiceAllowed(): Boolean = !pairing.configured || pairingController.state.value == PairingState.Connected

    /**
     * Send and Voice. Pairing mode: only while Connected, never both questions at once (typing is allowed while
     * the answer is spoken). Shared-token mode: as before (voice waits while busy, Send handled by sendMessage).
     */
    private fun updateInputs() {
        val busy = isProcessing || (uiState != UiState.Idle && uiState != UiState.Ready && uiState != UiState.Error)
        if (!pairing.configured) {
            binding.btnVoice.isEnabled = !busy
            return
        }
        val inputs = InputLock.of(voiceAllowed(), chatInFlight, voiceFlow.stage, busy)
        binding.btnSend.isEnabled = inputs.send
        binding.btnVoice.isEnabled = inputs.voice
    }

    /** UI thread: a conversation change, then the welcome (any entry, message or notice, hides it). */
    private fun showChange(change: ChatChange) {
        chatBubbles.apply(change)
        renderChatEmpty(pairingController.state.value == PairingState.Connected)
    }

    /** UI thread. Voice notice: a bubble in pairing mode, the status line in shared-token mode. */
    private fun voiceNotice(text: String) {
        if (pairing.configured) showChange(chatLog.notice(text)) else updateStatus(text)
    }

    /** UI thread. A failed voice step, by the same table as typed chat ("No enviado" only if the chat call failed). */
    private fun showVoiceFailure(message: Long, step: VoiceStep, outcome: CallOutcome) {
        val failure = ChatFailures.present(outcome)
        showChange(VoiceFailures.apply(chatLog, message, voiceAnswer, step, failure) { callText(it.text, it.arg) })
        // The check may already have ended (the collector only sees later changes).
        if (failure == ChatFailure.Check) ChatFailures.resolution(pairingController.callCheck.value)?.let { resolveChatCheck(it) }
    }

    private fun callText(text: CallText, arg: String?): String =
        if (arg == null) getString(CallTexts.of(text)) else getString(CallTexts.of(text), arg)

    /**
     * UI thread. A failed chat message stays as "No enviado" in the conversation; the status line is not used,
     * and nothing is added when the pairing card already explains (deleted, blocked).
     */
    private fun showChatFailure(chatId: Long, outcome: CallOutcome) {
        when (val failure = ChatFailures.present(outcome)) {
            ChatFailure.Check -> {
                chatBubbles.apply(chatLog.checking(chatId))
                // The check may already have ended (the collector only sees later changes).
                ChatFailures.resolution(pairingController.callCheck.value)?.let { resolveChatCheck(it) }
            }
            ChatFailure.NotSentOnly -> chatBubbles.apply(chatLog.failed(chatId, null))
            is ChatFailure.WithNotice -> chatBubbles.apply(chatLog.failed(chatId, callText(failure.text, failure.arg)))
        }
    }

    /** "Comprobando…" becomes "Esta función no está disponible…" or goes away (the card explains). */
    private fun resolveChatCheck(resolution: ChatCheckResolution) {
        val notice = if (resolution == ChatCheckResolution.UNAVAILABLE) pairingText(PairingText.FORBIDDEN_CALL) else null
        chatBubbles.apply(chatLog.resolveCheck(notice))
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
        val chatId = if (pairing.configured) {
            binding.etMessage.text?.clear()  // Empty even if sending fails: the text stays in its bubble.
            chatInFlight = true
            updateInputs()
            val (id, change) = chatLog.send(message)
            showChange(change)
            id
        } else {
            updateStatus("Generating response...")
            0L
        }
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
                            if (pairing.configured) {
                                callErrors.onSuccess()
                                chatBubbles.apply(chatLog.answered(chatId, answer))
                            } else {
                                binding.tvChatOutput.text = "${binding.tvChatOutput.text}\n$message -> $answer"
                                binding.etMessage.text?.clear()
                                updateStatus("Connected")
                            }
                        } else if (pairing.configured) {
                            showChatFailure(chatId, callErrors.describe(response.code))
                        } else if (response.code == 401) {
                            binding.tvChatOutput.text = "${binding.tvChatOutput.text}\nError: ${authenticationMessage()}"
                            updateStatus("Authentication failed (HTTP 401)")
                        } else {
                            binding.tvChatOutput.text = "${binding.tvChatOutput.text}\nError: HTTP ${response.code} ${responseBody ?: ""}"
                            updateStatus("Connected")
                        }
                        chatFinished()
                    }
                }
            } catch (e: Exception) {
                if (pairing.configured) {
                    val outcome = callErrors.describe(e)  // A CancellationException is rethrown: nothing is shown.
                    runOnUiThread {
                        showChatFailure(chatId, outcome)
                        chatFinished()
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

    /** UI thread: the typed question got its answer (or failed); voice may start again. */
    private fun chatFinished() {
        chatInFlight = false
        if (pairing.configured) updateInputs() else binding.btnSend.isEnabled = voiceAllowed()
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
        if (pairing.configured && voiceFlow.busy) return  // A voice question is still on its way.
        if (ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) != PackageManager.PERMISSION_GRANTED) {
            voiceStartFailed(getString(R.string.voice_mic_denied))
            return
        }

        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (bufferSize <= 0) {
            voiceStartFailed(getString(R.string.voice_mic_unsupported))
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
            voiceStartFailed(getString(R.string.voice_mic_unavailable))
            audioRecord?.release()
            audioRecord = null
            return
        }

        recordedPcm.reset()
        voiceMeter.reset()
        // The voice screen opens only once the microphone is really recording.
        val started = try {
            audioRecord?.startRecording()
            audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
        } catch (e: IllegalStateException) {
            false
        }
        if (!started) {
            voiceStartFailed(getString(R.string.voice_mic_unavailable))
            audioRecord?.release()
            audioRecord = null
            return
        }
        isRecording = true
        recordingStartMs = System.currentTimeMillis()
        if (pairing.configured) {
            voiceFlow.on(VoiceEvent.START)
            voiceScreen.show(voiceMeter)
            voiceBack.isEnabled = true
            updateInputs()  // No typed question while listening.
        } else {
            binding.btnVoice.text = getString(R.string.voice_button_stop)
            updateStatus(getString(R.string.voice_state_recording, 0))
        }

        recordingThread = Thread {
            val buffer = ShortArray(bufferSize / 2)
            var lastStatusMs = 0L
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    voiceMeter.onSamples(buffer, read)  // Level for the voice screen; no allocation.
                    val bb = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until read) bb.putShort(buffer[i])
                    synchronized(recordedPcm) {
                        recordedPcm.write(bb.array(), 0, read * 2)
                    }
                }

                val now = System.currentTimeMillis()
                val elapsed = now - recordingStartMs

                // Maximum recording duration of 30 seconds: stop and send, exactly like "Detener y enviar".
                if (VoiceTiming.reachedMax(elapsed)) {
                    isRecording = false
                    runOnUiThread {
                        // After a cancel STOP is refused and finishRecording finds no AudioRecord: nothing is sent.
                        if (voiceFlow.on(VoiceEvent.STOP)) closeVoiceScreen()
                        binding.btnVoice.text = getString(R.string.voice_button_start)
                        finishRecording()
                    }
                    break
                }

                // Duration display, updated once per second: the voice screen counter, or the status line.
                if (now - lastStatusMs >= 1000) {
                    lastStatusMs = now
                    val seconds = VoiceTiming.seconds(elapsed)
                    runOnUiThread {
                        if (pairing.configured) voiceScreen.seconds(seconds)
                        else updateStatus(getString(R.string.voice_state_recording, seconds))
                    }
                }
            }
        }
        recordingThread?.start()
    }

    /** "Detener y enviar" (UI thread): the screen closes and the recording goes on as before. */
    private fun stopVoice() {
        if (!voiceFlow.on(VoiceEvent.STOP)) return
        closeVoiceScreen()
        stopRecording()
    }

    /** "Cancelar", Back or onStop while listening: stop the microphone and discard the audio. Nothing is sent. */
    private fun cancelRecording() {
        if (!voiceFlow.on(VoiceEvent.CANCEL)) return
        closeVoiceScreen()
        isRecording = false
        recordingThread?.join(1000)
        recordingThread = null
        try { audioRecord?.stop() } catch (_: IllegalStateException) { }
        audioRecord?.release()
        audioRecord = null
        synchronized(recordedPcm) { recordedPcm.reset() }
        voiceMeter.reset()
        binding.btnVoice.text = getString(R.string.voice_button_start)
    }

    private fun closeVoiceScreen() {
        voiceBack.isEnabled = false
        voiceScreen.hide(binding.btnVoice)
        updateInputs()
    }

    /**
     * The microphone could not start: the voice screen never opens. Pairing mode: a notice in the conversation
     * (it hides the welcome like a message does); shared-token mode: the status line, as before.
     */
    private fun voiceStartFailed(text: String) = voiceNotice(text)

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
            voiceFlow.on(VoiceEvent.FAILED)
            voiceNotice(getString(R.string.voice_recording_empty))  // No provisional bubble: nothing was recorded.
            updateInputs()
            return
        }

        if (pairing.configured) {
            // Provisional bubble "Transcribiendo"; the first one also hides the welcome.
            val (id, change) = chatLog.voiceStarted()
            voiceMessage = id
            voiceAnswer = null
            showChange(change)
        } else updateStatus(getString(R.string.voice_saving))
        val wav = pcmToWav(pcm, 16000, 1, 16)
        try {
            // Temporary private app storage (cache dir).
            val file = File(cacheDir, "recording.wav")
            FileOutputStream(file).use { it.write(wav) }
            val duration = pcm.size / (16000 * 2)
            if (!pairing.configured) updateStatus(getString(R.string.voice_saved, duration))
            // Run the full voice pipeline off the UI thread:
            // transcribe (Vosk) → Ollama chat → Piper synthesis → MediaPlayer playback.
            isProcessing = true
            setState(UiState.Transcribing)
            runVoicePipeline(wav)
        } catch (e: Exception) {
            voiceFlow.on(VoiceEvent.FAILED)
            if (pairing.configured) {
                showChange(chatLog.voiceFailed(voiceMessage, null, VoiceStep.TRANSCRIBE, getString(R.string.voice_save_failed)))
            } else updateStatus(getString(R.string.voice_save_failed))
            isProcessing = false
            updateInputs()
        }
    }

    private fun pcmToWav(pcm: ByteArray, sampleRate: Int, channels: Int, bits: Int): ByteArray {
        return WavCodec.encodePcmToWav(pcm, sampleRate, channels, bits)
    }

    // Full voice pipeline: transcribe → Ollama → Piper → playback.
    // Runs entirely off the main UI thread on a single coroutine. Pairing mode: every step is a conversation
    // change (stage B3); shared-token mode: tv_transcription, tv_ai_response and the status line, as before.
    private fun runVoicePipeline(wav: ByteArray) {
        val inChat = pairing.configured
        val message = voiceMessage
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Transcribe via Vosk.
                val transcript = transcribeWavRaw(wav)
                if (!inChat) runOnUiThread { binding.tvTranscription.text = transcript.text }

                if (transcript.text.isBlank()) {
                    runOnUiThread {
                        voiceFlow.on(VoiceEvent.NO_SPEECH)
                        if (inChat) {
                            showChange(chatLog.voiceNoSpeech(message, getString(R.string.transcription_empty)))
                        } else {
                            binding.tvTranscription.text = getString(R.string.transcription_empty)
                            updateStatus(getString(R.string.transcription_empty))
                        }
                        setState(UiState.Ready)
                    }
                    return@launch
                }

                // Do not send silence or low-quality transcriptions automatically when
                // the backend requests clarification.
                if (transcript.needsClarification) {
                    runOnUiThread {
                        voiceFlow.on(VoiceEvent.CLARIFICATION)
                        if (inChat) {
                            val text = VoiceFailures.clarification(transcript.clarification, getString(R.string.voice_clarification))
                            showChange(chatLog.voiceClarification(message, text))
                        } else {
                            binding.tvTranscription.text = transcript.clarification
                            updateStatus(getString(R.string.voice_clarification))
                        }
                        setState(UiState.Ready)
                    }
                    return@launch
                }

                // 2. Ollama chat.
                runOnUiThread {
                    voiceFlow.on(VoiceEvent.TRANSCRIBED)
                    if (inChat) showChange(chatLog.voiceTranscribed(message, transcript.text))
                    setState(UiState.Thinking)
                }
                val answer = sendToOllama(transcript.text)
                runOnUiThread {
                    voiceFlow.on(VoiceEvent.ANSWERED)
                    if (inChat) {
                        val (id, change) = chatLog.voiceAnswered(message, answer)  // "Preparando la voz…"
                        voiceAnswer = id
                        showChange(change)
                    } else {
                        binding.tvAiResponse.text = answer
                        updateStatus(getString(R.string.voice_answer_received))
                    }
                }

                // 3. Piper synthesis.
                runOnUiThread { setState(UiState.Synthesizing) }
                val audioBytes = synthesizeSpeech(answer)
                callErrors.onSuccess()

                // 4. MediaPlayer playback.
                playAudio(audioBytes)

            } catch (e: Exception) {
                if (inChat) {
                    val outcome = callErrors.describe(e)  // A CancellationException is rethrown: nothing is shown.
                    runOnUiThread {
                        voiceFlow.on(VoiceEvent.FAILED)
                        showVoiceFailure(message, voiceFlow.failedStep ?: VoiceStep.TRANSCRIBE, outcome)
                        setState(UiState.Error)
                    }
                } else runOnUiThread {
                    binding.tvAiResponse.text = getString(R.string.transcription_error)
                    setState(UiState.Error)
                    updateStatus("Error: ${e.javaClass.simpleName}: ${e.message ?: ""}")
                }
            } finally {
                runOnUiThread {
                    isProcessing = false
                    updateInputs()
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
                voiceFlow.on(VoiceEvent.NO_AUDIO)
                if (pairing.configured) voiceAnswer?.let { showChange(chatLog.speech(it, null)) }
                voiceNotice(getString(R.string.voice_no_audio))
                setState(UiState.Ready)
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
                    voiceFlow.on(VoiceEvent.AUDIO_READY)
                    mp.start()
                    setState(UiState.Speaking)
                    if (pairing.configured) voiceAnswer?.let { showChange(chatLog.speech(it, ChatEntry.Speech.SPEAKING)) }
                    else updateStatus(getString(R.string.voice_playing))
                }
                setOnCompletionListener {
                    voiceFlow.on(VoiceEvent.PLAYBACK_DONE)
                    releaseMediaPlayer()
                    setState(UiState.Ready)
                    if (pairing.configured) voiceAnswer?.let { showChange(chatLog.speech(it, null)) }
                    else updateStatus(getString(R.string.voice_playback_done))
                }
                setOnErrorListener { _, _, _ ->
                    voiceFlow.on(VoiceEvent.FAILED)
                    releaseMediaPlayer()
                    setState(UiState.Error)
                    if (pairing.configured) {
                        showChange(chatLog.voiceFailed(voiceMessage, voiceAnswer, VoiceStep.PLAYBACK,
                            getString(R.string.voice_playback_failed)))
                    } else updateStatus(getString(R.string.voice_playback_failed))
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