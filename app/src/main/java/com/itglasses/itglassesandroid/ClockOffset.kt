package com.itglasses.itglassesandroid

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Difference between the server clock and the phone clock, taken from the Date header of responses from the
 * validated HTTPS origin. Used for the "iat" of DPoP proofs, so the backend's +-60 s window does not depend on
 * the phone's time. Pure Kotlin (no android.*); java.text instead of java.time because minSdk is 24.
 * If the header never arrives, the offset stays 0 and the clock error message covers it.
 */
internal class ClockOffset(
    private val deadZoneSeconds: Long = 5,
    private val maxSkewSeconds: Long = 86_400,
) {
    enum class Observation { MISSING, UNCHANGED, ADJUSTED, IMPLAUSIBLE }

    @Volatile var seconds: Long = 0L
        private set

    /** Server time in epoch seconds, for "iat". */
    fun serverNow(localMillis: Long): Long = Math.floorDiv(localMillis, 1000L) + seconds

    /** ADJUSTED means a rejected proof is worth one retry; IMPLAUSIBLE leaves the offset unchanged. */
    @Synchronized fun observe(dateHeader: String?, localMillis: Long): Observation {
        val server = parseHttpDate(dateHeader) ?: return Observation.MISSING
        val raw = server - Math.floorDiv(localMillis, 1000L)
        if (abs(raw) > maxSkewSeconds) return Observation.IMPLAUSIBLE
        if (abs(raw - seconds) <= deadZoneSeconds) return Observation.UNCHANGED
        seconds = if (abs(raw) <= deadZoneSeconds) 0L else raw
        return Observation.ADJUSTED
    }

    companion object {
        /** RFC 7231 IMF-fixdate ("Tue, 17 Nov 2026 16:20:00 GMT") -> epoch seconds, or null. */
        fun parseHttpDate(value: String?): Long? {
            val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
                isLenient = false
            }
            val position = ParsePosition(0)
            val date = format.parse(text, position) ?: return null
            if (position.index != text.length) return null
            return Math.floorDiv(date.time, 1000L)
        }
    }
}
