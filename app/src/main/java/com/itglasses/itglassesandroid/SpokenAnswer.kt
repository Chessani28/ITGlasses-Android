package com.itglasses.itglassesandroid

/** An assistant answer: [text] for the bubble, [speech] (backend field, may be absent) for the voice. */
internal data class AssistantReply(val text: String, val speech: String? = null)

/**
 * What the voice reads of an answer in pairing mode. The backend's `speech` when it has text; otherwise the answer
 * through [SpokenText], cut at the last complete sentence before [MAX_CHARS] plus "El resto está en el chat.".
 * The bubble always keeps the whole answer. Pure Kotlin.
 */
internal object SpokenAnswer {
    const val MAX_CHARS = 450
    const val REST = "El resto está en el chat."
    private val sentenceEnd = Regex("""[.!?…](?=\s|$)""")

    fun of(answer: String, speech: String?): String {
        if (!speech.isNullOrBlank()) return SpokenText.of(speech)
        return cap(SpokenText.of(answer))
    }

    /** [text] as it is up to [max] characters; longer, its last whole sentence (or word) before [max] + [REST]. */
    fun cap(text: String, max: Int = MAX_CHARS): String {
        val trimmed = text.trim()
        if (trimmed.length <= max) return trimmed
        val window = trimmed.substring(0, max)
        val end = sentenceEnd.findAll(window).lastOrNull()?.range?.last
        val head = if (end != null) {
            window.substring(0, end + 1)
        } else {
            window.substringBeforeLast(' ', window).trimEnd(',', ';', ':', ' ') + "."
        }
        return "$head $REST"
    }
}
