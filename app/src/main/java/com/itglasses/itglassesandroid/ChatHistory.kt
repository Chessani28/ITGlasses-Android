package com.itglasses.itglassesandroid

import java.util.Calendar
import java.util.TimeZone

/**
 * Chat history in the app (like a chat app's conversation list): the phone owner's own conversations, from the web,
 * this phone and the glasses. Pure Kotlin: the Activity parses the JSON and draws the list.
 *
 * Backend (exact POST routes, id in the body; Caddy publishes no path parameters and DPoP rejects query strings):
 *   /api/v1/devices/me/conversations/list  {cursor?}  -> {conversations: [...], next_cursor}
 *   /api/v1/devices/me/conversations/read  {id}       -> {conversation: {..., messages: [...]}}
 *   /api/v1/devices/me/conversations/open  {id}       the next messages (typed, voice, glasses) continue it
 *   /api/v1/devices/me/conversations/new   {}         the next message starts a new conversation
 */
internal object ChatHistory {
    const val LIST = "/api/v1/devices/me/conversations/list"
    const val READ = "/api/v1/devices/me/conversations/read"
    const val OPEN = "/api/v1/devices/me/conversations/open"
    const val NEW = "/api/v1/devices/me/conversations/new"
}

/** One row of the list. [lastMessageAt]: epoch seconds. [channel]: "web" or "device". */
internal data class HistoryItem(
    val id: String,
    val title: String?,
    val lastMessageAt: Long,
    val messageCount: Int,
    val channel: String,
    val deviceLabel: String?,
)

/** One saved message: [role] "user" or "assistant". */
internal data class HistoryMessage(val role: String, val content: String)

internal object HistoryTexts {
    private val months = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
    const val UNTITLED = "Conversación sin título"

    fun title(item: HistoryItem): String = item.title?.trim()?.takeIf { it.isNotEmpty() } ?: UNTITLED

    /** "Hoy 14:30 · Web · 4 mensajes". [now] in milliseconds; [zone] the phone's time zone. */
    fun subtitle(item: HistoryItem, now: Long, zone: TimeZone = TimeZone.getDefault()): String =
        listOf(whenText(item.lastMessageAt * 1000, now, zone), origin(item), count(item.messageCount)).joinToString(" · ")

    /** Where it was held: "Web", or the device's name ("Celular" when it has none). */
    fun origin(item: HistoryItem): String =
        if (item.channel == "web") "Web" else item.deviceLabel?.trim()?.takeIf { it.isNotEmpty() } ?: "Celular"

    fun count(messages: Int): String = if (messages == 1) "1 mensaje" else "$messages mensajes"

    /** "Hoy 14:30", "Ayer 09:05", "12 nov" (this year) or "12 nov 2025". */
    fun whenText(at: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val day = Calendar.getInstance(zone).apply { timeInMillis = at }
        val today = Calendar.getInstance(zone).apply { timeInMillis = now }
        val time = "%02d:%02d".format(day.get(Calendar.HOUR_OF_DAY), day.get(Calendar.MINUTE))
        if (sameDay(day, today)) return "Hoy $time"
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (sameDay(day, yesterday)) return "Ayer $time"
        val date = "${day.get(Calendar.DAY_OF_MONTH)} ${months[day.get(Calendar.MONTH)]}"
        return if (day.get(Calendar.YEAR) == today.get(Calendar.YEAR)) date else "$date ${day.get(Calendar.YEAR)}"
    }

    private fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}
