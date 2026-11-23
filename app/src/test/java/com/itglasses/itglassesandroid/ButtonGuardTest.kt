package com.itglasses.itglassesandroid

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Glasses guard: the automatic PLAY after the call route closes, cancel, and the background recording limits. */
class ButtonGuardTest {
    private var clock = 0L
    private val guard = ButtonGuard({ clock })
    private fun source(path: String) = ModuleFiles.file(path).readText()
    private val service by lazy { source("src/main/java/com/itglasses/itglassesandroid/VoiceForegroundService.kt") }
    private val pipeline by lazy { source("src/main/java/com/itglasses/itglassesandroid/VoicePipeline.kt") }

    @Test fun keysAreAcceptedUntilSomethingArmsTheGuard() {
        assertNull(guard.blockedBy())
    }

    @Test fun thePlayHalfASecondAfterATurnEndsIsIgnored() {
        clock = 10_000
        guard.block("turn_end")
        clock = 10_500  // what the log showed: PLAY ~0.5 s after "background turn end"
        assertEquals("turn_end", guard.blockedBy())
        clock = 10_000 + ButtonGuard.WINDOW_MS - 1
        assertEquals("turn_end", guard.blockedBy())
        clock = 10_000 + ButtonGuard.WINDOW_MS
        assertNull("a real tap 3 s later works", guard.blockedBy())
    }

    @Test fun aLaterEventExtendsTheWindowAndNamesItsReason() {
        guard.block("turn_end")
        clock = 2_000
        guard.block("route_change")
        clock = 4_000
        assertEquals("route_change", guard.blockedBy())
        clock = 2_000 + ButtonGuard.WINDOW_MS
        assertNull(guard.blockedBy())
    }

    @Test fun swipeCancelsATurnOnItsWayAndNothingElse() {
        val next = KeyEvent.KEYCODE_MEDIA_NEXT
        for (stage in listOf(TurnStage.LISTENING, TurnStage.THINKING, TurnStage.SPEAKING)) {
            assertEquals(stage.name, TalkAction.CANCEL, TalkButton.forKey(next, KeyEvent.ACTION_DOWN, 0, stage))
        }
        assertEquals(TalkAction.IGNORE, TalkButton.forKey(next, KeyEvent.ACTION_DOWN, 0, TurnStage.READY))
        assertEquals(TalkAction.IGNORE, TalkButton.forKey(next, KeyEvent.ACTION_UP, 0, TurnStage.LISTENING))
    }

    @Test fun backgroundRecordingLimits() {
        assertEquals(5_000L, BackgroundRecording.NO_SPEECH_TIMEOUT_MS)
        assertEquals(15_000L, BackgroundRecording.MAX_RECORDING_MS)
        val quiet = EndOfSpeech().apply { repeat(200) { onBlock(0.05f, 50) } }
        assertNull(BackgroundRecording.stop(4_999, 4_999, quiet))
        assertEquals(RecordingStop.NO_SPEECH, BackgroundRecording.stop(5_000, 5_000, quiet))
        assertNull("a late microphone: only real audio counts", BackgroundRecording.stop(6_000, 4_000, quiet))
        val spoke = EndOfSpeech().apply { repeat(6) { onBlock(0.05f, 50) }; repeat(10) { onBlock(0.6f, 50) } }
        assertNull("speech heard: keeps listening", BackgroundRecording.stop(10_000, 10_000, spoke))
        assertEquals(RecordingStop.MAX_DURATION, BackgroundRecording.stop(15_000, 15_000, spoke))
        assertTrue("never the 30 s of the app", BackgroundRecording.MAX_RECORDING_MS < VoiceTiming.MAX_MS)
    }

    @Test fun serviceArmsTheGuardAndChecksItBeforeEveryKey() {
        for (reason in listOf("service_start", "turn_end", "bt_device_added", "bt_device_removed", "route_change")) {
            assertTrue(reason, service.contains("guard.block(\"$reason\")"))
        }
        val callback = service.substringAfter("override fun onMediaButtonEvent").substringBefore("setPlaybackState")
        assertTrue(callback.indexOf("guard.blockedBy()") in 0 until callback.indexOf("TalkButton.forKey("))
    }

    @Test fun noSpeechCancelsInSilenceAndCancelHasItsTone() {
        assertTrue(pipeline.contains("if (pcm == null) return  // No speech in time: cancelled in silence"))
        assertTrue(pipeline.contains("VoiceCues.play(VoiceCues.Cue.CANCELLED, routed)"))
        assertTrue("el fin de turno arma el bloqueo", pipeline.substringAfter("} finally {").contains("onTurnEnd()"))
        assertFalse("en segundo plano no usa el límite de 30 s", pipeline.contains("VoiceTiming.reachedMax"))
        assertTrue("no_speech registra niveles y umbrales",
            pipeline.contains("GlassesLog.d(BackgroundRecording.describe(\"no_speech\", elapsed, audioMs, zeroMs, vad))"))
    }

    @Test fun everyTurnLogsItsSourceAndTheNotificationCanCancel() {
        assertTrue(pipeline.contains("background turn start source="))
        assertTrue(service.contains("val source = \"key:\${event.keyCode}\""))
        assertTrue(service.contains("putExtra(EXTRA_SOURCE, \"notification\")"))
        assertTrue(service.contains("putExtra(EXTRA_SOURCE, \"app\")"))
        assertTrue(service.contains("action(R.string.background_action_cancel, ACTION_CANCEL, 3)"))
        val strings = ModuleFiles.elements(ModuleFiles.xml("src/main/res/values/strings.xml"), "string")
            .associate { it.getAttribute("name") to it.textContent }
        assertEquals("Cancelar", strings["background_action_cancel"])
    }
}
