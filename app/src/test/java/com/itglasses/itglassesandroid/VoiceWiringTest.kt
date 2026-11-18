package com.itglasses.itglassesandroid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage B2: MainActivity uses VoiceTiming and VoiceMeter, Back while listening, and the screen on top of content. */
class VoiceWiringTest {
    private val activity by lazy { ModuleFiles.file("src/main/java/com/itglasses/MainActivity.kt").readText() }

    @Test fun recordingUsesVoiceTimingAndTheMeter() {
        assertFalse(activity.contains("MAX_DURATION_MS"))
        assertTrue(activity.contains("VoiceTiming.reachedMax(elapsed)"))
        assertTrue(activity.contains("VoiceTiming.seconds(elapsed)"))
        assertTrue(activity.contains("voiceMeter.onSamples(buffer, read)"))
        assertTrue(activity.contains("voiceScreen.seconds(seconds)"))
    }

    @Test fun backCancelsOnlyWhileListeningAndTheScreenSitsOnContent() {
        assertTrue(activity.contains("object : OnBackPressedCallback(false)"))
        assertTrue(activity.contains("onBackPressedDispatcher.addCallback(this, voiceBack)"))
        assertTrue(activity.contains("if (voiceFlow.screenOpen) cancelRecording()"))  // onStop
        assertTrue(activity.contains("voiceStartFailed(getString(R.string.voice_mic_unavailable))"))
        val screen = ModuleFiles.file("src/main/java/com/itglasses/itglassesandroid/VoiceScreen.kt").readText()
        assertTrue(screen.contains("android.R.id.content"))
    }
}
