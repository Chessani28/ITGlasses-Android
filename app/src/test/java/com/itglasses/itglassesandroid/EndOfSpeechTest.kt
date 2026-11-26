package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndOfSpeechTest {
    private val block = 50L

    /** Feeds [ms] of [level] in 50 ms blocks; true if any block ended the turn. */
    private fun EndOfSpeech.feed(level: Float, ms: Long): Boolean {
        var ended = false
        repeat((ms / block).toInt()) { if (onBlock(level, block)) ended = true }
        return ended
    }

    @Test fun endsAfterSpeechThenTheConfiguredSilence() {
        val vad = EndOfSpeech()
        assertFalse(vad.feed(0.05f, EndOfSpeech.CALIBRATION_MS))  // quiet room
        assertFalse(vad.feed(0.6f, 800))                            // speech
        assertTrue(vad.heard)
        assertFalse(vad.feed(0.05f, EndOfSpeech.SILENCE_MS - block))
        assertTrue(vad.onBlock(0.05f, block))
    }

    @Test fun neverEndsWithoutSpeech() {
        val vad = EndOfSpeech()
        assertFalse(vad.feed(0.05f, 30_000))
        assertFalse(vad.heard)
    }

    @Test fun aPauseShorterThanTheSilenceDoesNotEnd() {
        val vad = EndOfSpeech()
        vad.feed(0.05f, EndOfSpeech.CALIBRATION_MS)
        vad.feed(0.6f, 500)
        assertFalse(vad.feed(0.05f, 700))
        assertFalse(vad.feed(0.6f, 500))      // speaks again: the silence restarts
        assertFalse(vad.feed(0.05f, 1000))
        assertTrue(vad.feed(0.05f, 200))
    }

    @Test fun aShortBlipDoesNotCountAsSpeech() {
        val vad = EndOfSpeech()
        vad.feed(0.05f, EndOfSpeech.CALIBRATION_MS)
        vad.feed(0.8f, EndOfSpeech.MIN_SPEECH_MS - block)
        assertFalse(vad.heard)
        assertFalse(vad.feed(0.05f, 3000))
    }

    @Test fun aNoisyRoomRaisesTheThreshold() {
        val vad = EndOfSpeech()
        vad.feed(0.45f, EndOfSpeech.CALIBRATION_MS)
        assertEquals(0.45f + EndOfSpeech.MARGIN, vad.threshold, 0.001f)
        assertFalse(vad.feed(0.5f, 1000))     // the noise itself is not speech
        assertFalse(vad.heard)
    }

    @Test fun theBackgroundIsCapped() {
        val vad = EndOfSpeech()
        vad.feed(0.9f, EndOfSpeech.CALIBRATION_MS)  // someone talking during calibration
        assertEquals(EndOfSpeech.NOISE_CAP + EndOfSpeech.MARGIN, vad.threshold, 0.001f)
    }

    @Test fun quietRoomUsesTheMinimumSpeechLevel() {
        val vad = EndOfSpeech()
        vad.feed(0f, EndOfSpeech.CALIBRATION_MS)
        assertEquals(EndOfSpeech.SPEECH_LEVEL, vad.threshold, 0f)
    }

    @Test fun constantsAreTheAgreedDefaults() {
        assertEquals(1100L, EndOfSpeech.SILENCE_MS)
        assertEquals(0.40f, EndOfSpeech.SPEECH_LEVEL, 0f)
    }

    @Test fun blockDurationFromSamples() {
        assertEquals(64L, EndOfSpeech.blockMs(1024))
        assertEquals(0L, EndOfSpeech.blockMs(0))
        assertFalse(EndOfSpeech().onBlock(0.9f, 0))
    }
}
