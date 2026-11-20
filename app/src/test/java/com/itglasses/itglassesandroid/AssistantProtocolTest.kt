package com.itglasses.itglassesandroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AssistantProtocolTest {
    @Test fun usesTheAssistantEndpointNotRawChat() {
        assertEquals("/api/v1/assistant/chat", AssistantProtocol.ENDPOINT)
    }

    @Test fun firstMessageHasNoSessionId() {
        val body = JSONObject(AssistantProtocol.requestBody("  ¿Qué es una VPN?  ", null))
        assertEquals("¿Qué es una VPN?", body.getString("message"))
        assertFalse(body.has("session_id"))
    }

    @Test fun followUpsReuseTheSession() {
        val body = JSONObject(AssistantProtocol.requestBody("¿Y el siguiente paso?", "abc-123"))
        assertEquals("abc-123", body.getString("session_id"))
    }

    @Test fun invalidSessionIdsAreNotSent() {
        for (session in listOf("", "   ", "x".repeat(129))) {
            assertFalse(JSONObject(AssistantProtocol.requestBody("hola", session)).has("session_id"))
        }
    }

    @Test fun longMessagesAreCutToTheBackendLimit() {
        val body = JSONObject(AssistantProtocol.requestBody("a".repeat(5000), null))
        assertEquals(4000, body.getString("message").length)
    }

    @Test fun parsesAnswerSessionRouteAndSources() {
        val reply = AssistantProtocol.parse(
            """{"answer":" Primero conecta el equipo. ","session_id":"s-1","route":"knowledge",
               "sources":[{"title":"Manual"},{"title":"Guía"}]}"""
        )
        assertEquals("Primero conecta el equipo.", reply.answer)
        assertEquals("s-1", reply.sessionId)
        assertEquals("knowledge", reply.route)
        assertEquals(2, reply.sourceCount)
        assertEquals("Primero conecta el equipo.\n\n(2 fuentes)", AssistantProtocol.displayText(reply))
    }

    @Test fun nullOrMissingFieldsAreTolerated() {
        val reply = AssistantProtocol.parse("""{"answer":"Hola","session_id":null}""")
        assertNull(reply.sessionId)
        assertEquals("", reply.route)
        assertEquals(0, reply.sourceCount)
        assertEquals("Hola", AssistantProtocol.displayText(reply))
    }

    @Test(expected = IllegalStateException::class)
    fun emptyAnswerIsAnError() {
        AssistantProtocol.parse("""{"answer":"  ","session_id":"s-1"}""")
    }
}
