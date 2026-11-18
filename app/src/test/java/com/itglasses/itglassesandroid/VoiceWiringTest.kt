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

    @Test fun pairingModeVoiceGoesToTheConversation() {
        assertTrue(activity.contains("binding.tvTranscription.visibility = android.view.View.GONE"))
        assertTrue(activity.contains("binding.tvAiResponse.visibility = android.view.View.GONE"))
        assertFalse("la voz ya no usa la línea de estado", activity.contains("showNotice("))
        assertTrue(activity.contains("InputLock.of("))
        for (call in listOf("chatLog.voiceStarted()", "chatLog.voiceNoSpeech(", "chatLog.voiceClarification(",
                "chatLog.voiceTranscribed(", "chatLog.voiceAnswered(", "ChatEntry.Speech.SPEAKING", "VoiceFailures.apply(",
                "VoiceStep.PLAYBACK")) {
            assertTrue(call, activity.contains(call))
        }
    }
}
