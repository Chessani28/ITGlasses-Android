package com.itglasses.itglassesandroid

import com.itglasses.itglassesandroid.ChatEntry.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage B3: a failed voice step uses the same ChatFailures table as typed chat. */
class VoiceFailuresTest {
    private val network = ChatFailure.WithNotice(CallText.NO_NETWORK)
    private fun text(f: ChatFailure.WithNotice) = "aviso ${f.text}"
    private fun kinds(log: ChatLog) = log.entries.map { it::class.simpleName }

    @Test fun transcriptionAndChatFailuresByKind() {
        for (failure in listOf(network, ChatFailure.NotSentOnly, ChatFailure.Check)) {
            val log = ChatLog()
            val (id, _) = log.voiceStarted()
            VoiceFailures.apply(log, id, null, VoiceStep.TRANSCRIBE, failure, ::text)
            assertTrue("$failure", log.entries.none { it is ChatEntry.User })
            assertEquals("$failure", when (failure) {
                network -> listOf("Notice"); ChatFailure.Check -> listOf("Checking"); else -> emptyList()
            }, kinds(log))

            val chat = ChatLog()
            val (q, _) = chat.voiceStarted()
            chat.voiceTranscribed(q, "hola")
            VoiceFailures.apply(chat, q, null, VoiceStep.CHAT, failure, ::text)
            assertEquals("$failure", Status.FAILED, chat.entries.filterIsInstance<ChatEntry.User>().single().status)
        }
    }

    @Test fun synthesisFailureKeepsTheAnswerAndNeverSaysNotSent() {
        for (failure in listOf(network, ChatFailure.NotSentOnly, ChatFailure.Check)) {
            val log = ChatLog()
            val (id, _) = log.voiceStarted()
            log.voiceTranscribed(id, "hola")
            val (answer, _) = log.voiceAnswered(id, "respuesta")
            VoiceFailures.apply(log, id, answer, VoiceStep.SYNTHESIS, failure, ::text)
            assertEquals(Status.SENT, log.entries.filterIsInstance<ChatEntry.User>().single().status)
            assertEquals(null, log.entries.filterIsInstance<ChatEntry.Assistant>().single().speech)
        }
        val log = ChatLog()
        val (id, _) = log.voiceStarted()
        log.voiceTranscribed(id, "hola")
        val (answer, _) = log.voiceAnswered(id, "respuesta")
        val change = VoiceFailures.apply(log, id, answer, VoiceStep.SYNTHESIS, network, ::text)
        assertEquals(listOf("aviso NO_NETWORK"), change.added.map { (it as ChatEntry.Notice).text })
    }

    @Test fun emptyClarificationUsesTheApprovedFallback() {
        val fallback = "Hace falta aclarar la pregunta."
        assertEquals(fallback, VoiceFailures.clarification("", fallback))
        assertEquals(fallback, VoiceFailures.clarification("   ", fallback))
        assertEquals("¿Router o módem?", VoiceFailures.clarification(" ¿Router o módem? ", fallback))
    }
}
