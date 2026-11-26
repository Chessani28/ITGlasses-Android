package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineNoticeTest {
    @Test fun eachReasonHasItsNotice() {
        assertEquals(EngineNotice.VRAM, EngineNotice.of(true, "voice_vram_limit"))
        assertEquals(EngineNotice.GPU_UNAVAILABLE, EngineNotice.of(true, "voice_gpu_unavailable"))
        assertEquals(EngineNotice.STARTUP, EngineNotice.of(true, "voice_worker_startup"))
        assertEquals(EngineNotice.VOSK, EngineNotice.of(true, "otra_cosa"))
        assertEquals(EngineNotice.VOSK, EngineNotice.of(true, null))       // Vosk without a reason: still said.
        assertEquals(EngineNotice.CPU, EngineNotice.of(false, "gpu_paused"))
        assertNull(EngineNotice.of(false, null))                            // Whisper on the GPU: nothing.
        assertNull(EngineNotice.of(false, " "))
    }

    @Test fun aNoticeIsShownOnceUntilTheVoiceIsBackToNormal() {
        val notices = EngineNotices()
        assertEquals(EngineNotice.VRAM, notices.next(EngineNotice.VRAM))
        assertNull(notices.next(EngineNotice.VRAM))                        // Same reason: not repeated.
        assertEquals(EngineNotice.CPU, notices.next(EngineNotice.CPU))     // Another reason: shown.
        assertNull(notices.next(null))                                     // Back to normal.
        assertEquals(EngineNotice.CPU, notices.next(EngineNotice.CPU))     // It happens again: shown again.
    }
}
