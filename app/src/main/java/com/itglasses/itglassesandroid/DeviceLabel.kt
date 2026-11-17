package com.itglasses.itglassesandroid

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

/**
 * Name, model and pairing code as shown to people. Pure Kotlin (no android.*).
 * clean() mirrors backend/app/pairing/routes.py label(): NFC, trimmed, 1 to 64 code points, no control (Cc),
 * format (Cf), line (Zl) or paragraph (Zp) characters. Here they are removed instead of rejected, so the phone
 * never gets a 422 for its own name.
 */
internal object DeviceLabel {
    const val MAX_CODE_POINTS = 64
    private val SIX_DIGITS = Regex("[0-9]{6}")
    private val FORBIDDEN = setOf(
        Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR,
        Character.PARAGRAPH_SEPARATOR, Character.SURROGATE,
    ).map { it.toInt() }.toSet()

    /** deviceName: Settings.Global.DEVICE_NAME (API 25+); the caller guards the version. */
    fun name(deviceName: String?): String? = clean(deviceName)

    /** "Samsung SM-S911B", "Google Pixel 7"; "OnePlus 9" stays as is (no repeated manufacturer). */
    fun model(manufacturer: String?, model: String?): String? {
        val maker = clean(manufacturer)?.let(::capitalize)
        val device = clean(model)
        val joined = when {
            maker == null -> device
            device == null -> maker
            device.equals(maker, ignoreCase = true) || device.startsWith("$maker ", ignoreCase = true) -> device
            else -> "$maker $device"
        }
        return clean(joined)
    }

    /** null when nothing usable is left: the field is then omitted. */
    fun clean(raw: String?): String? {
        if (raw == null) return null
        val kept = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val codePoint = raw.codePointAt(i)  // A lone surrogate comes back as itself (type SURROGATE).
            if (Character.getType(codePoint) !in FORBIDDEN) kept.appendCodePoint(codePoint)
            i += Character.charCount(codePoint)
        }
        var value = Normalizer.normalize(kept, Normalizer.Form.NFC).trim()
        value = Normalizer.normalize(truncate(value), Normalizer.Form.NFC).trim()
        return value.ifEmpty { null }
    }

    /** "482913" -> "482 913", like the web panel; anything else is shown unchanged. */
    fun formatCode(code: String): String =
        if (SIX_DIGITS.matches(code)) code.substring(0, 3) + " " + code.substring(3) else code

    /** "482913" -> "4 8 2 9 1 3", for the screen reader. */
    fun spokenCode(code: String): String = code.toList().joinToString(" ")

    /** Seconds left -> "m:ss" (never negative). */
    fun countdown(seconds: Long): String {
        val left = seconds.coerceAtLeast(0)
        return String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60)
    }

    private fun capitalize(value: String): String =
        value.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

    /** Cut at a character (grapheme) boundary so an emoji or accented letter is never split. */
    private fun truncate(value: String): String {
        if (value.codePointCount(0, value.length) <= MAX_CODE_POINTS) return value
        val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(value) }
        var end = 0
        var next = boundaries.next()
        while (next != BreakIterator.DONE && value.codePointCount(0, next) <= MAX_CODE_POINTS) {
            end = next
            next = boundaries.next()
        }
        if (end == 0) end = value.offsetByCodePoints(0, MAX_CODE_POINTS)
        return value.substring(0, end)
    }
}
