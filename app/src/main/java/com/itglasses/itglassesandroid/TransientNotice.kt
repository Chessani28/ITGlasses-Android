package com.itglasses.itglassesandroid

/**
 * A status-line notice that lasts durationMillis. show() returns a ticket; after the delay the caller clears the
 * line only if expire(ticket) is true, so an older timer never wipes a newer notice. Pure Kotlin; clock injected.
 */
internal class TransientNotice(
    private val durationMillis: Long = 8_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var text: String? = null
    private var until = 0L
    private var ticket = 0L

    @Synchronized fun show(value: String): Long {
        text = value
        until = clock() + durationMillis
        return ++ticket
    }

    /** The notice still on screen, or null once it has expired. */
    @Synchronized fun current(): String? = text?.takeIf { clock() < until }

    /** True if this ticket is still the latest notice: the caller then clears the status line. */
    @Synchronized fun expire(ticket: Long): Boolean {
        if (ticket != this.ticket) return false
        text = null
        return true
    }

    val durationMs: Long get() = durationMillis
}
