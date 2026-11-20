package com.itglasses.itglassesandroid

/**
 * Historial del chat de texto en pantalla. Antes crecía sin límite dentro de un
 * TextView; ahora conserva solo los mensajes más recientes.
 */
internal object ChatTranscript {
    const val SEPARATOR = "\n\n"
    const val MAX_CHARS = 8000

    fun append(current: String, entry: String, maxChars: Int = MAX_CHARS): String {
        val entries = current.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() } +
            entry.trim()
        val kept = ArrayDeque<String>()
        var size = 0
        for (item in entries.asReversed()) {
            val added = item.length + if (kept.isEmpty()) 0 else SEPARATOR.length
            // El mensaje más reciente siempre se muestra, aunque sea largo.
            if (kept.isNotEmpty() && size + added > maxChars) break
            kept.addFirst(item)
            size += added
        }
        return kept.joinToString(SEPARATOR)
    }
}
