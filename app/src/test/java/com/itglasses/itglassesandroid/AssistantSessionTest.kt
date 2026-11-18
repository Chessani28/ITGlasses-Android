package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantSessionTest {
    private val uuid = "3f2c1d9e-8b7a-4c6d-9e1f-0a2b3c4d5e6f"

    @Test fun firstQuestionHasNoSessionAndTheReturnedOneIsKept() {
        assertNull(AssistantSession.next(null, null))
        assertEquals(uuid, AssistantSession.next(null, uuid))
        assertEquals("otra-sesion", AssistantSession.next(uuid, "otra-sesion"))  // The backend started a new one.
    }

    @Test fun aMissingOrUnsafeValueKeepsTheCurrentSession() {
        for (bad in listOf(null, "", " ", "a b", "x\n", "<script>", "sesión", "a".repeat(AssistantSession.MAX_LENGTH + 1))) {
            assertEquals("'$bad' no debería sustituir la sesión", uuid, AssistantSession.next(uuid, bad))
        }
    }

    @Test fun theBackendLimitIsAccepted() {
        val longest = "a".repeat(AssistantSession.MAX_LENGTH)
        assertEquals(128, AssistantSession.MAX_LENGTH)
        assertEquals(longest, AssistantSession.next(null, longest))
    }
}
