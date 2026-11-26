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

    private fun item(title: String? = "Impresora", count: Int = 4, last: Long = at(2026, 11, 26, 14, 30) / 1000,
                     phone: Int = 2, glasses: Int = 0, id: String = "c1") =
        HistoryItem(id, title, last, count, phone, glasses)

    @Test fun datesReadLikeAChatApp() {
        val now = at(2026, 11, 26, 18, 0)
        assertEquals("Hoy 14:30", HistoryTexts.whenText(at(2026, 11, 26, 14, 30), now, zone))
        assertEquals("Ayer 09:05", HistoryTexts.whenText(at(2026, 11, 25, 9, 5), now, zone))
        assertEquals("3 nov", HistoryTexts.whenText(at(2026, 11, 3, 9, 5), now, zone))
        assertEquals("31 dic 2025", HistoryTexts.whenText(at(2025, 12, 31, 23, 0), now, zone))
        // Yesterday across a year change.
        assertEquals("Ayer 23:00", HistoryTexts.whenText(at(2025, 12, 31, 23, 0), at(2026, 1, 1, 8, 0), zone))
    }

    @Test fun theSubtitleSaysWhenAndHowLongTheGroupSaysTheDay() {
        val now = at(2026, 11, 26, 18, 0)
        assertEquals("14:30 · 4 mensajes", HistoryTexts.subtitle(item(), now, zone))
        assertEquals("09:05 · 1 mensaje", HistoryTexts.subtitle(item(count = 1, last = at(2026, 11, 25, 9, 5) / 1000), now, zone))
        assertEquals("3 nov · 4 mensajes", HistoryTexts.subtitle(item(last = at(2026, 11, 3, 9, 5) / 1000), now, zone))
    }

    @Test fun theIconSaysWhereTheQuestionsWereAsked() {
        assertEquals(HistoryOrigin.PHONE, item(phone = 3, glasses = 0).origin)
        assertEquals(HistoryOrigin.GLASSES, item(phone = 0, glasses = 2).origin)
        assertEquals(HistoryOrigin.BOTH, item(phone = 1, glasses = 1).origin)
        assertEquals(HistoryOrigin.PHONE, item(phone = 0, glasses = 0).origin)   // no questions counted: the phone
        assertEquals("Con los lentes", HistoryTexts.originText(HistoryOrigin.GLASSES))
    }

    @Test fun onlyQuestionsHeardThroughTheGlassesSayVia() {
        assertEquals("glasses", ChatVia.of(true))
        assertEquals(null, ChatVia.of(false))
    }

    @Test fun theListIsGroupedByDayKeepingItsOrder() {
        val now = at(2026, 11, 26, 18, 0)
        val items = listOf(
            item(id = "a", last = at(2026, 11, 26, 9, 0) / 1000),
            item(id = "b", last = at(2026, 11, 26, 8, 0) / 1000),
            item(id = "c", last = at(2026, 11, 25, 20, 0) / 1000),
            item(id = "d", last = at(2026, 11, 21, 20, 0) / 1000),
            item(id = "e", last = at(2026, 11, 2, 20, 0) / 1000),
            item(id = "f", last = at(2026, 10, 30, 20, 0) / 1000),
        )
        val groups = HistoryTexts.groups(items, now, zone)
        assertEquals(listOf("Hoy", "Ayer", "Últimos 7 días", "Este mes", "Anteriores"), groups.map { it.label })
        assertEquals(listOf("a", "b"), groups[0].items.map { it.id })
        assertEquals(listOf("f"), groups.last().items.map { it.id })
        assertTrue(HistoryTexts.groups(emptyList(), now, zone).isEmpty())
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
