package com.itglasses.itglassesandroid

import com.itglasses.itglassesandroid.ChatEntry.Speech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

/** Only the voice failed, with the answer on screen: a discreet label, no amber notice (except 403 / acting). */
class VoiceUnspokenTest {
    private fun text(f: ChatFailure.WithNotice) = "aviso ${f.text}"

    private fun answered(): Triple<ChatLog, Long, Long> {
        val log = ChatLog()
        val (id, _) = log.voiceStarted()
        log.voiceTranscribed(id, "hola")
        val (answer, _) = log.voiceAnswered(id, "respuesta")
        return Triple(log, id, answer)
    }

    private fun speech(log: ChatLog) = log.entries.filterIsInstance<ChatEntry.Assistant>().single().speech

    @Test fun networkTimeoutAndServerFailuresOfTheVoiceAreDiscreet() {
        val quiet = listOf(CallText.NO_NETWORK, CallText.NO_RESPONSE, CallText.TIMEOUT, CallText.SERVER_ERROR,
            CallText.UNEXPECTED_CODE, CallText.FAILED, CallText.REJECTED, CallText.RATE_LIMITED)
        for (step in listOf(VoiceStep.SYNTHESIS, VoiceStep.PLAYBACK)) for (callText in quiet) {
            val (log, id, answer) = answered()
            val change = VoiceFailures.apply(log, id, answer, step, ChatFailure.WithNotice(callText), ::text)
            assertTrue("$step $callText", change.added.isEmpty())
            assertEquals("$step $callText", Speech.FAILED, speech(log))
            assertEquals(ChatEntry.Status.SENT, log.entries.filterIsInstance<ChatEntry.User>().single().status)
        }
    }

    @Test fun noticesThatAskToActAnd403StayAsBefore() {
        for (callText in listOf(CallText.UNAUTHORIZED, CallText.UNAUTHORIZED_REPEATED, CallText.CLOCK, CallText.CERTIFICATE)) {
            val (log, id, answer) = answered()
            val change = VoiceFailures.apply(log, id, answer, VoiceStep.SYNTHESIS, ChatFailure.WithNotice(callText), ::text)
            assertEquals("$callText", listOf("aviso $callText"), change.added.map { (it as ChatEntry.Notice).text })
            assertEquals(null, speech(log))
        }
        val (log, id, answer) = answered()
        VoiceFailures.apply(log, id, answer, VoiceStep.SYNTHESIS, ChatFailure.Check, ::text)
        assertTrue(log.entries.any { it is ChatEntry.Checking })
        val (removed, removedId, removedAnswer) = answered()  // 410: the pairing card explains
        assertTrue(VoiceFailures.apply(removed, removedId, removedAnswer, VoiceStep.SYNTHESIS, ChatFailure.NotSentOnly, ::text)
            .added.isEmpty())
        assertEquals(null, speech(removed))
    }

    @Test fun onlySynthesisOrPlaybackWithAnAnswerIsQuiet() {
        val network = ChatFailure.WithNotice(CallText.NO_NETWORK)
        assertFalse(VoiceFailures.quiet(VoiceStep.CHAT, network, 7))
        assertFalse(VoiceFailures.quiet(VoiceStep.TRANSCRIBE, network, 7))
        assertFalse(VoiceFailures.quiet(VoiceStep.SYNTHESIS, network, null))
        assertTrue(VoiceFailures.quiet(VoiceStep.PLAYBACK, network, 7))
        assertTrue(ChatLog().voiceUnspoken(null) == ChatChange())
    }

    @Test fun debugLineCarriesTheExceptionTypeAndStatusOnly() {
        assertEquals("step=synthesis_failed ms=1200 error=CallHttpException status=504",
            VoiceDebugLog.failure("synthesis_failed", 1200, CallHttpException(504)))
        val line = VoiceDebugLog.failure("synthesis_failed", 5, SocketTimeoutException("texto privado de la respuesta"))
        assertEquals("step=synthesis_failed ms=5 error=SocketTimeoutException status=none", line)
        assertFalse(line.contains("privado"))
    }

    @Test fun labelTextAndWiring() {
        val strings = ModuleFiles.file("src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("<string name=\"voice_unspoken\">No se pudo leer en voz alta.</string>"))
        val bubbles = ModuleFiles.file("src/main/java/com/itglasses/itglassesandroid/ChatBubbles.kt").readText()
        assertTrue(bubbles.contains("ChatEntry.Speech.FAILED -> label.text.text = context.getString(R.string.voice_unspoken)"))
        val activity = ModuleFiles.file("src/main/java/com/itglasses/MainActivity.kt").readText()
        assertEquals("sin audio y error de reproducción: etiqueta discreta", 2,
            Regex("""if \(pairing\.configured\) showChange\(chatLog\.voiceUnspoken\(voiceAnswer\)\)""").findAll(activity).count())
        assertTrue("el registro de fallos solo en depuración", Regex(
            """private fun voiceFailureLog\(step: String, error: Throwable\) \{\s+if \(!BuildConfig\.DEBUG\) return""")
            .containsMatchIn(activity))
        assertTrue(activity.contains("voiceFailureLog(\"synthesis_failed\", e)"))
    }
}
