package com.itglasses.itglassesandroid

import com.itglasses.itglassesandroid.ChatEntry.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLogTest {
    private val log = ChatLog()
    private fun kinds() = log.entries.map { it::class.simpleName }

    @Test fun sendingAddsTheMessageAndGenerating() {
        val (id, change) = log.send("hola")
        assertEquals(listOf(ChatEntry.User(id, "hola", Status.SENDING), ChatEntry.Typing(id + 1, id)), change.added)
        assertTrue(change.removed.isEmpty() && change.updated.isEmpty())
    }

    @Test fun answerReplacesGeneratingAndMarksTheMessageSent() {
        val (id, _) = log.send("hola")
        val change = log.answered(id, "Hola, ¿en qué te ayudo?")
        assertEquals(listOf(id + 1), change.removed)
        assertEquals(listOf(ChatEntry.User(id, "hola", Status.SENT)), change.updated)
        assertEquals(listOf("Hola, ¿en qué te ayudo?"), change.added.map { (it as ChatEntry.Assistant).text })
        assertEquals(listOf("User", "Assistant"), kinds())
    }

    @Test fun failureMarksNotSentWithOrWithoutANotice() {
        val (first, _) = log.send("uno")
        val withNotice = log.failed(first, "Sin conexión con el servidor.")
        assertEquals(listOf(ChatEntry.User(first, "uno", Status.FAILED)), withNotice.updated)
        assertEquals(listOf("Sin conexión con el servidor."), withNotice.added.map { (it as ChatEntry.Notice).text })

        val (second, _) = log.send("dos")
        val silent = log.failed(second, null)  // The card already explains (deleted device).
        assertTrue(silent.added.isEmpty())
        assertEquals(listOf(ChatEntry.User(second, "dos", Status.FAILED)), silent.updated)
        assertEquals(listOf("User", "Notice", "User"), kinds())
    }

    @Test fun checkingIsProvisionalAndBecomesTheUnavailableNotice() {
        val (id, _) = log.send("hola")
        val checking = log.checking(id)
        assertEquals(listOf(ChatEntry.User(id, "hola", Status.FAILED)), checking.updated)
        val provisional = checking.added.single() as ChatEntry.Checking
        assertEquals(id, provisional.forMessage)

        val resolved = log.resolveCheck("Esta función no está disponible para el celular.")
        assertEquals(listOf(provisional.id), resolved.removed)
        assertEquals(listOf("Esta función no está disponible para el celular."), resolved.added.map { (it as ChatEntry.Notice).text })
        assertEquals(listOf("User", "Notice"), kinds())
    }

    @Test fun checkingLeftToTheCardLeavesNothingInTheConversation() {
        val (id, _) = log.send("hola")
        val provisional = log.checking(id).added.single()
        val resolved = log.resolveCheck(null)
        assertEquals(listOf(provisional.id), resolved.removed)
        assertTrue(resolved.added.isEmpty())
        assertEquals(listOf("User"), kinds())
    }

    @Test fun anotherMessageRemovesAPendingCheckAndResolvingLaterDoesNothing() {
        val (first, _) = log.send("uno")
        val provisional = log.checking(first).added.single()
        val (_, change) = log.send("dos")
        assertEquals(listOf(provisional.id), change.removed)
        assertEquals(ChatChange(), log.resolveCheck("tarde"))
        assertEquals(listOf("User", "User", "Typing"), kinds())
    }

    @Test fun keepsAtMostTheLimitAndReportsWhatWasDropped() {
        val small = ChatLog(limit = 4)
        val (a, _) = small.send("a")       // a, typing
        small.answered(a, "A")             // a, A
        val (b, _) = small.send("b")       // a, A, b, typing (4: nothing dropped)
        small.answered(b, "B")             // a, A, b, B
        val (c, change) = small.send("c")  // a, A, b, B, c, typing -> the two oldest go
        assertEquals(listOf(a, a + 2), change.removed)
        assertEquals(listOf(c, c + 1), change.added.map { it.id })
        assertEquals(listOf("User", "Assistant", "User", "Typing"), small.entries.map { it::class.simpleName })
    }

    @Test fun answerForAMessageAlreadyDroppedStillAddsTheAnswer() {
        val tiny = ChatLog(limit = 2)
        val (old, _) = tiny.send("viejo")
        tiny.failed(old, "aviso 1")
        tiny.send("nuevo")                // drops "viejo" and its notice
        val change = tiny.answered(old, "respuesta tardía")
        assertTrue(change.updated.isEmpty())
        assertTrue(tiny.entries.size <= 2)
    }

    @Test fun onlyTheTextsGivenAreStored() {
        val (id, _) = log.send("pregunta")
        log.failed(id, "aviso")
        assertEquals(setOf("pregunta", "aviso"), log.entries.mapNotNull {
            when (it) { is ChatEntry.User -> it.text; is ChatEntry.Notice -> it.text; else -> null }
        }.toSet())
    }
}
