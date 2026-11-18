package com.itglasses.itglassesandroid

import org.junit.Assert.assertTrue
import org.junit.Test

/** Pairing mode talks to the same assistant as the web panel; shared-token mode keeps /api/v1/chat. */
class AssistantEndpointTest {
    private val activity by lazy { ModuleFiles.file("src/main/java/com/itglasses/MainActivity.kt").readText() }

    @Test fun pairingModeUsesTheAssistantAndSharedTokenModeTheOldChat() {
        assertTrue("falta el endpoint del asistente", activity.contains("ASSISTANT_ENDPOINT = \"/api/v1/assistant/chat\""))
        assertTrue("el modo token compartido debe seguir con /api/v1/chat", activity.contains("CHAT_ENDPOINT = \"/api/v1/chat\""))
        assertTrue("el endpoint debe depender del modo",
            activity.contains("if (pairing.configured) ASSISTANT_ENDPOINT else CHAT_ENDPOINT"))
        assertTrue("el chat escrito y la voz deben usar la misma petición",
            Regex("""chatRequest\(baseUrl, message\)""").findAll(activity).count() == 2)
    }

    @Test fun oneSessionAndTheVoiceSkipsTheSources() {
        assertTrue("la sesión devuelta debe pasar por AssistantSession.next",
            activity.contains("AssistantSession.next(assistantSession, returned)"))
        assertTrue("solo el modo emparejamiento manda session_id",
            activity.contains("if (pairing.configured) assistantSession?.let { json.put(\"session_id\", it) }"))
        assertTrue("la voz debe leer SpokenText.of(answer) en el modo emparejamiento",
            activity.contains("synthesizeSpeech(if (inChat) SpokenText.of(answer) else answer)"))
    }
}
