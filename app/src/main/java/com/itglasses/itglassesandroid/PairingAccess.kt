package com.itglasses.itglassesandroid

import android.content.Context

/** One value per key for the whole process, created on first use. Pure and thread-safe. */
internal class ProcessSingletons<K, V> {
    private val values = HashMap<K, V>()

    @Synchronized
    fun get(key: K, create: (K) -> V): V = values.getOrPut(key) { create(key) }
}

/**
 * One DevicePairing per origin for the whole process (the app screen and VoiceForegroundService). The server keeps one
 * access token per device: two instances would renew it against each other and answer 401 by turns. DevicePairing
 * itself is unchanged; this only decides how many instances exist.
 */
internal object PairingAccess {
    private val instances = ProcessSingletons<Pair<String, String?>, DevicePairing>()

    fun get(context: Context, origin: String, pin: String? = null): DevicePairing =
        instances.get(origin to pin) { DevicePairing(context.applicationContext, it.first, it.second) }

    /** The server this phone uses: the one scanned from a QR (ServerStore), else the APK's built-in origin. */
    fun current(context: Context): DevicePairing =
        get(context, ServerStore.origin(context), ServerStore.pin(context))
}
