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
import androidx.lifecycle.lifecycleScope
import com.itglasses.itglassesandroid.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
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
    private var pairingRunning = false
    private val legacyAuth = AuthInterceptor { activeToken }
    private val backendUrl: String?
        get() = if (pairing.managed) pairing.origin else BackendConfiguration.getBaseUrl(this)
    private val STATUS_ENDPOINT = "/api/v1/status"
    private val CHAT_ENDPOINT = "/api/v1/chat"
    private val TRANSCRIBE_ENDPOINT = "/api/v1/voice/transcribe"
    private val SYNTHESIZE_ENDPOINT = "/api/v1/voice/synthesize"

    @Volatile private var activeToken: String? = null

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                if (pairing.managed) {
                    try {
                        pairing.authorize(chain).also { response ->
                            if (response.code == 401) runOnUiThread { runPairing() }
                        }
                    } catch (e: PairingError) {
                        if (e.status == 401) runOnUiThread { runPairing() }
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
            binding.btnVoice.isEnabled = !busy
            binding.tvStatus.text = when (state) {
                UiState.Recording -> "Recording..."
                UiState.Transcribing -> "Transcribing..."
                UiState.Thinking -> "Thinking..."
                UiState.Synthesizing -> "Synthesizing..."
                UiState.Speaking -> "Speaking..."
                UiState.Ready -> "Ready"
                UiState.Error -> "Error"
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
                updateStatus("Microphone permission denied")
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

        binding.btnPair.setOnClickListener { runPairing() }
        pairingControls()
        if (pairing.id.isNotEmpty()) runPairing()
        else if (!pairing.managed && (activeToken != null || !pairing.configured)) connectToBackend()
    }

    private fun pairingControls() {
        val legacy = !pairing.managed && (!activeToken.isNullOrBlank() || !pairing.configured || BackendConfiguration.hasCustomUrl(this))
        val visibility = if (legacy) android.view.View.VISIBLE else android.view.View.GONE
        for (view in listOf(binding.etBackendUrl, binding.etApiToken, binding.btnConnect,
            binding.btnDisconnect, binding.tvBackendUrl)) view.visibility = visibility
        binding.btnSend.isEnabled = legacy
        binding.btnVoice.isEnabled = legacy
        binding.btnPair.isEnabled = pairing.configured
        if (!pairing.configured) binding.tvPairing.text = "Pendiente de configurar HTTPS en la APK"
    }

    private fun runPairing() {
        if (pairingRunning || isDestroyed) return
        pairingRunning = true
        binding.btnPair.isEnabled = false
        lifecycleScope.launch {
            try {
                if (pairing.id.isEmpty()) kotlinx.coroutines.withContext(Dispatchers.IO) { pairing.start() }
                while (true) {
                    if (pairing.code.isNotEmpty()) binding.tvPairing.text = "Esperando aprobación · " + pairing.code
                    val state = kotlinx.coroutines.withContext(Dispatchers.IO) { pairing.poll() }
                    if (state == "active") {
                        BackendConfiguration.saveToken(this@MainActivity, null)
                        activeToken = null
                        binding.etApiToken.text?.clear()
                        pairingControls()
                        binding.btnPair.isEnabled = false
                        binding.tvPairing.text = "Conectado · " + pairing.id.take(8)
                        binding.btnSend.isEnabled = true
                        binding.btnVoice.isEnabled = true
                        break
                    }
                    if (state != "pending") {
                        pairing.forgetRequest()
                        pairingControls()
                        binding.tvPairing.text = when (state) {
                            "denied" -> "Solicitud rechazada"
                            "revoked" -> "Dispositivo revocado"
                            else -> "Solicitud vencida"
                        }
                        break
                    }
                    kotlinx.coroutines.delay(5000)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if ((e is PairingError && e.status == 410) || !pairing.hasIdentity()) pairing.forgetRequest()
                pairingControls()
                binding.tvPairing.text = "No se pudo verificar el dispositivo. Revisa la conexión y la hora, y vuelve a emparejar."
            } finally {
                pairingRunning = false
            }
        }
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
        updateStatus("Generating response...")
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
                            updateStatus("Connected")
                        } else if (response.code == 401) {
                            binding.tvChatOutput.text = "${binding.tvChatOutput.text}\nError: ${authenticationMessage()}"
                            updateStatus("Authentication failed (HTTP 401)")
                        } else {
                            binding.tvChatOutput.text = "${binding.tvChatOutput.text}\nError: HTTP ${response.code} ${responseBody ?: ""}"
                            updateStatus("Connected")
                        }
                        binding.btnSend.isEnabled = true
                    }
                }
            } catch (e: Exception) {
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
            updateStatus("Microphone permission denied")
            return
        }

        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (bufferSize <= 0) {
            updateStatus("AudioRecord unsupported")
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
            updateStatus("AudioRecord init failed")
            audioRecord?.release()
            audioRecord = null
            return
        }

        recordedPcm.reset()
        isRecording = true
        recordingStartMs = System.currentTimeMillis()
        binding.btnVoice.text = "Stop"
        updateStatus("Recording... 0s")
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
                        binding.btnVoice.text = "Voice"
                        finishRecording()
                    }
                    break
                }

                // Recording state and duration display, updated once per second.
                if (now - lastStatusMs >= 1000) {
                    lastStatusMs = now
                    runOnUiThread { updateStatus("Recording... ${elapsed / 1000}s") }
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

        binding.btnVoice.text = "Voice"
        val pcm = synchronized(recordedPcm) { recordedPcm.toByteArray() }
        if (pcm.isEmpty()) {
            updateStatus("Error: empty recording")
            return
        }

        updateStatus("Saving...")
        val wav = pcmToWav(pcm, 16000, 1, 16)
        try {
            // Temporary private app storage (cache dir).
            val file = File(cacheDir, "recording.wav")
            FileOutputStream(file).use { it.write(wav) }
            val duration = pcm.size / (16000 * 2)
            updateStatus("Saved: ${file.name} ${file.length()} bytes, ~${duration}s")
            // Run the full voice pipeline off the UI thread:
            // transcribe (Vosk) → Ollama chat → Piper synthesis → MediaPlayer playback.
            isProcessing = true
            binding.btnVoice.isEnabled = false
            setState(UiState.Transcribing)
            runVoicePipeline(wav)
        } catch (e: Exception) {
            updateStatus("Error: ${e.message}")
            isProcessing = false
            binding.btnVoice.isEnabled = true
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
                        updateStatus("Completed: no speech recognized")
                    }
                    return@launch
                }

                // Do not send silence or low-quality transcriptions automatically when
                // the backend requests clarification.
                if (transcript.needsClarification) {
                    runOnUiThread {
                        binding.tvTranscription.text = transcript.clarification
                        setState(UiState.Ready)
                        updateStatus("Clarification required")
                    }
                    return@launch
                }

                // 2. Ollama chat.
                runOnUiThread { setState(UiState.Thinking) }
                val answer = sendToOllama(transcript.text)
                runOnUiThread {
                    binding.tvAiResponse.text = answer
                    updateStatus("AI response received")
                }

                // 3. Piper synthesis.
                runOnUiThread { setState(UiState.Synthesizing) }
                val audioBytes = synthesizeSpeech(answer)

                // 4. MediaPlayer playback.
                playAudio(audioBytes)

            } catch (e: Exception) {
                runOnUiThread {
                    binding.tvAiResponse.text = getString(R.string.transcription_error)
                    setState(UiState.Error)
                    updateStatus("Error: ${e.javaClass.simpleName}: ${e.message ?: ""}")
                }
            } finally {
                runOnUiThread {
                    isProcessing = false
                    binding.btnVoice.isEnabled = true
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
                updateStatus("No audio to play")
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
                    updateStatus("Playing response")
                }
                setOnCompletionListener {
                    releaseMediaPlayer()
                    setState(UiState.Ready)
                    updateStatus("Playback complete")
                }
                setOnErrorListener { _, _, _ ->
                    releaseMediaPlayer()
                    setState(UiState.Error)
                    updateStatus("Playback error")
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