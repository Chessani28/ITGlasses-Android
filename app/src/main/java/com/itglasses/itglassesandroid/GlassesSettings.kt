package com.itglasses.itglassesandroid

import android.content.Context

/** Demo lentes: the "Usar lentes" switch. Off by default; off means the app behaves exactly as before. */
internal object GlassesSettings {
    private const val PREFS = "itglasses_glasses"
    private const val KEY_ENABLED = "use_glasses"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
