package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SttEngineTest {
    @Test fun pairingModeAsksForWhisperAndSharedTokenModeSendsNoHeader() {
        assertEquals("whisper-small", SttEngine.first(pairing = true))
        assertNull(SttEngine.first(pairing = false))
        assertEquals("X-ITGlasses-STT-Engine", SttEngine.HEADER)
    }

    @Test fun onlyA503FromWhisperRetriesOnceWithVosk() {
        assertEquals("vosk", SttEngine.fallbackAfter("whisper-small", 503))
        for (status in listOf(400, 401, 403, 409, 410, 413, 415, 422, 500, 502, 504)) {
            assertNull("$status no debe reintentar", SttEngine.fallbackAfter("whisper-small", status))
        }
        assertNull("nunca un segundo reintento", SttEngine.fallbackAfter("vosk", 503))
        assertNull("sin cabecera (token compartido) no hay reintento", SttEngine.fallbackAfter(null, 503))
    }

    @Test fun theBasicEngineLabelIsOnlyForVosk() {
        assertTrue(SttEngine.isBasic("vosk"))
        assertFalse("Whisper nunca muestra la etiqueta", SttEngine.isBasic("whisper-small"))
        assertFalse(SttEngine.isBasic(null))
    }

    @Test fun debugLogHasOnlyStepTimeEngineAndMicrophone() {
        assertEquals("step=transcribe ms=1234 engine=whisper-small mic=VOICE_RECOGNITION",
            VoiceDebugLog.line("transcribe", 1234, "whisper-small", "VOICE_RECOGNITION"))
        assertEquals("step=answer ms=0 engine=none mic=none", VoiceDebugLog.line("answer", -5, null, null))
        assertEquals("ITG-Voice", VoiceDebugLog.TAG)
        // A step is a short identifier: free text (a transcript) cannot be logged through it.
        for (bad in listOf("Según el manual", "transcribe ", "", "a".repeat(25))) {
            assertTrue(bad, runCatching { VoiceDebugLog.line(bad, 1, null, null) }.isFailure)
        }
    }
}
