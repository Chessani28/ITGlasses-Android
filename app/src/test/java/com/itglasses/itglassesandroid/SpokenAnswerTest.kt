package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the voice reads in pairing mode: the backend's `speech`, or the answer without sources cut to 450. */
class SpokenAnswerTest {
    private val sentence = "Conecta el equipo a la red por cable y espera a que termine el arranque."  // 72 characters

    @Test fun theBackendSpeechIsReadWhenItHasText() {
        val speech = "«Reinicio de servidor» tiene 3 pasos. Paso 1: Apagar el servidor. Paso 2: Esperar 30 segundos."
        assertEquals(speech, SpokenAnswer.of("«Reinicio de servidor» (3 pasos):\n1. Apagar el servidor.", speech))
        assertEquals("Paso 1: Apagar.", SpokenAnswer.of("respuesta", "Paso 1: Apagar. [Fuente: Procedimiento de Reinicio]"))
    }

    @Test fun absentNullOrBlankSpeechUsesTheAnswer() {
        for (speech in listOf(null, "", "   ", "\n")) {
            assertEquals("$speech", "Respuesta corta.", SpokenAnswer.of("Respuesta corta. [Fuente: Manual]", speech))
        }
    }

    @Test fun aShortAnswerIsReadWholeWithoutTheRestNotice() {
        val answer = List(6) { sentence }.joinToString(" ")  // 437 characters
        assertEquals(answer, SpokenAnswer.of(answer, null))
        assertFalse(SpokenAnswer.of(answer, null).contains(SpokenAnswer.REST))
    }

    @Test fun aLongAnswerIsCutAtTheLastWholeSentence() {
        val answer = List(10) { sentence }.joinToString(" ")
        val spoken = SpokenAnswer.of(answer, null)
        assertEquals(List(6) { sentence }.joinToString(" ") + " " + SpokenAnswer.REST, spoken)
        assertTrue(spoken.length <= SpokenAnswer.MAX_CHARS + SpokenAnswer.REST.length + 1)
    }

    @Test fun withoutSentencesTheCutIsAtAWord() {
        val answer = List(80) { "palabra" }.joinToString(" ")
        val spoken = SpokenAnswer.cap(answer)
        assertTrue(spoken.endsWith("palabra. " + SpokenAnswer.REST))
        assertTrue(spoken.removeSuffix(" " + SpokenAnswer.REST).length <= SpokenAnswer.MAX_CHARS)
    }

    @Test fun sourcesLinksAndMarkdownAreRemovedBeforeMeasuring() {
        val answer = "**Usa** un cable cruzado. [Fuente: Guía, página 4]\n\nFuentes consultadas:\n" +
            List(20) { "- [Guía $it](https://example.com/guia/$it) (consultada: hoy)" }.joinToString("\n")
        assertEquals("Usa un cable cruzado.", SpokenAnswer.of(answer, null))
        val listed = "Pasos:\n" + List(12) { "- Paso ${it + 1}: ${sentence}" }.joinToString("\n")
        val spoken = SpokenAnswer.of(listed, null)
        assertFalse(spoken.contains("-") || spoken.contains("**") || spoken.contains("http"))
        assertTrue(spoken.endsWith(SpokenAnswer.REST))
    }

    @Test fun limitsAreTheApprovedOnes() {
        assertEquals(450, SpokenAnswer.MAX_CHARS)
        assertEquals("El resto está en el chat.", SpokenAnswer.REST)
    }
}
