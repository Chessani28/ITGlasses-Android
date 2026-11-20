package com.itglasses.itglassesandroid

import com.itglasses.itglassesandroid.VoiceEvent.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceFlowTest {
    private fun flow(vararg events: VoiceEvent) = VoiceFlow().also { f -> events.forEach { assertTrue("$it", f.on(it)) } }

    @Test fun fullRunGoesThroughEveryState() {
        val f = VoiceFlow()
        val seen = mutableListOf(f.stage)
        for (e in listOf(START, STOP, TRANSCRIBED, ANSWERED, AUDIO_READY, PLAYBACK_DONE)) {
            assertTrue(f.on(e)); seen += f.stage
        }
        assertEquals(listOf(VoiceStage.IDLE, VoiceStage.LISTENING, VoiceStage.TRANSCRIBING, VoiceStage.THINKING,
            VoiceStage.SYNTHESIZING, VoiceStage.SPEAKING, VoiceStage.DONE), seen)
        assertNull(f.failedStep)
    }

    @Test fun screenAndBackOnlyWhileListening() {
        val f = VoiceFlow()
        assertFalse(f.screenOpen || f.backCancels)
        f.on(START)
        assertTrue(f.screenOpen && f.backCancels && !f.busy)
        f.on(STOP)
        assertFalse(f.screenOpen || f.backCancels)
        assertTrue(f.busy)
        assertTrue(flow(START, CANCEL).stage == VoiceStage.CANCELLED)
    }

    @Test fun noSpeechClarificationAndNoAudioEndWithoutFailing() {
        assertEquals(VoiceStage.DONE, flow(START, STOP, NO_SPEECH).stage)
        assertEquals(VoiceStage.DONE, flow(START, STOP, CLARIFICATION).stage)
        val noAudio = flow(START, STOP, TRANSCRIBED, ANSWERED, NO_AUDIO)
        assertEquals(VoiceStage.DONE, noAudio.stage)
        assertNull(noAudio.failedStep)
    }

    @Test fun failureRecordsTheStepItHappenedIn() {
        assertEquals(VoiceStep.TRANSCRIBE, flow(START, STOP, FAILED).failedStep)
        assertEquals(VoiceStep.CHAT, flow(START, STOP, TRANSCRIBED, FAILED).failedStep)
        assertEquals(VoiceStep.SYNTHESIS, flow(START, STOP, TRANSCRIBED, ANSWERED, FAILED).failedStep)
        assertEquals(VoiceStep.PLAYBACK, flow(START, STOP, TRANSCRIBED, ANSWERED, AUDIO_READY, FAILED).failedStep)
        val listening = flow(START, FAILED)  // Microphone stopped working while listening.
        assertEquals(VoiceStage.FAILED, listening.stage)
        assertNull(listening.failedStep)
        val again = flow(START, STOP, FAILED, START)
        assertNull(again.failedStep)
        assertEquals(VoiceStage.LISTENING, again.stage)
    }

    @Test fun invalidEventsChangeNothing() {
        val f = VoiceFlow()
        for (e in VoiceEvent.values().filter { it != START }) assertFalse("$e", f.on(e))
        assertEquals(VoiceStage.IDLE, f.stage)
        f.on(START); f.on(STOP)
        assertFalse(f.on(CANCEL))  // Once sent there is nothing to cancel.
        assertFalse(f.on(START))   // No new recording while busy.
        assertFalse(f.on(ANSWERED))
        assertEquals(VoiceStage.TRANSCRIBING, f.stage)
    }

    @Test fun counterAndThirtySecondLimit() {
        assertEquals(0, VoiceTiming.seconds(0))
        assertEquals(0, VoiceTiming.seconds(-500))
        assertEquals(12, VoiceTiming.seconds(12_999))
        assertEquals(30, VoiceTiming.seconds(45_000))
        assertFalse(VoiceTiming.reachedMax(29_999))
        assertTrue(VoiceTiming.reachedMax(30_000))
        assertEquals(VoiceStage.TRANSCRIBING, flow(START, STOP).stage)  // The limit is just STOP.
    }
}
