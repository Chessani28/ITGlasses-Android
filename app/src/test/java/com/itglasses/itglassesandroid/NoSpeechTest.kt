package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Background recording diagnostics (log only). The no-speech rule is the one of 82c5906: nothing heard by the
 * end-of-speech detector (vad.heard) in 5 s cancels in silence; a very short word ("Hola") can be cancelled.
 */
class NoSpeechTest {
    private fun calibrated(noise: Float = 0.05f) = EndOfSpeech().apply { repeat(6) { onBlock(noise, 50) } }
    private val pipeline by lazy { ModuleFiles.file("src/main/java/com/itglasses/itglassesandroid/VoicePipeline.kt").readText() }

    @Test fun theRuleIsStillVadHeard() {
        assertTrue(pipeline.contains("when (BackgroundRecording.stop(elapsed, vad.heard))"))
        val vad = calibrated()
        repeat(3) { vad.onBlock(0.6f, 50) }  // 150 ms: shorter than MIN_SPEECH_MS
        assertFalse(vad.heard)
        assertEquals(RecordingStop.NO_SPEECH, BackgroundRecording.stop(5_000, vad.heard))
    }

    @Test fun diagnosticsNeverChangeTheEndOfSpeech() {
        assertEquals(1100L, EndOfSpeech.SILENCE_MS)
        assertEquals(0.40f, EndOfSpeech.SPEECH_LEVEL, 0f)
        assertEquals(200L, EndOfSpeech.MIN_SPEECH_MS)
        val vad = calibrated()
        assertEquals(EndOfSpeech.SPEECH_LEVEL, vad.threshold, 0f)
        assertEquals(EndOfSpeech.HINT_LEVEL, vad.hintThreshold, 0f)
    }

    @Test fun digitalSilenceCountsOnlyTheSamplesRead() {
        assertTrue(BackgroundRecording.digitalSilence(ShortArray(320), 320))
        assertFalse(BackgroundRecording.digitalSilence(ShortArray(320) { if (it == 7) 1 else 0 }, 320))
        assertTrue(BackgroundRecording.digitalSilence(ShortArray(320) { if (it == 300) 9 else 0 }, 160))
    }

    @Test fun diagnosticLineHasLevelsThresholdsAndTimesOnly() {
        val vad = calibrated()
        repeat(4) { vad.onBlock(0.3f, 50) }
        assertEquals("background record result=no_speech wall_ms=5025 audio_ms=5000 zero_ms=0 peak=0.30 noise=0.05 " +
            "threshold=0.40 hint=0.25 voice_ms=200 heard=false",
            BackgroundRecording.describe("no_speech", 5025, 5000, 0, vad))
        assertTrue(pipeline.contains("GlassesLog.d(BackgroundRecording.describe(\"no_speech\", elapsed, audioMs, zeroMs, vad))"))
    }
}
