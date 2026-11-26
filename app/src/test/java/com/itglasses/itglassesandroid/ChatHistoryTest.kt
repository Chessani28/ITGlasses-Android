package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ChatHistoryTest {
    private val zone = TimeZone.getTimeZone("America/Mexico_City")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply { clear(); set(year, month - 1, day, hour, minute) }.timeInMillis

    private fun item(title: String? = "Impresora", channel: String = "device", label: String? = "Pixel 7",
                     count: Int = 4, last: Long = at(2026, 11, 26, 14, 30) / 1000) =
        HistoryItem("c1", title, last, count, channel, label)

    @Test fun datesReadLikeAChatApp() {
        val now = at(2026, 11, 26, 18, 0)
        assertEquals("Hoy 14:30", HistoryTexts.whenText(at(2026, 11, 26, 14, 30), now, zone))
        assertEquals("Ayer 09:05", HistoryTexts.whenText(at(2026, 11, 25, 9, 5), now, zone))
        assertEquals("3 nov", HistoryTexts.whenText(at(2026, 11, 3, 9, 5), now, zone))
        assertEquals("31 dic 2025", HistoryTexts.whenText(at(2025, 12, 31, 23, 0), now, zone))
        // Yesterday across a year change.
        assertEquals("Ayer 23:00", HistoryTexts.whenText(at(2025, 12, 31, 23, 0), at(2026, 1, 1, 8, 0), zone))
    }

    @Test fun theSubtitleSaysWhenWhereAndHowLong() {
        val now = at(2026, 11, 26, 18, 0)
        assertEquals("Hoy 14:30 · Pixel 7 · 4 mensajes", HistoryTexts.subtitle(item(), now, zone))
        assertEquals("Hoy 14:30 · Web · 1 mensaje", HistoryTexts.subtitle(item(channel = "web", count = 1), now, zone))
        assertEquals("Celular", HistoryTexts.origin(item(label = " ")))
    }

    @Test fun aConversationWithoutTitleStillHasOne() {
        assertEquals("Impresora", HistoryTexts.title(item()))
        assertEquals(HistoryTexts.UNTITLED, HistoryTexts.title(item(title = null)))
        assertEquals(HistoryTexts.UNTITLED, HistoryTexts.title(item(title = "  ")))
    }

    @Test fun openingAConversationReplacesWhatIsShown() {
        val log = ChatLog()
        val (old, _) = log.send("Hola")
        val change = log.replaceWith(listOf(
            HistoryMessage("user", "¿Qué es un router?"),
            HistoryMessage("assistant", "Un equipo que conecta redes."),
            HistoryMessage("system", "ignorado"),
            HistoryMessage("assistant", " "),
        ))
        assertTrue(old in change.removed)
        assertEquals(2, change.removed.size)                      // The question and its "Generando respuesta".
        val entries = log.entries
        assertEquals(2, entries.size)
        val user = entries[0] as ChatEntry.User
        assertEquals("¿Qué es un router?", user.text)
        assertEquals(ChatEntry.Status.SENT, user.status)
        assertEquals("Un equipo que conecta redes.", (entries[1] as ChatEntry.Assistant).text)
        assertEquals(entries, change.added)
    }

    @Test fun aLongConversationKeepsTheNewestMessages() {
        val log = ChatLog(limit = 3)
        val change = log.replaceWith((1..10).map { HistoryMessage(if (it % 2 == 1) "user" else "assistant", "m$it") })
        assertEquals(listOf("m8", "m9", "m10"), log.entries.map {
            when (it) { is ChatEntry.User -> it.text; is ChatEntry.Assistant -> it.text; else -> "" }
        })
        assertEquals(3, change.added.size)
    }

    @Test fun aNewConversationEmptiesTheChat() {
        val log = ChatLog()
        log.send("Hola")
        val change = log.clear()
        assertEquals(2, change.removed.size)
        assertTrue(log.isEmpty)
        // After it, messages work as usual.
        val (_, sent) = log.send("Otra")
        assertEquals(2, sent.added.size)
    }
}
