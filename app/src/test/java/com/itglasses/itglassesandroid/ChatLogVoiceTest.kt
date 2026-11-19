package com.itglasses.itglassesandroid

import com.itglasses.itglassesandroid.ChatEntry.Speech
import com.itglasses.itglassesandroid.ChatEntry.Status
import com.itglasses.itglassesandroid.ChatEntry.Waiting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage B: voice in the conversation, and "No enviado" only when the question did not reach the assistant. */
class ChatLogVoiceTest {
    private val log = ChatLog()
    private fun users() = log.entries.filterIsInstance<ChatEntry.User>()
    private fun kinds() = log.entries.map { it::class.simpleName }

    /** Question transcribed and answered: ids of the question and the answer. */
    private fun answered(): Pair<Long, Long> {
        val (id, _) = log.voiceStarted()
        log.voiceTranscribed(id, "¿Cómo reinicio el router?")
        return id to log.voiceAnswered(id, "Mantén pulsado el botón 10 s.").first
    }

    @Test fun startAddsAProvisionalBubbleWithoutText() {
        val (id, change) = log.voiceStarted()
        assertEquals(listOf(ChatEntry.User(id, "", Status.TRANSCRIBING)), change.added)
        assertFalse(log.isEmpty)
    }

    @Test fun transcriptionFillsTheBubbleAndThinkingWaits() {
        val (id, _) = log.voiceStarted()
        val change = log.voiceTranscribed(id, "hola")
        assertEquals(listOf(ChatEntry.User(id, "hola", Status.SENDING)), change.updated)
        assertEquals(listOf(ChatEntry.Typing(id + 1, id, Waiting.THINKING)), change.added)
    }

    @Test fun answerReplacesThinkingAndPreparesTheVoiceThenSpeaks() {
        val (id, _) = log.voiceStarted()
        log.voiceTranscribed(id, "hola")
        val (answer, change) = log.voiceAnswered(id, "Hola")
        assertEquals(listOf(id + 1), change.removed)
        assertEquals(listOf(ChatEntry.User(id, "hola", Status.SENT)), change.updated)
        assertEquals(listOf(ChatEntry.Assistant(answer, "Hola", Speech.PREPARING)), change.added)
        assertEquals(Speech.SPEAKING, (log.speech(answer, Speech.SPEAKING).updated.single() as ChatEntry.Assistant).speech)
        assertTrue(log.speech(answer, Speech.SPEAKING).updated.isEmpty())  // Same label: nothing to redraw.
        assertEquals(null, (log.speech(answer, null).updated.single() as ChatEntry.Assistant).speech)
        assertEquals(listOf("User", "Assistant"), kinds())
    }

    @Test fun noSpeechRemovesTheProvisionalBubbleAndShowsTheNotice() {
        val (id, _) = log.voiceStarted()
        val change = log.voiceNoSpeech(id, "No se reconoció ninguna voz.")
        assertEquals(listOf(id), change.removed)
        assertEquals(listOf("No se reconoció ninguna voz."), change.added.map { (it as ChatEntry.Notice).text })
        assertEquals(listOf("Notice"), kinds())
    }

    @Test fun clarificationIsAnAnswerWithoutNotSentOrVoice() {
        val (id, _) = log.voiceStarted()
        val change = log.voiceClarification(id, "¿Te refieres al router o al módem?")
        assertEquals(listOf(id), change.removed)
        assertEquals(listOf(ChatEntry.Assistant(id + 1, "¿Te refieres al router o al módem?")), change.added)
        assertTrue(users().isEmpty())
    }

    @Test fun transcriptionFailureLeavesNoNotSent() {
        val (id, _) = log.voiceStarted()
        val change = log.voiceFailed(id, null, VoiceStep.TRANSCRIBE, "Sin conexión con el servidor.")
        assertEquals(listOf(id), change.removed)
        assertTrue(change.updated.isEmpty())
        assertTrue(users().isEmpty())
        assertEquals(listOf("Notice"), kinds())
        // 410: the card explains, nothing is added.
        val (second, _) = log.voiceStarted()
        assertTrue(log.voiceFailed(second, null, VoiceStep.TRANSCRIBE, null).added.isEmpty())
        assertEquals(listOf("Notice"), kinds())
    }

    @Test fun chatFailureMarksTheTranscribedQuestionNotSent() {
        val (id, _) = log.voiceStarted()
        log.voiceTranscribed(id, "hola")
        val change = log.voiceFailed(id, null, VoiceStep.CHAT, "Sin conexión con el servidor.")
        assertEquals(listOf(id + 1), change.removed)  // "Pensando"
        assertEquals(listOf(ChatEntry.User(id, "hola", Status.FAILED)), change.updated)
        assertEquals(listOf("User", "Notice"), kinds())
    }

    @Test fun synthesisAndPlaybackFailuresKeepQuestionAndAnswer() {
        for (step in listOf(VoiceStep.SYNTHESIS, VoiceStep.PLAYBACK)) {
            val (id, answer) = answered()
            if (step == VoiceStep.PLAYBACK) log.speech(answer, Speech.SPEAKING)
            val change = log.voiceFailed(id, answer, step, "No se pudo reproducir la respuesta.")
            assertEquals(listOf(ChatEntry.Assistant(answer, "Mantén pulsado el botón 10 s.", null)), change.updated)
            assertTrue(change.removed.isEmpty())
            assertEquals(Status.SENT, users().single { it.id == id }.status)
        }
        assertTrue(users().none { it.status == Status.FAILED })
    }

    @Test fun forbiddenInAVoiceStepUsesTheProvisionalCheck() {
        val (first, _) = log.voiceStarted()
        val transcribe = log.voiceChecking(first, null, VoiceStep.TRANSCRIBE)
        assertEquals(listOf(first), transcribe.removed)
        assertTrue(transcribe.added.single() is ChatEntry.Checking)

        val (second, _) = log.voiceStarted()  // Drops the pending check.
        log.voiceTranscribed(second, "hola")
        val chat = log.voiceChecking(second, null, VoiceStep.CHAT)
        assertEquals(listOf(ChatEntry.User(second, "hola", Status.FAILED)), chat.updated)
        assertEquals(listOf("User", "Checking"), kinds())
        log.resolveCheck("Esta función no está disponible para el celular.")
        assertEquals(listOf("User", "Notice"), kinds())

        val (third, answer) = answered()
        val synthesis = log.voiceChecking(third, answer, VoiceStep.SYNTHESIS)
        assertEquals(Status.SENT, users().single { it.id == third }.status)
        assertTrue(synthesis.added.single() is ChatEntry.Checking)
    }

    @Test fun standaloneNoticesAndTypedChatKeepWorking() {
        assertEquals(listOf("La grabación quedó vacía. Inténtalo de nuevo."),
            log.notice("La grabación quedó vacía. Inténtalo de nuevo.").added.map { (it as ChatEntry.Notice).text })
        val (id, change) = log.send("escrito")
        assertEquals(ChatEntry.Typing(id + 1, id, Waiting.GENERATING), change.added.last())
        assertEquals(ChatEntry.Assistant(id + 2, "ok", null), log.answered(id, "ok").added.single())
    }

    @Test fun voiceStepsOnEntriesAlreadyDroppedDoNothingHarmful() {
        val small = ChatLog(limit = 2)
        val (id, _) = small.voiceStarted()
        small.notice("a"); small.notice("b")  // The provisional bubble is dropped by the limit.
        assertEquals(ChatChange(), small.voiceTranscribed(id, "hola"))
        assertEquals(ChatChange(), small.speech(999, Speech.SPEAKING))
        assertTrue(small.voiceFailed(id, 999, VoiceStep.SYNTHESIS, null).updated.isEmpty())
        assertTrue(small.entries.size <= 2)
    }

    @Test fun basicEngineLabelOnlyForTheVoskFallbackNeverForWhisper() {
        val (whisper, _) = log.voiceStarted()
        val fromWhisper = log.voiceTranscribed(whisper, "hola").updated.single() as ChatEntry.User
        assertFalse("con Whisper no se muestra la etiqueta", fromWhisper.basicEngine || fromWhisper.showsBasicEngine)

        val (vosk, _) = log.voiceStarted()
        val fromVosk = log.voiceTranscribed(vosk, "hola", basicEngine = true).updated.single() as ChatEntry.User
        assertTrue(fromVosk.showsBasicEngine)
        // The label stays when the answer arrives or the question fails.
        log.voiceAnswered(vosk, "respuesta")
        assertTrue(users().single { it.id == vosk }.showsBasicEngine)
        assertFalse(users().single { it.id == whisper }.showsBasicEngine)
        assertFalse("la burbuja provisional nunca la muestra",
            ChatEntry.User(1, "", Status.TRANSCRIBING, basicEngine = true).showsBasicEngine)
    }
}
