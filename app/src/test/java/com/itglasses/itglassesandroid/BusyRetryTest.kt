package com.itglasses.itglassesandroid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BusyRetryTest {
    private val busy = """{"error":{"code":"voice_busy","message":"A local speech operation is already running."}}"""

    @Test fun retriesVoiceBusyOnTheSpeechAndChatCalls() {
        for (path in BusyRetry.PATHS) assertTrue(path, BusyRetry.shouldRetry(path, 409, busy, 0))
    }

    @Test fun atMostThreeRetries() {
        assertTrue(BusyRetry.shouldRetry("/api/v1/voice/transcribe", 409, busy, BusyRetry.MAX_RETRIES - 1))
        assertFalse(BusyRetry.shouldRetry("/api/v1/voice/transcribe", 409, busy, BusyRetry.MAX_RETRIES))
    }

    @Test fun anyOtherConflictIsNotRetried() {
        val other = """{"error":{"code":"source_unavailable","message":"x"}}"""
        assertFalse(BusyRetry.shouldRetry("/api/v1/voice/synthesize", 409, other, 0))
        assertFalse(BusyRetry.shouldRetry("/api/v1/voice/synthesize", 503, busy, 0))
        assertFalse(BusyRetry.shouldRetry("/api/v1/voice/synthesize", 200, "", 0))
    }

    @Test fun pairingAndOtherPathsAreNeverRetried() {
        for (path in listOf("/api/v1/pairing/claim", "/api/v1/pairing/status", "/api/v1/devices/token", "/api/v1/status")) {
            assertFalse(path, BusyRetry.shouldRetry(path, 409, busy, 0))
        }
    }
}
