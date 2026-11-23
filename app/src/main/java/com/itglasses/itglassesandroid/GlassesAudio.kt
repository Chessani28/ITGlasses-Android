package com.itglasses.itglassesandroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Demo lentes: microphone and spoken answer through a Bluetooth headset (the Ray-Ban Meta connect as one, HFP/SCO).
 * API 31+: AudioManager.setCommunicationDevice; older versions: startBluetoothSco. [acquire] blocks until the route
 * is active or ROUTE_TIMEOUT_MS passes, so never call it on the UI thread. [release] clears the route and restores
 * the previous audio mode. Neither throws: on any failure the phone's microphone and speaker stay in use.
 */
internal class GlassesAudio(context: Context) {
    private val app = context.applicationContext
    private val audio: AudioManager? = app.getSystemService(AudioManager::class.java)
    private var previousMode: Int? = null
    private var legacySco = false

    /** The Bluetooth route is active (until [release]). */
    @Volatile var active = false
        private set
    /** The glasses' microphone, for AudioRecord.setPreferredDevice (null: the route alone decides). */
    @Volatile var input: AudioDeviceInfo? = null
        private set
    /** The glasses' speaker, for MediaPlayer.setPreferredDevice (null: the route alone decides). */
    @Volatile var output: AudioDeviceInfo? = null
        private set

    /** True when the Bluetooth route is active. Off the UI thread only. */
    @Synchronized
    fun acquire(): Boolean {
        if (active) return true
        val am = audio ?: return false.also { GlassesLog.d("acquire result=no_audio_manager") }
        val started = SystemClock.elapsedRealtime()
        GlassesLog.d("acquire start api=${Build.VERSION.SDK_INT}")
        return try {
            previousMode = am.mode
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            val routed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) routeModern(am) else routeLegacy(am)
            if (!routed) {
                GlassesLog.d("acquire result=no_route ms=${SystemClock.elapsedRealtime() - started}")
                release()
                return false
            }
            Thread.sleep(SETTLE_MS)  // The first milliseconds of a new SCO link are often silent.
            input = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type in BLUETOOTH_TYPES }
            output = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in BLUETOOTH_TYPES }
            active = true
            GlassesLog.d("acquire result=ok ms=${SystemClock.elapsedRealtime() - started} input=${input?.type ?: "none"} " +
                "output=${output?.type ?: "none"}")
            true
        } catch (e: Exception) {
            GlassesLog.d("acquire result=error type=${GlassesLog.safe(e.javaClass.simpleName)}")
            release()
            false
        }
    }

    /** Back to the phone's microphone and speaker. Safe to call at any time, any number of times. */
    @Synchronized
    fun release() {
        val am = audio ?: return
        if (!active && previousMode == null) return
        GlassesLog.d("release was_active=$active")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else if (legacySco) {
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                am.stopBluetoothSco()
            }
        } catch (_: Exception) {
        }
        previousMode?.let { mode -> try { am.mode = mode } catch (_: Exception) { } }
        previousMode = null
        legacySco = false
        active = false
        input = null
        output = null
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun routeModern(am: AudioManager): Boolean {
        val available = am.availableCommunicationDevices
        GlassesLog.d("modern devices=${available.joinToString(",") { it.type.toString() }}")
        val device = available.firstOrNull { it.type in BLUETOOTH_TYPES }
            ?: return false.also { GlassesLog.d("modern result=no_bluetooth_device") }
        if (!am.setCommunicationDevice(device)) return false.also { GlassesLog.d("modern result=set_refused") }
        val deadline = SystemClock.elapsedRealtime() + ROUTE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (am.communicationDevice?.id == device.id) return true
            Thread.sleep(POLL_MS)
        }
        GlassesLog.d("modern result=timeout")
        return false
    }

    @Suppress("DEPRECATION")
    private fun routeLegacy(am: AudioManager): Boolean {
        if (!am.isBluetoothScoAvailableOffCall) return false.also { GlassesLog.d("legacy result=sco_unavailable") }
        val headset = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
        if (!headset) return false.also { GlassesLog.d("legacy result=no_headset") }
        val connected = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) connected.countDown()
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
            ContextCompat.RECEIVER_EXPORTED)
        return try {
            legacySco = true
            am.startBluetoothSco()
            am.isBluetoothScoOn = true
            connected.await(ROUTE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .also { GlassesLog.d("legacy result=${if (it) "connected" else "timeout"}") }
        } finally {
            try { app.unregisterReceiver(receiver) } catch (_: Exception) { }
        }
    }

    companion object {
        /** Longest wait for the Bluetooth route before falling back to the phone. */
        const val ROUTE_TIMEOUT_MS = 3000L
        const val SETTLE_MS = 300L
        private const val POLL_MS = 100L
        /** Classic Bluetooth headset (SCO) and, on API 31+, LE Audio headsets (TYPE_BLE_HEADSET = 26). */
        private val BLUETOOTH_TYPES = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 26)
    }
}

/**
 * Demo lentes: debug-only diagnostics in logcat (tag ITG-Glasses): route steps, results, device types (numbers) and
 * timings. Never audio, text, device names or addresses.
 */
internal object GlassesLog {
    const val TAG = "ITG-Glasses"
    private val unsafe = Regex("[^A-Za-z0-9_]")

    fun d(line: String) {
        if (!BuildConfig.DEBUG) return
        Log.d(TAG, line)
    }

    fun safe(value: String): String = value.replace(unsafe, "").take(40).ifEmpty { "Unknown" }
}
