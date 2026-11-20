package com.itglasses.itglassesandroid

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MicSourcesTest {
    @Test fun voiceRecognitionFirstThenMic() {
        assertEquals(listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC), MicSources.order)
        assertFalse("sin el procesado de llamadas",
            MediaRecorder.AudioSource.VOICE_COMMUNICATION in MicSources.order)
    }

    @Test fun namesForTheDebugLog() {
        assertEquals("VOICE_RECOGNITION", MicSources.name(MediaRecorder.AudioSource.VOICE_RECOGNITION))
        assertEquals("MIC", MicSources.name(MediaRecorder.AudioSource.MIC))
        assertEquals("none", MicSources.name(null))
        assertEquals("other", MicSources.name(MediaRecorder.AudioSource.CAMCORDER))
    }
}
