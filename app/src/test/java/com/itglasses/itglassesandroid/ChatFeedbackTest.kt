package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 👍/👎 on the assistant's answers (2026-11-26): the answer keeps its saved id and the user's vote. */
class ChatFeedbackTest {
    @Test fun anAnswerKeepsItsSavedId() {
        val log = ChatLog()
        val (message, _) = log.send("¿Qué es una VPN?")
        log.answered(message, "Un túnel cifrado.", messageId = 42)
        val answer = log.entries.last() as ChatEntry.Assistant
        assertEquals(42L, answer.messageId)
        assertNull(answer.vote)
        val (voice, _) = log.voiceStarted()
        log.voiceTranscribed(voice, "¿Y un proxy?")
        log.voiceAnswered(voice, "Un intermediario.", messageId = 44)
        assertEquals(44L, (log.entries.last() as ChatEntry.Assistant).messageId)
    }

    @Test fun anUnsavedAnswerHasNoId() {
        val log = ChatLog()
        val (message, _) = log.send("hola")
        log.answered(message, "¡Hola!")
        assertNull((log.entries.last() as ChatEntry.Assistant).messageId)
    }

    @Test fun aVoteUpdatesTheAnswerInPlace() {
        val log = ChatLog()
        val (message, _) = log.send("¿Qué es una VPN?")
        log.answered(message, "Un túnel cifrado.", messageId = 42)
        val answer = log.entries.last() as ChatEntry.Assistant
        val change = log.voted(answer.id, -1)
        assertEquals(-1, (change.updated.single() as ChatEntry.Assistant).vote)
        assertTrue(change.added.isEmpty() && change.removed.isEmpty())
        assertNull((log.voted(answer.id, null).updated.single() as ChatEntry.Assistant).vote)
        assertTrue(log.voted(999, 1).updated.isEmpty())                     // gone: nothing to update
    }

    @Test fun anOpenedConversationKeepsIdsAndVotes() {
        val log = ChatLog()
        log.replaceWith(listOf(HistoryMessage("user", "¿Qué es una VPN?", 1),
            HistoryMessage("assistant", "Un túnel cifrado.", 2, vote = 1),
            HistoryMessage("assistant", "Otra.", 4)))
        val answers = log.entries.filterIsInstance<ChatEntry.Assistant>()
        assertEquals(listOf(2L, 4L), answers.map { it.messageId })
        assertEquals(listOf(1, null), answers.map { it.vote })
    }

    @Test fun theSameThumbAgainRemovesTheVote() {
        assertEquals(1, Votes.next(null, 1))
        assertEquals(0, Votes.next(1, 1))
        assertEquals(-1, Votes.next(1, -1))
        assertEquals(0, Votes.next(-1, -1))
    }

    @Test fun onlyAThumbsDownCarriesTheComment() {
        val up = Votes.body(42L, 1, "bien")
        assertEquals(setOf("message_id", "value"), up.keys)
        assertEquals(42L, up["message_id"])
        assertEquals(1, up["value"])
        val down = Votes.body(42L, -1, "  no era eso ")
        assertEquals(-1, down["value"])
        assertEquals("no era eso", down["comment"])
        assertEquals(setOf("message_id", "value"), Votes.body(42L, -1, "   ").keys)
        assertEquals(Votes.COMMENT_MAX, (Votes.body(42L, -1, "x".repeat(900))["comment"] as String).length)
    }

    @Test fun theRoute() {
        assertEquals("/api/v1/devices/me/feedback", Votes.ENDPOINT)
    }
}
