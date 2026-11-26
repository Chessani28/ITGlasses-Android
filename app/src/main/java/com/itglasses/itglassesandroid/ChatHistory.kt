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

/** The chat request's "via": "glasses" when the question was heard through the glasses; nothing otherwise. */
internal object ChatVia {
    fun of(glasses: Boolean): String? = if (glasses) "glasses" else null
}

/** Where a conversation's questions were asked: their icons in the list (phone, glasses or both). */
internal enum class HistoryOrigin { PHONE, GLASSES, BOTH }

/**
 * One row of the list (only this phone's conversations). [lastMessageAt]: epoch seconds. [phoneQuestions] and
 * [glassesQuestions]: the backend's "sources" (questions asked on the phone and through the glasses).
 */
internal data class HistoryItem(
    val id: String,
    val title: String?,
    val lastMessageAt: Long,
    val messageCount: Int,
    val phoneQuestions: Int = 0,
    val glassesQuestions: Int = 0,
) {
    val origin: HistoryOrigin get() = when {
        glassesQuestions > 0 && phoneQuestions > 0 -> HistoryOrigin.BOTH
        glassesQuestions > 0 -> HistoryOrigin.GLASSES
        else -> HistoryOrigin.PHONE
    }
}

/** A section of the list: "Hoy", "Ayer", "Últimos 7 días", "Este mes" or "Anteriores", newest first. */
internal data class HistoryGroup(val label: String, val items: List<HistoryItem>)

/** One saved message: [role] "user" or "assistant". */
internal data class HistoryMessage(val role: String, val content: String)

internal object HistoryTexts {
    private val months = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
    const val UNTITLED = "Conversación sin título"

    fun title(item: HistoryItem): String = item.title?.trim()?.takeIf { it.isNotEmpty() } ?: UNTITLED

    /** "14:30 · 4 mensajes" (today and yesterday: the group says the day) or "3 nov · 4 mensajes". */
    fun subtitle(item: HistoryItem, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val stamp = whenText(item.lastMessageAt * 1000, now, zone).removePrefix("Hoy ").removePrefix("Ayer ")
        return "$stamp · ${count(item.messageCount)}"
    }

    /** Spoken description of the icons, for TalkBack. */
    fun originText(origin: HistoryOrigin): String = when (origin) {
        HistoryOrigin.PHONE -> "En el celular"
        HistoryOrigin.GLASSES -> "Con los lentes"
        HistoryOrigin.BOTH -> "En el celular y con los lentes"
    }

    /** The list in sections by the last message's day, keeping the backend's order (newest first). */
    fun groups(items: List<HistoryItem>, now: Long, zone: TimeZone = TimeZone.getDefault()): List<HistoryGroup> {
        val today = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        fun daysBefore(days: Int) = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -days) }.timeInMillis
        val month = (today.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        val labels = items.map { item ->
            val at = item.lastMessageAt * 1000
            when {
                at >= today.timeInMillis -> "Hoy"
                at >= daysBefore(1) -> "Ayer"
                at >= daysBefore(7) -> "Últimos 7 días"
                at >= month -> "Este mes"
                else -> "Anteriores"
            }
        }
        val groups = mutableListOf<HistoryGroup>()
        items.zip(labels).forEach { (item, label) ->
            if (groups.lastOrNull()?.label == label) groups[groups.size - 1] = groups.last().copy(items = groups.last().items + item)
            else groups += HistoryGroup(label, listOf(item))
        }
        return groups
    }

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
