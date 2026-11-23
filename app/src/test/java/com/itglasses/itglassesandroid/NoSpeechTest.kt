package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression of 23/11 (82c5906): every background turn ended in no_speech at ~5 s although the user spoke. The
 * no-speech cancel used vad.heard (200 ms in a row above the end-of-speech threshold), which choppy or quiet Bluetooth
 * speech may not reach in 5 s. Now it needs no voice at all (hint level, total time) in 5 s of real audio.
 */
class NoSpeechTest {
    private fun calibrated(noise: Float = 0.05f) = EndOfSpeech().apply { repeat(6) { onBlock(noise, 50) } }

    @Test fun choppySpeechNeverInARowIsNotCancelled() {
        val vad = calibrated()
        repeat(50) { onAlternating(vad, it) }  // 2.5 s: 50 ms of voice, 50 ms of pause
        assertFalse("never 200 ms in a row", vad.heard)
        assertTrue(BackgroundRecording.voiceSeen(vad))
        assertNull(BackgroundRecording.stop(5_000, 5_000, vad))
    }

    private fun onAlternating(vad: EndOfSpeech, i: Int) = vad.onBlock(if (i % 2 == 0) 0.6f else 0.1f, 50)

    @Test fun quietBluetoothSpeechBelowTheEndOfSpeechThresholdIsNotCancelled() {
        val vad = calibrated()
        repeat(20) { vad.onBlock(0.32f, 50) }  // about -34 dBFS: below 0.40, above the 0.25 hint
        assertFalse(vad.heard)
        assertTrue(BackgroundRecording.voiceSeen(vad))
        assertNull(BackgroundRecording.stop(6_000, 6_000, vad))
        assertEquals(RecordingStop.MAX_DURATION, BackgroundRecording.stop(15_000, 15_000, vad))
    }

    @Test fun silenceIsStillCancelled() {
        val vad = calibrated()
        repeat(100) { vad.onBlock(0.08f, 50) }
        assertFalse(BackgroundRecording.voiceSeen(vad))
        assertEquals(RecordingStop.NO_SPEECH, BackgroundRecording.stop(5_300, 5_000, vad))
    }

    @Test fun aClickIsNotVoice() {
        val vad = calibrated()
        vad.onBlock(0.9f, 50)
        repeat(100) { vad.onBlock(0.05f, 50) }
        assertFalse(BackgroundRecording.voiceSeen(vad))
        assertEquals(RecordingStop.NO_SPEECH, BackgroundRecording.stop(5_000, 5_000, vad))
    }

    @Test fun aLateMicrophoneDoesNotEatTheFiveSeconds() {
        assertTrue(BackgroundRecording.digitalSilence(ShortArray(320), 320))
        assertFalse(BackgroundRecording.digitalSilence(ShortArray(320) { if (it == 7) 1 else 0 }, 320))
        assertTrue("only the samples read count", BackgroundRecording.digitalSilence(ShortArray(320) { if (it == 300) 9 else 0 }, 160))
        val vad = calibrated(0f)
        assertNull("3 s of zeros + 2.5 s of real silence", BackgroundRecording.stop(5_500, 2_500, vad))
    }

    @Test fun theEndOfSpeechItselfIsUnchanged() {
        assertEquals(1100L, EndOfSpeech.SILENCE_MS)
        assertEquals(0.40f, EndOfSpeech.SPEECH_LEVEL, 0f)
        assertEquals(200L, EndOfSpeech.MIN_SPEECH_MS)
        val vad = calibrated()
        assertEquals(EndOfSpeech.SPEECH_LEVEL, vad.threshold, 0f)
        assertEquals(EndOfSpeech.HINT_LEVEL, vad.hintThreshold, 0f)
    }

    @Test fun diagnosticLineHasLevelsThresholdsAndTimesOnly() {
        val vad = calibrated()
        repeat(4) { vad.onBlock(0.3f, 50) }
        assertEquals("background record result=no_speech wall_ms=5025 audio_ms=5000 zero_ms=0 peak=0.30 noise=0.05 " +
            "threshold=0.40 hint=0.25 voice_ms=200 heard=false",
            BackgroundRecording.describe("no_speech", 5025, 5000, 0, vad))
    }
}
