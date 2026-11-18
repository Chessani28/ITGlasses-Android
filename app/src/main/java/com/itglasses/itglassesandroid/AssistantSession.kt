package com.itglasses.itglassesandroid

/**
 * Session of /api/v1/assistant/chat (pairing mode): one conversation shared by typed chat and voice, kept in
 * memory only. The backend keys it per device and starts a new one after 30 min; it creates one when none is sent.
 */
internal object AssistantSession {
    /** Same limit as the backend's AssistantRequest.session_id. */
    const val MAX_LENGTH = 128
    private val safe = Regex("[A-Za-z0-9._:-]{1,$MAX_LENGTH}")

    /** The session returned by the backend if it is a safe value; otherwise the current one stays. */
    fun next(current: String?, returned: String?): String? = returned?.takeIf { safe.matches(it) } ?: current
}
