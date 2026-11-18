package com.itglasses.itglassesandroid

/** One line of the pairing-mode conversation. Texts are already resolved by the Activity; never bodies or exceptions. */
internal sealed class ChatEntry {
    abstract val id: Long

    enum class Status { SENDING, SENT, FAILED }

    data class User(override val id: Long, val text: String, val status: Status) : ChatEntry()
    data class Assistant(override val id: Long, val text: String) : ChatEntry()
    data class Notice(override val id: Long, val text: String) : ChatEntry()
    /** "Generando respuesta" while the answer to message [forMessage] is pending. */
    data class Typing(override val id: Long, val forMessage: Long) : ChatEntry()
    /** Provisional "Comprobando el estado del dispositivo…" after a 403; replaced or removed when the check ends. */
    data class Checking(override val id: Long, val forMessage: Long) : ChatEntry()
}

/** What changed: views to add at the end (in order), to remove, and to update in place. */
internal data class ChatChange(
    val added: List<ChatEntry> = emptyList(),
    val removed: List<Long> = emptyList(),
    val updated: List<ChatEntry> = emptyList(),
)

/** Pairing-mode conversation (stage C). Pure Kotlin; UI thread only. At most [limit] entries, oldest dropped first. */
internal class ChatLog(private val limit: Int = 100) {
    private val list = mutableListOf<ChatEntry>()
    private var nextId = 1L

    val entries: List<ChatEntry> get() = list.toList()

    /** Nothing sent yet: the welcome (chat_empty) may show. Never true again after the first message. */
    val isEmpty: Boolean get() = list.isEmpty()

    /** New message: its bubble (sending) and "Generando respuesta". Drops any pending "Comprobando". */
    fun send(text: String): Pair<Long, ChatChange> {
        val removed = removeAll { it is ChatEntry.Checking || it is ChatEntry.Typing }
        val user = ChatEntry.User(nextId++, text, ChatEntry.Status.SENDING)
        val typing = ChatEntry.Typing(nextId++, user.id)
        return user.id to finish(added = listOf(user, typing), removed = removed)
    }

    fun answered(message: Long, answer: String): ChatChange {
        val removed = removeAll { it is ChatEntry.Typing && it.forMessage == message }
        return finish(listOf(ChatEntry.Assistant(nextId++, answer)), removed, mark(message, ChatEntry.Status.SENT))
    }

    /** Not sent. [notice] is shown below it unless the pairing card already explains (then null). */
    fun failed(message: Long, notice: String?): ChatChange {
        val removed = removeAll { it is ChatEntry.Typing && it.forMessage == message }
        val added = listOfNotNull(notice?.let { ChatEntry.Notice(nextId++, it) })
        return finish(added, removed, mark(message, ChatEntry.Status.FAILED))
    }

    /** 403: not sent, with a provisional "Comprobando…" until [resolveCheck]. */
    fun checking(message: Long): ChatChange {
        val removed = removeAll { (it is ChatEntry.Typing && it.forMessage == message) || it is ChatEntry.Checking }
        return finish(listOf(ChatEntry.Checking(nextId++, message)), removed, mark(message, ChatEntry.Status.FAILED))
    }

    /** End of the status check: "Comprobando…" becomes [notice], or simply goes away when null. */
    fun resolveCheck(notice: String?): ChatChange {
        val removed = removeAll { it is ChatEntry.Checking }
        if (removed.isEmpty()) return ChatChange()
        return finish(listOfNotNull(notice?.let { ChatEntry.Notice(nextId++, it) }), removed)
    }

    private fun removeAll(predicate: (ChatEntry) -> Boolean): List<Long> {
        val gone = list.filter(predicate).map { it.id }
        list.removeAll(predicate)
        return gone
    }

    private fun mark(message: Long, status: ChatEntry.Status): List<ChatEntry> {
        val index = list.indexOfFirst { it is ChatEntry.User && it.id == message }
        if (index < 0) return emptyList()  // Already dropped by the limit.
        val updated = (list[index] as ChatEntry.User).copy(status = status)
        list[index] = updated
        return listOf(updated)
    }

    private fun finish(added: List<ChatEntry>, removed: List<Long>, updated: List<ChatEntry> = emptyList()): ChatChange {
        list.addAll(added)
        val dropped = mutableListOf<Long>()
        while (list.size > limit) dropped.add(list.removeAt(0).id)
        val droppedSet = dropped.toSet()
        return ChatChange(
            added = added.filter { it.id !in droppedSet },
            removed = removed + dropped.filter { id -> added.none { it.id == id } },
            updated = updated.filter { it.id !in droppedSet },
        )
    }
}

/** How a failed chat call shows in the conversation. The status line is never used for chat failures. */
internal sealed class ChatFailure {
    /** "No enviado" plus a notice bubble with this text. */
    data class WithNotice(val text: CallText, val arg: String? = null) : ChatFailure()
    /** Only "No enviado": the pairing card already explains (deleted device). */
    data object NotSentOnly : ChatFailure()
    /** 403: provisional "Comprobando…" until PairingController's status check ends. */
    data object Check : ChatFailure()
}

internal enum class ChatCheckResolution {
    /** Still connected: the function is not available for the phone. */
    UNAVAILABLE,
    /** No longer connected (blocked, deleted…): the card explains, nothing is added. */
    LEFT_TO_CARD,
}

internal object ChatFailures {
    fun present(outcome: CallOutcome): ChatFailure = when (outcome) {
        CallOutcome.DeviceCheck -> ChatFailure.Check
        is CallOutcome.Notice ->
            if (outcome.text == CallText.REMOVED) ChatFailure.NotSentOnly else ChatFailure.WithNotice(outcome.text, outcome.arg)
    }

    /** Null while the check is still running (or none started). */
    fun resolution(check: CallCheck): ChatCheckResolution? = when (check) {
        CallCheck.UNAVAILABLE -> ChatCheckResolution.UNAVAILABLE
        CallCheck.RESOLVED -> ChatCheckResolution.LEFT_TO_CARD
        CallCheck.IDLE, CallCheck.CHECKING -> null
    }
}

/** Widest a chat bubble may be: 80 % of the width available in the conversation. */
internal object BubbleWidth {
    const val FRACTION = 0.8f

    /**
     * [available] is the container's inner width (0 before the first layout, then [fallback] is used).
     * [reserved] is taken from the limit, so a row with the assistant avatar also stays within 80 %.
     */
    fun max(available: Int, fallback: Int, reserved: Int = 0): Int {
        val base = if (available > 0) available else fallback
        return ((base.coerceAtLeast(0) * FRACTION).toInt() - reserved.coerceAtLeast(0)).coerceAtLeast(0)
    }
}

/** Welcome in the conversation area: pairing mode only, phone Connected, nothing sent yet. Voice does not hide it. */
internal object ChatEmpty {
    fun visible(pairingMode: Boolean, connected: Boolean, logEmpty: Boolean): Boolean = pairingMode && connected && logEmpty
}
