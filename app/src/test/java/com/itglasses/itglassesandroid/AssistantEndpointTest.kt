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
        // Same request for both; the voice also says when it was heard through the glasses (history icon).
        assertTrue("el chat escrito y la voz deben usar la misma petición",
            Regex("""chatRequest\(baseUrl, message(, glasses)?\)""").findAll(activity).count() == 2)
        assertTrue("la voz debe decir si vino de los lentes", activity.contains("chatRequest(baseUrl, message, glasses)"))
    }

    @Test fun oneSessionAndTheVoiceSkipsTheSources() {
        assertTrue("la sesión devuelta debe pasar por AssistantSession.next",
            activity.contains("AssistantSession.next(assistantSession, returned)"))
        assertTrue("solo el modo emparejamiento manda session_id",
            activity.contains("if (pairing.configured) assistantSession?.let { json.put(\"session_id\", it) }"))
        // Speech app stage: the voice reads the backend's `speech` or the answer without sources (SpokenAnswer, which
        // goes through SpokenText); shared-token mode the whole answer, as before.
        assertTrue("la voz debe leer SpokenAnswer.of(reply.text, reply.speech) en el modo emparejamiento",
            activity.contains("synthesizeSpeech(if (inChat) SpokenAnswer.of(reply.text, reply.speech) else answer)"))
        assertTrue("speech ausente o null no debe romper la lectura",
            activity.contains("val speech = if (obj.isNull(\"speech\")) null else obj.optString(\"speech\")"))
    }
}
