package com.itglasses.itglassesandroid

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build

/** Reglas puras (sin Android) para elegir el micrófono; se prueban en JVM. */
internal object MicrophonePolicy {
    // AudioDeviceInfo.TYPE_BLUETOOTH_SCO = 7, TYPE_BLE_HEADSET = 26 (API 31).
    const val TYPE_BLUETOOTH_SCO = 7
    const val TYPE_BLE_HEADSET = 26

    /** Primer micrófono Bluetooth (lentes / audífonos) de la lista, o null. */
    fun pickBluetooth(types: List<Int>): Int? =
        types.indexOfFirst { it == TYPE_BLUETOOTH_SCO || it == TYPE_BLE_HEADSET }.takeIf { it >= 0 }

    fun label(deviceName: String?): String =
        deviceName?.trim()?.takeIf { it.isNotEmpty() }?.let { "micrófono de $it" } ?: "micrófono Bluetooth"
}

/**
 * Enruta la grabación al micrófono de los lentes Ray-Ban Meta, que el teléfono
 * ve como un dispositivo Bluetooth de llamadas (SCO/BLE). Si no hay lentes
 * conectados, o en Android 11 o anterior, se usa el micrófono del teléfono.
 *
 * Mientras se graba, la ruta de comunicación queda activa; al terminar se
 * libera para que la respuesta se reproduzca por audio multimedia (mejor
 * calidad, y también sale por las bocinas de los lentes).
 */
internal class GlassesMicrophone(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousMode: Int? = null
    private var activeDevice: AudioDeviceInfo? = null

    /** Descripción del micrófono en uso, para mostrarla en pantalla. */
    var currentLabel: String = PHONE_LABEL
        private set

    val usingGlasses: Boolean get() = activeDevice != null

    /**
     * Intenta activar el micrófono Bluetooth. Devuelve true si quedó activo.
     * Llamar a [release] al terminar de grabar, aunque devuelva false.
     */
    fun acquire(): Boolean {
        release()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try {
            val devices = audioManager.availableCommunicationDevices
            val index = MicrophonePolicy.pickBluetooth(devices.map { it.type }) ?: return false
            val device = devices[index]
            previousMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (!audioManager.setCommunicationDevice(device)) {
                restoreMode()
                return false
            }
            activeDevice = device
            currentLabel = MicrophonePolicy.label(device.productName?.toString())
            true
        } catch (_: RuntimeException) {
            // SecurityException / IllegalArgumentException: se usa el teléfono.
            release()
            false
        }
    }

    /** Pide a AudioRecord que lea del micrófono Bluetooth activo. */
    fun preferFor(record: AudioRecord) {
        val wanted = activeDevice ?: return
        val input = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == wanted.type && it.address == wanted.address }
            ?: audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == wanted.type }
        if (input != null) record.setPreferredDevice(input)
    }

    /** Espera (máx. [timeoutMs]) a que el enlace Bluetooth quede listo. */
    fun awaitReady(timeoutMs: Long = 1500) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val wanted = activeDevice ?: return
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (audioManager.communicationDevice?.id == wanted.id) return
            Thread.sleep(50)
        }
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && activeDevice != null) {
            try { audioManager.clearCommunicationDevice() } catch (_: RuntimeException) { }
        }
        activeDevice = null
        currentLabel = PHONE_LABEL
        restoreMode()
    }

    private fun restoreMode() {
        previousMode?.let { mode ->
            try { audioManager.mode = mode } catch (_: RuntimeException) { }
        }
        previousMode = null
    }

    companion object {
        const val PHONE_LABEL = "micrófono del teléfono"
    }
}
