package com.itglasses.itglassesandroid

import org.junit.Assert.assertTrue
import org.junit.Test

class MetaConnectionStatusTest {

    @Test
    fun `registered app with no device remains explicitly disconnected`() {
        val message = MetaConnectionStatus.message("REGISTERED", 0, null, null)

        assertTrue(message.contains("no hay lentes Meta compatibles conectados"))
        assertTrue(message.contains("El modo teléfono sigue disponible"))
    }

    @Test
    fun `reported device does not switch phone audio route`() {
        val message = MetaConnectionStatus.message("REGISTERED", 1, null, null)

        assertTrue(message.contains("DAT reporta 1 dispositivo"))
        assertTrue(message.contains("Se siguen usando el micrófono y la bocina del teléfono"))
    }

    @Test
    fun `active session does not claim that glasses audio is enabled`() {
        val message = MetaConnectionStatus.message("REGISTERED", 1, "STARTED", null)

        assertTrue(message.contains("el audio de los lentes no está habilitado"))
        assertTrue(message.contains("El modo teléfono sigue disponible"))
    }
}
