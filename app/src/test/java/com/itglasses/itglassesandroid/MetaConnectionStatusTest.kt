package com.itglasses.itglassesandroid

import org.junit.Assert.assertTrue
import org.junit.Test

class MetaConnectionStatusTest {

    @Test
    fun `registered app with no device remains explicitly disconnected`() {
        val message = MetaConnectionStatus.message("REGISTERED", 0, null, null)

        assertTrue(message.contains("no compatible Meta device is connected"))
        assertTrue(message.contains("Phone mode remains available"))
    }

    @Test
    fun `reported device does not switch phone audio route`() {
        val message = MetaConnectionStatus.message("REGISTERED", 1, null, null)

        assertTrue(message.contains("DAT reports 1 connected device"))
        assertTrue(message.contains("Phone microphone and speaker remain selected"))
    }

    @Test
    fun `active session does not claim that glasses audio is enabled`() {
        val message = MetaConnectionStatus.message("REGISTERED", 1, "STARTED", null)

        assertTrue(message.contains("glasses audio is not enabled"))
        assertTrue(message.contains("Phone mode remains available"))
    }
}
