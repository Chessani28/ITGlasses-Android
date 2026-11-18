package com.itglasses.itglassesandroid

/**
 * What the voice reads of an assistant answer (pairing mode). The bubble keeps the full text with its sources;
 * the voice leaves out "[Fuente: …]", "Fuente oficial: …", the "Fuentes … consultadas:" block, links and markdown,
 * and ends every line with a pause so list items are not run together. Pure Kotlin.
 */
internal object SpokenText {
    private val sourcesBlock = Regex("""(?m)^[ \t]*Fuentes(?: públicas)? consultadas(?: \(extractos\))?:[ \t]*$""")
    private val citation = Regex("""[ \t]*\[Fuente:[^\]]*]""")
    private val officialSource = Regex("""(?m)^[ \t]*Fuente oficial:.*$""")
    private val link = Regex("""\[([^\]]+)]\(https?://[^)\s]*\)""")
    private val url = Regex("""https?://\S+""")
    private val bullet = Regex("""(?m)^[ \t]*[-*•][ \t]+""")
    private val emphasis = Regex("""\*\*|__|`""")
    private const val PAUSES = ".:;!?…"

    fun of(answer: String): String {
        var text = sourcesBlock.find(answer)?.let { answer.substring(0, it.range.first) } ?: answer
        text = citation.replace(text, "")
        text = officialSource.replace(text, "")
        text = link.replace(text) { it.groupValues[1] }
        text = url.replace(text, "")
        text = bullet.replace(text, "")
        text = emphasis.replace(text, "")
        val spoken = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(" ") { if (it.last() in PAUSES) it else "$it." }
        // Nothing left (an answer made only of sources): read it as it is rather than stay silent.
        return spoken.ifBlank { answer.trim() }
    }
}
