package com.itglasses.itglassesandroid

import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat

/**
 * Bubble views for ChatLog (stage C, voice in stage B3). Applies each ChatChange: removes, updates in place (or
 * rebuilds the row in its own position when what is drawn changes), adds at the end with a short fade, then
 * scrolls to the last message. Android-only: verified on the phone.
 */
internal class ChatBubbles(
    private val container: LinearLayout,
    private val scroll: ScrollView,
    /** 👍 (1) or 👎 (-1) tapped under a saved answer; the Activity sends it and calls [ChatLog.voted]. */
    private val onVote: (ChatEntry.Assistant, Int) -> Unit = { _, _ -> },
) {
    private class Row(
        val view: View,
        val bubble: TextView? = null,
        val notSent: TextView? = null,
        val dots: TypingDots? = null,
        /** Pixels taken by the avatar beside the bubble (0 without avatar). */
        val reserved: Int = 0,
        /** Voice answer: "Preparando la voz…" / "Hablando…" under the bubble. */
        val speech: SpeechLabel? = null,
        /** Voice placeholder: rebuilt in place once its text arrives. */
        val placeholder: Boolean = false,
        /** 👍/👎 under a saved answer. */
        val votes: VoteRow? = null,
    )

    private class VoteRow(val row: View, val up: TextView, val down: TextView) {
        var entry: ChatEntry.Assistant? = null
    }

    private class SpeechLabel(val row: View, val text: TextView, val pulse: Pulse) {
        var speaking = false
    }

    private val context = container.context
    private val rows = mutableMapOf<Long, Row>()
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(context, id)
    /** Avatar of 28 dp plus 8 dp of space (ChatAvatar.RESERVED_DP): what a bubble with avatar gives up. */
    private val avatarReserved get() = dp(ChatAvatar.RESERVED_DP)

    /** 80 % of the container's real width, minus [reserved]; the screen width only before the first layout. */
    private fun maxBubbleWidth(reserved: Int = 0) = BubbleWidth.max(
        available = container.width - container.paddingLeft - container.paddingRight,
        fallback = context.resources.displayMetrics.widthPixels,
        reserved = reserved,
    )

    init {
        // Rotation, split screen or a resized window: recompute the limit of every bubble already shown.
        container.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) container.post {
                rows.values.forEach { row ->
                    val width = maxBubbleWidth(row.reserved)
                    row.bubble?.let { if (it.maxWidth != width) it.maxWidth = width }
                }
            }
        }
    }

    fun apply(change: ChatChange) {
        for (id in change.removed) rows.remove(id)?.let { row ->
            stop(row)
            container.removeView(row.view)
        }
        for (entry in change.updated) {
            val row = rows[entry.id] ?: continue
            when (entry) {
                is ChatEntry.User ->
                    if (row.placeholder && entry.status != ChatEntry.Status.TRANSCRIBING) rebuild(entry, row)
                    else row.notSent?.visibility = if (entry.status == ChatEntry.Status.FAILED) View.VISIBLE else View.GONE
                is ChatEntry.Assistant -> {
                    row.speech?.let { show(it, entry.speech) }
                    row.votes?.let { show(it, entry) }
                }
                else -> Unit
            }
        }
        for (entry in change.added) {
            val row = build(entry)
            rows[entry.id] = row
            container.addView(row.view)
            row.dots?.start()
            fadeIn(row.view)
        }
        if (change.added.isNotEmpty()) scroll.post {
            val bottom = scroll.getChildAt(0)?.height ?: 0
            if (Motion.enabled(context)) scroll.smoothScrollTo(0, bottom) else scroll.scrollTo(0, bottom)
        }
    }

    /** Looping dots and the speaker's pulse only in the foreground. */
    fun pauseAnimations() = rows.values.forEach { row ->
        row.dots?.stop()
        row.speech?.pulse?.stop()
    }

    fun resumeAnimations() = rows.values.forEach { row ->
        row.dots?.start()
        row.speech?.let { if (it.speaking) it.pulse.start() }
    }

    /** Same position, new views: the placeholder becomes the message bubble. */
    private fun rebuild(entry: ChatEntry, old: Row) {
        val index = container.indexOfChild(old.view)
        stop(old)
        container.removeView(old.view)
        val row = build(entry)
        rows[entry.id] = row
        if (index >= 0) container.addView(row.view, index) else container.addView(row.view)
        row.dots?.start()
        fadeIn(row.view)
    }

    private fun stop(row: Row) {
        row.dots?.stop()
        row.speech?.pulse?.stop()
    }

    private fun build(entry: ChatEntry): Row = when (entry) {
        is ChatEntry.User -> if (entry.status == ChatEntry.Status.TRANSCRIBING) {
            // On the user's green bubble the label uses text_primary: text_secondary would be below 4.5:1.
            waiting(Gravity.END, R.drawable.bubble_user, context.getString(R.string.chat_transcribing_label),
                context.getString(R.string.voice_state_transcribing), avatar = ChatAvatar.has(entry), textColorRes = R.color.itg_text_primary)
                .let { Row(it.view, it.bubble, dots = it.dots, placeholder = true) }
        } else {
            val bubble = text(entry.text, R.color.itg_text_primary, R.drawable.bubble_user, selectable = true)
            val notSent = label(context.getString(R.string.chat_not_sent)).apply {
                visibility = if (entry.status == ChatEntry.Status.FAILED) View.VISIBLE else View.GONE
            }
            // Grey "Motor de voz básico" only when the Vosk fallback transcribed it (never with Whisper).
            val basic = label(context.getString(R.string.chat_basic_engine)).apply {
                visibility = if (entry.showsBasicEngine) View.VISIBLE else View.GONE
            }
            Row(column(Gravity.END, bubble, basic, notSent), bubble = bubble, notSent = notSent)
        }
        is ChatEntry.Assistant -> {
            val reserved = dp(ChatAvatar.reservedDp(entry))
            val bubble = text(entry.text, R.color.itg_text_primary, R.drawable.bubble_assistant, selectable = true,
                reserved = reserved)
            val speech = speechLabel()
            show(speech, entry.speech)
            val votes = if (entry.messageId != null) voteRow(reserved).also { show(it, entry) } else null
            val parts = listOfNotNull(withAvatar(bubble), speech.row, votes?.row)
            Row(column(Gravity.START, *parts.toTypedArray()), bubble = bubble, reserved = reserved, speech = speech,
                votes = votes)
        }
        is ChatEntry.Notice -> {
            val bubble = text(entry.text, R.color.itg_warning, R.drawable.bubble_notice)
            Row(column(Gravity.CENTER_HORIZONTAL, bubble), bubble = bubble)
        }
        is ChatEntry.Typing -> when (entry.waiting) {
            ChatEntry.Waiting.GENERATING -> waiting(Gravity.START, R.drawable.bubble_assistant,
                context.getString(R.string.chat_generating_label), context.getString(R.string.call_generating), avatar = ChatAvatar.has(entry))
            ChatEntry.Waiting.THINKING -> waiting(Gravity.START, R.drawable.bubble_assistant,
                context.getString(R.string.chat_thinking_label), context.getString(R.string.voice_state_thinking), avatar = ChatAvatar.has(entry))
        }
        is ChatEntry.Checking -> {
            val full = context.getString(R.string.pairing_call_checking)
            waiting(Gravity.CENTER_HORIZONTAL, null, full.removeSuffix("…"), full, avatar = ChatAvatar.has(entry))
        }
    }

    /** Label + three blinking dots; with animations off, the static text with its "…". */
    private fun waiting(
        align: Int,
        bubbleBackground: Int?,
        label: String,
        static: String,
        avatar: Boolean,
        textColorRes: Int = R.color.itg_text_secondary,
    ): Row {
        val reserved = if (avatar) avatarReserved else 0
        fun framed(view: View) = if (avatar) withAvatar(view) else view
        if (!Motion.enabled(context)) {
            val bubble = text(static, textColorRes, bubbleBackground, reserved = reserved)
            return Row(column(align, framed(bubble)), bubble = bubble, reserved = reserved)
        }
        val dots = List(3) {
            View(context).apply {
                setBackgroundResource(R.drawable.status_dot)
                backgroundTintList = android.content.res.ColorStateList.valueOf(color(textColorRes))
                layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginStart = dp(4) }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (bubbleBackground != null) setBackgroundResource(bubbleBackground)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            contentDescription = static
            addView(TextView(context).apply {
                text = label
                setTextColor(color(textColorRes))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            dots.forEach { addView(it) }
        }
        return Row(column(align, framed(line)), dots = TypingDots(dots), reserved = reserved)
    }

    /**
     * Small speaker + "Preparando la voz…" / "Hablando…" / "No se pudo leer en voz alta." under a voice answer,
     * aligned with the bubble (past the avatar). A polite live region: TalkBack announces the change without
     * cutting the answer.
     */
    /** 👍 / 👎 under a saved answer, aligned with the bubble (after the avatar). */
    private fun voteRow(reserved: Int): VoteRow {
        fun thumb(symbol: String, description: Int) = TextView(context).apply {
            text = symbol
            textSize = 16f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            contentDescription = context.getString(description)
            isClickable = true
            isFocusable = true
            minWidth = dp(40)
            minHeight = dp(36)
            gravity = Gravity.CENTER
        }
        val up = thumb("\uD83D\uDC4D", R.string.feedback_useful)
        val down = thumb("\uD83D\uDC4E", R.string.feedback_not_useful)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(reserved, 0, 0, 0)
            addView(up)
            addView(down)
        }
        val votes = VoteRow(row, up, down)
        up.setOnClickListener { votes.entry?.let { onVote(it, 1) } }
        down.setOnClickListener { votes.entry?.let { onVote(it, -1) } }
        return votes
    }

    /** The pressed thumb is fully visible and marked; the other one stays discreet. */
    private fun show(votes: VoteRow, entry: ChatEntry.Assistant) {
        votes.entry = entry
        for ((view, value) in listOf(votes.up to 1, votes.down to -1)) {
            val pressed = entry.vote == value
            view.alpha = if (pressed) 1f else 0.45f
            view.isSelected = pressed
            view.setBackgroundResource(if (pressed) R.drawable.bubble_user else 0)
        }
    }

    private fun speechLabel(): SpeechLabel {
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_speaker)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val text = label("").apply {
            ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(avatarReserved, dp(2), 0, 0)
            addView(icon, LinearLayout.LayoutParams(dp(SPEAKER_DP), dp(SPEAKER_DP)))
            addView(text)
        }
        return SpeechLabel(row, text, Pulse(icon))
    }

    /** Gentle pulse while speaking (still with animations off: Pulse handles that); hidden without a label. */
    private fun show(label: SpeechLabel, speech: ChatEntry.Speech?) {
        label.speaking = speech == ChatEntry.Speech.SPEAKING
        when (speech) {
            null -> label.row.visibility = View.GONE
            ChatEntry.Speech.PREPARING -> label.text.text = context.getString(R.string.voice_state_synthesizing)
            ChatEntry.Speech.SPEAKING -> label.text.text = context.getString(R.string.voice_state_speaking)
            ChatEntry.Speech.FAILED -> label.text.text = context.getString(R.string.voice_unspoken)
        }
        if (speech != null) label.row.visibility = View.VISIBLE
        if (label.speaking) label.pulse.start() else label.pulse.stop()
    }

    /** Avatar (decorative, not read by TalkBack) at the bottom left of the bubble, 8 dp apart. */
    private fun withAvatar(bubble: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        addView(ImageView(context).apply {
            setBackgroundResource(R.drawable.avatar_assistant)
            setImageResource(R.drawable.ic_assistant)
            val inset = dp(5)
            setPadding(inset, inset, inset, inset)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(ChatAvatar.SIZE_DP), dp(ChatAvatar.SIZE_DP)).apply { marginEnd = dp(ChatAvatar.GAP_DP) })
        addView(bubble, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun text(value: String, textColorRes: Int, bubbleBackground: Int?, selectable: Boolean = false, reserved: Int = 0) =
        TextView(context).apply {
            text = value
            setTextColor(color(textColorRes))
            if (bubbleBackground != null) setBackgroundResource(bubbleBackground)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            maxWidth = maxBubbleWidth(reserved)
            setTextIsSelectable(selectable)
        }

    private fun label(value: String) = TextView(context).apply {
        text = value
        textSize = 12f
        setTextColor(color(R.color.itg_text_secondary))
        setPadding(dp(4), dp(2), dp(4), 0)
    }

    /**
     * Full-width row; each child wraps its content and sits at [align] (layout_gravity). Without explicit params a
     * vertical LinearLayout gives its children MATCH_PARENT, which stretched every bubble to the whole width.
     */
    private fun column(align: Int, vararg children: View) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(4); bottomMargin = dp(4) }
        children.forEach { child ->
            addView(child, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { gravity = align })
        }
    }

    private companion object {
        const val SPEAKER_DP = 14
    }
}
