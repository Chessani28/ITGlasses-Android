package com.itglasses.itglassesandroid

import org.json.JSONObject

/** Respuesta del cerebro de ITGlasses (`/api/v1/assistant/chat`). */
internal data class AssistantReply(
    val answer: String,
    val sessionId: String?,
    val route: String,
    val sourceCount: Int,
)

/**
 * Contrato con el asistente unificado del backend: RAG de manuales, historial,
 * búsqueda web y narración de procedimientos. `/api/v1/chat` solo llama al
 * modelo sin nada de eso, por lo que la app ya no lo usa.
 */
internal object AssistantProtocol {
    const val ENDPOINT = "/api/v1/assistant/chat"

    // Límites de AssistantRequest en backend/app/api/schemas.py.
    const val MAX_MESSAGE_LENGTH = 4000
    const val MAX_SESSION_ID_LENGTH = 128

    fun requestBody(message: String, sessionId: String?): String {
        val body = JSONObject().put("message", message.trim().take(MAX_MESSAGE_LENGTH))
        validSessionId(sessionId)?.let { body.put("session_id", it) }
        return body.toString()
    }

    fun parse(responseBody: String): AssistantReply {
        val json = JSONObject(responseBody)
        val answer = text(json, "answer")
        if (answer.isEmpty()) throw IllegalStateException("El asistente devolvió una respuesta vacía")
        return AssistantReply(
            answer = answer,
            sessionId = validSessionId(text(json, "session_id")),
            route = text(json, "route"),
            sourceCount = json.optJSONArray("sources")?.length() ?: 0,
        )
    }

    /** Texto para pantalla: la respuesta y, si hay, cuántas fuentes la respaldan. */
    fun displayText(reply: AssistantReply): String = when (reply.sourceCount) {
        0 -> reply.answer
        1 -> "${reply.answer}\n\n(1 fuente)"
        else -> "${reply.answer}\n\n(${reply.sourceCount} fuentes)"
    }

    // org.json devuelve la cadena "null" para valores JSON null.
    private fun text(json: JSONObject, key: String): String =
        if (json.isNull(key)) "" else json.optString(key, "").trim()

    private fun validSessionId(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_SESSION_ID_LENGTH }
}
