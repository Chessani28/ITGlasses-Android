package com.itglasses.itglassesandroid

import android.content.Context
import android.os.Build
import android.provider.Settings

/** Name and model sent with the pairing request (both optional). Android-only: verified on the phone. */
internal object DeviceInfo {
    /** Settings.Global.DEVICE_NAME exists from API 25 (N_MR1); minSdk is 24. */
    fun name(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return null
        val raw = try {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (_: RuntimeException) {
            null
        }
        return DeviceLabel.name(raw)
    }

    fun model(): String? = DeviceLabel.model(Build.MANUFACTURER, Build.MODEL)
}
