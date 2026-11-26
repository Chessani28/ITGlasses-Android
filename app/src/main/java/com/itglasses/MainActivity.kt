package com.itglasses.itglassesandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
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

    // One DevicePairing per process, shared with VoiceForegroundService (PairingAccess); DevicePairing is unchanged.
    private val pairing by lazy { PairingAccess.get(applicationContext, BuildConfig.PAIRING_ORIGIN) }
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
    /** Pairing mode: the same assistant as the web panel (documents, authorization, per-device sessions). */
    private val ASSISTANT_ENDPOINT = "/api/v1/assistant/chat"
    private val TRANSCRIBE_ENDPOINT = "/api/v1/voice/transcribe"
    private val SYNTHESIZE_ENDPOINT = "/api/v1/voice/synthesize"

    @Volatile private var activeToken: String? = null
    /** One assistant session in memory, shared by typed chat and voice (written on the UI or the voice IO thread). */
    @Volatile private var assistantSession: String? = null

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .addInterceptor(BusyRetryInterceptor())  // Demo lentes (two phones): see BusyRetry.
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
    /** Voz 1: microphone source in use, and debug-only timing of each voice question (no text, no audio). */
    private var micSource: Int? = null
    private var voiceT0 = 0L
    @Volatile private var voiceEngine: String? = null
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

    // Demo lentes ("Usar lentes"): Bluetooth route, end of speech and tones. Unused with the switch off.
    private val glassesAudio by lazy { GlassesAudio(this) }
    @Volatile private var endOfSpeech: EndOfSpeech? = null
    private var glassesStarting = false
    /** Increases with each voice turn: a late release never touches a newer turn's route. */
    private var voiceTurn = 0
    private val CUE_GAP_MS = 80L
    /** Prueba de la patilla (fase A, solo debug): exists only while its switch is on. */
    private var mediaProbe: MediaButtonProbe? = null
    // Background voice ("Escuchar con el celular bloqueado"): permissions, and the bubble of the turn on its way.
    private lateinit var backgroundPermissions: ActivityResultLauncher<Array<String>>
    private var backgroundMessage: Long? = null
    /** After asking Android to start the service: if it is not running by then, the switch goes off with a notice. */
    private val BACKGROUND_START_CHECK_MS = 3000L

    private fun setState(state: UiState) {
        uiState = state
        if (state == UiState.Ready || state == UiState.Error) runOnUiThread { voiceTurnEnded(error = state == UiState.Error) }
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
    override fun onStart() {
        super.onStart()
        if (BackgroundTurns.running.value) {
            VoiceForegroundService.reclaim(this, "app_start")  // Take the temple tap back from video/music apps.
        } else if (BackgroundVoice.enabled(this)) {
            // Saved on but not running (reinstall, killed): the microphone service can only start while the app
            // is visible, so restart it here; if it cannot run, show the switch off.
            val canRun = pairing.configured && pairing.managed &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (canRun) startBackgroundVoice() else showBackgroundOff()
        }
    }

    override fun onStop() {
        if (voiceFlow.screenOpen) cancelRecording()
        super.onStop()
    }

    override fun onDestroy() {
        if (::wearablesIntegration.isInitialized) wearablesIntegration.onDestroy()
        super.onDestroy()
        releaseMediaPlayer()
        glassesAudio.release()
        mediaProbe?.release()
        mediaProbe = null
    }

    private fun startMediaProbe() {
        if (BackgroundTurns.running.value) {
            // Never two owners of the temple tap: the running service wins, the probe stays off.
            MediaButtonProbe.set(this, false)
            if (binding.swMediaProbe.isChecked) binding.swMediaProbe.isChecked = false
            return
        }
        if (mediaProbe == null) mediaProbe = MediaButtonProbe(this).also { it.start() }
    }

    private fun stopMediaProbe() {
        mediaProbe?.release()
        mediaProbe = null
    }

    /** Switch on: needs this phone paired, the microphone (and on Android 13+ ask for notifications). */
    private fun enableBackgroundVoice() {
        if (!pairing.configured || !pairing.managed) {
            backgroundFailed(getString(R.string.background_needs_pairing))
            return
        }
        BackgroundVoice.set(this, true)
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startBackgroundVoice() else backgroundPermissions.launch(missing.toTypedArray())
    }

    private fun startBackgroundVoice() {
        // One owner of the temple tap: the diagnostic probe goes off when the service starts.
        if (binding.swMediaProbe.isChecked) binding.swMediaProbe.isChecked = false
        if (!VoiceForegroundService.start(this)) {
            backgroundFailed()
            return
        }
        lifecycleScope.launch {
            delay(BACKGROUND_START_CHECK_MS)
            if (BackgroundVoice.enabled(this@MainActivity) && !BackgroundTurns.running.value) backgroundFailed()
        }
    }

    /** The switch shows the real state: off, without a notice. */
    private fun showBackgroundOff() {
        BackgroundVoice.set(this, false)
        if (binding.swBackground.isChecked) binding.swBackground.isChecked = false
    }

    private fun disableBackgroundVoice() {
        BackgroundVoice.set(this, false)
        VoiceForegroundService.shutdown(this)
    }

    /** The service could not run: switch off, a notice, and the usual mode (app open) goes on as before. */
    private fun backgroundFailed(text: String = getString(R.string.background_start_failed)) {
        BackgroundVoice.set(this, false)
        if (binding.swBackground.isChecked) binding.swBackground.isChecked = false
        voiceNotice(text)
    }

    /** Pairing mode: the background turns as bubbles while the app is open (nothing is replayed afterwards). */
    private fun observeBackgroundTurns() {
        if (!pairing.configured) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    BackgroundTurns.updates.collect { update ->
                        when (update) {
                            is TurnUpdate.Asked -> {
                                val (id, change) = chatLog.send(update.text)
                                backgroundMessage = id
                                showChange(change)
                            }
                            is TurnUpdate.Answered -> {
                                val id = backgroundMessage
                                if (id != null) chatBubbles.apply(chatLog.answered(id, update.text))
                                else showChange(chatLog.notice(update.text))
                                backgroundMessage = null
                            }
                            is TurnUpdate.Failed -> {
                                val id = backgroundMessage
                                if (id != null) chatBubbles.apply(chatLog.failed(id, update.notice))
                                else showChange(chatLog.notice(update.notice))
                                backgroundMessage = null
                            }
                        }
                    }
                }
                launch { BackgroundTurns.startFailures.collect { backgroundFailed() } }
                launch {
                    // Turned off from the notification ("Apagar"): the switch follows.
                    BackgroundTurns.running.collect { running ->
                        if (!running && !BackgroundVoice.enabled(this@MainActivity)) showBackgroundOff()
                    }
                }
            }
        }
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
                beginVoice()
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
        binding.btnHistory.setOnClickListener { showHistory() }
        binding.btnNewConversation.setOnClickListener { newConversation() }
        binding.swGlasses.isChecked = GlassesSettings.enabled(this)
        binding.swGlasses.setOnCheckedChangeListener { _, on -> GlassesSettings.set(this, on) }
        binding.swMediaProbe.visibility = if (BuildConfig.DEBUG) android.view.View.VISIBLE else android.view.View.GONE
        binding.swMediaProbe.isChecked = BuildConfig.DEBUG && MediaButtonProbe.enabled(this)
        binding.swMediaProbe.setOnCheckedChangeListener { _, on ->
            if (on && BackgroundTurns.running.value) {
                // The service owns the temple tap: the probe would take it away (seen in the log on 23/11).
                binding.swMediaProbe.isChecked = false
                voiceNotice(getString(R.string.media_probe_busy))
                return@setOnCheckedChangeListener
            }
            MediaButtonProbe.set(this, on)
            if (on) startMediaProbe() else {
                stopMediaProbe()
                VoiceForegroundService.reclaim(this, "probe_off")
            }
        }
        if (BuildConfig.DEBUG && MediaButtonProbe.enabled(this)) startMediaProbe()
        backgroundPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val mic = result[Manifest.permission.RECORD_AUDIO] ?: (ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            if (mic) startBackgroundVoice() else backgroundFailed(getString(R.string.voice_mic_denied))
        }
        binding.swBackground.isChecked = BackgroundVoice.enabled(this)
        binding.swBackground.setOnCheckedChangeListener { _, on -> if (on) enableBackgroundVoice() else disableBackgroundVoice() }
        observeBackgroundTurns()
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
        // History: only while Connected; opening or starting one never overlaps a question on its way.
        binding.historyBar.visibility =
            if (pairingController.state.value == PairingState.Connected) android.view.View.VISIBLE else android.view.View.GONE
        val historyFree = inputs.voice && backgroundMessage == null && !historyBusy
        binding.btnHistory.isEnabled = historyFree
        binding.btnNewConversation.isEnabled = historyFree
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
                okHttpClient.newCall(chatRequest(baseUrl, message)).execute().use { response ->
                    val responseBody = response.body?.string()
                    runOnUiThread {
                        if (response.isSuccessful && responseBody != null) {
                            val answer = try {
                                answerOf(org.json.JSONObject(responseBody), responseBody).text
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

    // ---- Chat history (ChatHistory): the owner's own conversations; open one to continue it, or start a new one. ----

    private var historyBusy = false

    private fun historyRequest(path: String, id: String? = null, cursor: String? = null): Request {
        val json = org.json.JSONObject()
        id?.let { json.put("id", it) }
        cursor?.let { json.put("cursor", it) }
        return Request.Builder().url("${pairing.origin}$path")
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()
    }

    /** IO thread: the JSON body of a history call, or null with the notice to show when it failed. */
    private fun historyCall(request: Request): Pair<org.json.JSONObject?, Int?> = try {
        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string()
            when {
                response.isSuccessful && body != null -> org.json.JSONObject(body) to null
                response.code == 403 && body?.contains("device_without_owner") == true -> null to R.string.history_no_owner
                response.code == 404 -> null to R.string.history_missing
                else -> null to R.string.history_failed
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null to R.string.history_failed
    }

    private fun historyToast(text: Int) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()

    /** UI thread: false (with a notice) while a question is on its way or another history call runs. */
    private fun historyStart(): Boolean {
        if (!binding.btnHistory.isEnabled) {
            historyToast(R.string.history_busy)
            return false
        }
        historyBusy = true
        updateInputs()
        return true
    }

    private fun historyDone() {
        historyBusy = false
        updateInputs()
    }

    private fun showHistory(loaded: List<HistoryItem> = emptyList(), cursor: String? = null) {
        if (!historyStart()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val (json, failure) = historyCall(historyRequest(ChatHistory.LIST, cursor = cursor))
            val items = loaded + (json?.optJSONArray("conversations")?.let { array ->
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull { item ->
                    val id = item.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    HistoryItem(id, item.optString("title").takeIf { !item.isNull("title") },
                        item.optLong("last_message_at"), item.optInt("message_count"), item.optString("channel"),
                        item.optString("device_label").takeIf { !item.isNull("device_label") })
                }
            } ?: emptyList())
            val next = json?.optString("next_cursor")?.takeIf { !json.isNull("next_cursor") && it.isNotEmpty() }
            runOnUiThread {
                historyDone()
                if (json == null) historyToast(failure ?: R.string.history_failed) else historyDialog(items, next)
            }
        }
    }

    private fun historyDialog(items: List<HistoryItem>, next: String?) {
        if (isFinishing || isDestroyed) return
        val now = System.currentTimeMillis()
        val rows: List<CharSequence> = items.map { item ->
            val title = HistoryTexts.title(item)
            val current = if (item.id == assistantSession) " · ${getString(R.string.history_current)}" else ""
            android.text.SpannableStringBuilder(title).apply {
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, title.length, 0)
                append("\n")
                val start = length
                append(HistoryTexts.subtitle(item, now) + current)
                setSpan(android.text.style.RelativeSizeSpan(0.85f), start, length, 0)
            }
        } + listOfNotNull(next?.let { getString(R.string.history_more) })
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.history_title)
            .setNegativeButton(R.string.history_close, null)
        if (rows.isEmpty()) dialog.setMessage(R.string.history_empty)
        else dialog.setItems(rows.toTypedArray()) { _, index ->
            if (index < items.size) openConversation(items[index].id) else showHistory(items, next)
        }
        dialog.show()
    }

    /** Open [id]: the next typed, voice and glasses questions continue it; its messages replace the chat. */
    private fun openConversation(id: String) {
        if (!historyStart()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val (opened, openFailure) = historyCall(historyRequest(ChatHistory.OPEN, id = id))
            val (json, failure) = if (opened == null) null to openFailure
                else historyCall(historyRequest(ChatHistory.READ, id = id))
            val messages = json?.optJSONObject("conversation")?.optJSONArray("messages")?.let { array ->
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                    .map { HistoryMessage(it.optString("role"), it.optString("content")) }
            }
            runOnUiThread {
                historyDone()
                if (messages == null) {
                    historyToast(failure ?: R.string.history_failed)
                    return@runOnUiThread
                }
                assistantSession = AssistantSession.next(null, id)
                showChange(chatLog.replaceWith(messages))
            }
        }
    }

    /** "Nueva conversación": the next question (typed, voice or glasses) starts a new one; the chat empties. */
    private fun newConversation() {
        if (!historyStart()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val (json, failure) = historyCall(historyRequest(ChatHistory.NEW))
            runOnUiThread {
                historyDone()
                if (json == null) {
                    historyToast(if (failure == R.string.history_no_owner) failure else R.string.history_new_failed)
                    return@runOnUiThread
                }
                assistantSession = null
                showChange(chatLog.clear())
                historyToast(R.string.history_new_done)
            }
        }
    }

    /** UI thread: the typed question got its answer (or failed); voice may start again. */
    private fun chatFinished() {
        chatInFlight = false
        if (pairing.configured) updateInputs() else binding.btnSend.isEnabled = voiceAllowed()
    }

    private fun toggleRecording() {
        // Background voice running: it owns the microphone, so "Hablar" runs (or ends) its turn.
        if (BackgroundTurns.running.value) {
            VoiceForegroundService.talk(this)
            return
        }
        // Ignore taps while a recording is being uploaded or transcribed.
        if (isProcessing) return
        if (isRecording) {
            stopRecording()
        } else if (ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED) {
            beginVoice()
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

        recordedPcm.reset()
        voiceMeter.reset()
        // VOICE_RECOGNITION first, MIC as fallback (MicSources); no audio effects. The voice screen opens only
        // once the microphone is really recording.
        micSource = null
        for (source in MicSources.order) {
            val record = try {
                AudioRecord(source, sampleRate, channelConfig, audioFormat, bufferSize)
            } catch (e: IllegalArgumentException) {
                null
            }
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                record?.release()
                continue
            }
            glassesAudio.input?.let { record.setPreferredDevice(it) }  // Demo lentes: the glasses' microphone.
            val recording = try {
                record.startRecording()
                record.recordingState == AudioRecord.RECORDSTATE_RECORDING
            } catch (e: IllegalStateException) {
                false
            }
            if (!recording) {
                record.release()
                continue
            }
            audioRecord = record
            micSource = source
            break
        }
        if (audioRecord == null) {
            voiceStartFailed(getString(R.string.voice_mic_unavailable))
            return
        }
        endOfSpeech = if (GlassesSettings.enabled(this)) EndOfSpeech() else null
        if (endOfSpeech != null) GlassesLog.d("record start mic=${MicSources.name(micSource)} preferred=${glassesAudio.input?.type ?: "none"}")
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
                var ended = false
                if (read > 0) {
                    voiceMeter.onSamples(buffer, read)  // Level for the voice screen; no allocation.
                    // Demo lentes: automatic end of speech, only with "Usar lentes" on (null otherwise).
                    ended = endOfSpeech?.onBlock(voiceMeter.level, EndOfSpeech.blockMs(read)) == true
                    if (ended) GlassesLog.d("vad end ms=${System.currentTimeMillis() - recordingStartMs} " +
                        "threshold=${"%.2f".format(java.util.Locale.ROOT, endOfSpeech?.threshold ?: 0f)}")
                    val bb = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until read) bb.putShort(buffer[i])
                    synchronized(recordedPcm) {
                        recordedPcm.write(bb.array(), 0, read * 2)
                    }
                }

                val now = System.currentTimeMillis()
                val elapsed = now - recordingStartMs

                // Maximum recording duration of 30 seconds, or (Demo lentes) the end of speech: stop and send,
                // exactly like "Detener y enviar" (which keeps working while the VAD listens).
                if (VoiceTiming.reachedMax(elapsed) || ended) {
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
        voiceTurnEnded(error = false)
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
    private fun voiceStartFailed(text: String) {
        voiceNotice(text)
        voiceTurnEnded(error = true)
    }

    /**
     * Demo lentes. Switch off: the usual recording, unchanged. Switch on: the Bluetooth route first (off the UI
     * thread, up to GlassesAudio.ROUTE_TIMEOUT_MS), the "escuchando" tone, then the usual recording; without the
     * route, the phone's microphone and speaker with a notice.
     */
    private fun beginVoice() {
        if (!GlassesSettings.enabled(this)) {
            startRecording()
            return
        }
        if (glassesStarting || isRecording || (pairing.configured && voiceFlow.busy)) return
        glassesStarting = true
        val turn = ++voiceTurn
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch(Dispatchers.IO) {
            val routed = glassesAudio.acquire()
            VoiceCues.play(VoiceCues.Cue.LISTENING, routed)
            delay(VoiceCues.Cue.LISTENING.ms + CUE_GAP_MS)  // The tone never reaches the recording.
            runOnUiThread {
                glassesStarting = false
                if (turn != voiceTurn) return@runOnUiThread
                if (!routed) voiceNotice(getString(R.string.glasses_unavailable))
                startRecording()
                if (!isRecording) voiceTurnEnded(error = false)
            }
        }
    }

    /**
     * Demo lentes: end of a voice turn (answer spoken, nothing heard, cancelled or failed). The error tone, then
     * the route goes back to the phone and the screen may sleep. Nothing happens with the switch off.
     */
    private fun voiceTurnEnded(error: Boolean) {
        if (!GlassesSettings.enabled(this) && !glassesAudio.active) return
        if (error) VoiceCues.play(VoiceCues.Cue.ERROR, glassesAudio.active)
        val turn = voiceTurn
        binding.root.postDelayed({
            if (turn != voiceTurn || isRecording || glassesStarting) return@postDelayed
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            glassesAudio.release()
        }, if (error) VoiceCues.Cue.ERROR.ms + CUE_GAP_MS else 0L)
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
            voiceFlow.on(VoiceEvent.FAILED)
            voiceNotice(getString(R.string.voice_recording_empty))  // No provisional bubble: nothing was recorded.
            voiceTurnEnded(error = true)
            updateInputs()
            return
        }

        voiceT0 = SystemClock.elapsedRealtime()
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
            if (GlassesSettings.enabled(this)) VoiceCues.play(VoiceCues.Cue.THINKING, glassesAudio.active)
            isProcessing = true
            setState(UiState.Transcribing)
            runVoicePipeline(wav)
        } catch (e: Exception) {
            voiceFlow.on(VoiceEvent.FAILED)
            if (pairing.configured) {
                showChange(chatLog.voiceFailed(voiceMessage, null, VoiceStep.TRANSCRIBE, getString(R.string.voice_save_failed)))
            } else updateStatus(getString(R.string.voice_save_failed))
            isProcessing = false
            voiceTurnEnded(error = true)
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

                runOnUiThread { voiceTiming("transcribe") }
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
                    if (inChat) showChange(chatLog.voiceTranscribed(message, transcript.text, transcript.basicEngine))
                    setState(UiState.Thinking)
                }
                val reply = sendToOllama(transcript.text)
                val answer = reply.text
                runOnUiThread {
                    voiceTiming("answer")
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
                // Pairing mode: the bubble keeps the whole answer with its sources; the voice reads the backend's
                // `speech`, or the answer without sources cut to 450 characters (SpokenAnswer). Shared-token mode:
                // the whole answer, as before.
                val audioBytes = try {
                    synthesizeSpeech(if (inChat) SpokenAnswer.of(reply.text, reply.speech) else answer)
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) voiceFailureLog("synthesis_failed", e)
                    throw e
                }
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

    /** Recognized-text result from the transcription endpoint, including clarification policy. */
    private data class TranscriptResult(
        val text: String,
        val needsClarification: Boolean,
        val clarification: String,
        /** Transcribed by the Vosk fallback (pairing mode): "Motor de voz básico". */
        val basicEngine: Boolean = false,
    )

    /** Debug builds only: elapsed time since "Detener y enviar", engine and microphone. Never text or audio. */
    /** Debug builds only: the failed call's exception type and HTTP status (no message, text or audio). */
    private fun voiceFailureLog(step: String, error: Throwable) {
        if (!BuildConfig.DEBUG) return
        Log.d(VoiceDebugLog.TAG, VoiceDebugLog.failure(step, SystemClock.elapsedRealtime() - voiceT0, error))
    }

    private fun voiceTiming(step: String) {
        if (!BuildConfig.DEBUG) return
        Log.d(VoiceDebugLog.TAG, VoiceDebugLog.line(step, SystemClock.elapsedRealtime() - voiceT0, voiceEngine,
            MicSources.name(micSource)))
    }

    /**
     * POST raw WAV to /api/v1/voice/transcribe and parse the JSON response. Pairing mode asks for Whisper and
     * retries once with Vosk after a 503 (SttEngine); shared-token mode sends no engine header, as before.
     */
    private fun transcribeWavRaw(wav: ByteArray): TranscriptResult {
        val baseUrl = backendUrl ?: throw IllegalStateException("Backend URL is not configured.")
        val body = wav.toRequestBody("audio/wav".toMediaType())
        var engine = SttEngine.first(pairing.configured)
        while (true) {
            val builder = Request.Builder().url("$baseUrl$TRANSCRIBE_ENDPOINT").post(body)
            engine?.let { builder.header(SttEngine.HEADER, it) }
            val used = engine
            val result = okHttpClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val retry = SttEngine.fallbackAfter(used, response.code)
                    if (retry != null) {
                        engine = retry
                        return@use null
                    }
                }
                readTranscript(response, used)
            }
            if (result != null) return result
        }
    }

    /** Errors as before (pairing mode: only the status, never the body); success records the engine used. */
    private fun readTranscript(response: okhttp3.Response, engine: String?): TranscriptResult {
        val responseBody = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            if (pairing.configured) throw CallHttpException(response.code)  // Only the status, never the body.
            if (response.code == 401) {
                throw RuntimeException(authenticationMessage())
            }
            throw RuntimeException("Transcribe HTTP ${response.code} ${response.message ?: ""}")
        }
        val obj = org.json.JSONObject(responseBody)
        voiceEngine = engine
        return TranscriptResult(
            text = obj.optString("text", "").trim(),
            needsClarification = obj.optBoolean("needs_clarification", false),
            clarification = obj.optString("clarification", ""),
            basicEngine = SttEngine.isBasic(engine),
        )
    }

    /**
     * Chat request. Pairing mode: /api/v1/assistant/chat with the shared session (none on the first question: the
     * backend creates it). Shared-token mode: /api/v1/chat, as before.
     */
    private fun chatRequest(baseUrl: String, message: String): Request {
        val json = org.json.JSONObject().put("message", message)
        if (pairing.configured) assistantSession?.let { json.put("session_id", it) }
        return Request.Builder()
            .url(baseUrl + if (pairing.configured) ASSISTANT_ENDPOINT else CHAT_ENDPOINT)
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    /**
     * The answer, and in pairing mode the backend's `speech` (absent or null on an older backend: SpokenAnswer then
     * cuts the answer itself) and the returned session (AssistantSession validates it).
     */
    private fun answerOf(obj: org.json.JSONObject, body: String): AssistantReply {
        if (!pairing.configured) return AssistantReply(obj.optString("answer", body))
        val returned = if (obj.isNull("session_id")) null else obj.optString("session_id")
        assistantSession = AssistantSession.next(assistantSession, returned)
        val speech = if (obj.isNull("speech")) null else obj.optString("speech")
        return AssistantReply(obj.optString("answer", body), speech)
    }

    /** POST recognized text to the chat: the assistant in pairing mode, /api/v1/chat in shared-token mode. */
    private fun sendToOllama(message: String): AssistantReply {
        val baseUrl = backendUrl ?: throw IllegalStateException("Backend URL is not configured.")
        return okHttpClient.newCall(chatRequest(baseUrl, message)).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                if (pairing.configured) throw CallHttpException(response.code)  // Only the status, never the body.
                if (response.code == 401) {
                    throw RuntimeException(authenticationMessage())
                }
                throw RuntimeException("Chat HTTP ${response.code} ${response.message ?: ""}")
            }
            answerOf(org.json.JSONObject(responseBody), responseBody)
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
                // Pairing mode: the answer is already shown, only the discreet label; shared-token mode: status line.
                if (pairing.configured) showChange(chatLog.voiceUnspoken(voiceAnswer))
                else updateStatus(getString(R.string.voice_no_audio))
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
                        // Demo lentes: with the Bluetooth route the answer goes with the call audio (to the glasses).
                        .setUsage(if (glassesAudio.active) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                if (android.os.Build.VERSION.SDK_INT >= 28) glassesAudio.output?.let { setPreferredDevice(it) }
                if (GlassesSettings.enabled(this@MainActivity)) GlassesLog.d("playback route=${if (glassesAudio.active) "bluetooth" else "phone"}")
                setOnPreparedListener { mp ->
                    voiceFlow.on(VoiceEvent.AUDIO_READY)
                    voiceTiming("audio_start")
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
                    // Pairing mode: the answer is already shown, only the discreet label (no notice).
                    if (pairing.configured) showChange(chatLog.voiceUnspoken(voiceAnswer))
                    else updateStatus(getString(R.string.voice_playback_failed))
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