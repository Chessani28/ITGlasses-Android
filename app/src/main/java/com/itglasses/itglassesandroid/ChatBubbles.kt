package com.itglasses.itglassesandroid

import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Bubble views for ChatLog (stage C). Applies each ChatChange: removes, updates in place, adds at the end with a
 * short fade, then scrolls to the last message. Android-only: verified on the phone.
 */
internal class ChatBubbles(private val container: LinearLayout, private val scroll: ScrollView) {
    private class Row(
        val view: View,
        val bubble: TextView? = null,
        val notSent: TextView? = null,
        val dots: TypingDots? = null,
        /** Pixels taken by the avatar beside the bubble (0 without avatar). */
        val reserved: Int = 0,
    )

    private val context = container.context
    private val rows = mutableMapOf<Long, Row>()
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(context, id)
    /** Avatar of 28 dp plus 8 dp of space: what a bubble with avatar gives up (AVATAR_DP + AVATAR_GAP_DP). */
    private val avatarReserved get() = dp(AVATAR_DP + AVATAR_GAP_DP)

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
            row.dots?.stop()
            container.removeView(row.view)
        }
        for (entry in change.updated) {
            val user = entry as? ChatEntry.User ?: continue
            rows[user.id]?.notSent?.visibility =
                if (user.status == ChatEntry.Status.FAILED) View.VISIBLE else View.GONE
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

    /** Looping dots only in the foreground. */
    fun pauseAnimations() = rows.values.forEach { it.dots?.stop() }
    fun resumeAnimations() = rows.values.forEach { it.dots?.start() }

    private fun build(entry: ChatEntry): Row = when (entry) {
        is ChatEntry.User -> {
            val bubble = text(entry.text, R.color.itg_text_primary, R.drawable.bubble_user, selectable = true)
            val notSent = label(context.getString(R.string.chat_not_sent)).apply {
                visibility = if (entry.status == ChatEntry.Status.FAILED) View.VISIBLE else View.GONE
            }
            Row(column(Gravity.END, bubble, notSent), bubble = bubble, notSent = notSent)
        }
        is ChatEntry.Assistant -> {
            val bubble = text(entry.text, R.color.itg_text_primary, R.drawable.bubble_assistant, selectable = true,
                reserved = avatarReserved)
            Row(column(Gravity.START, withAvatar(bubble)), bubble = bubble, reserved = avatarReserved)
        }
        is ChatEntry.Notice -> {
            val bubble = text(entry.text, R.color.itg_warning, R.drawable.bubble_notice)
            Row(column(Gravity.CENTER_HORIZONTAL, bubble), bubble = bubble)
        }
        is ChatEntry.Typing -> waiting(Gravity.START, R.drawable.bubble_assistant,
            context.getString(R.string.chat_generating_label), context.getString(R.string.call_generating), avatar = true)
        is ChatEntry.Checking -> {
            val full = context.getString(R.string.pairing_call_checking)
            waiting(Gravity.CENTER_HORIZONTAL, null, full.removeSuffix("…"), full, avatar = false)
        }
    }

    /** Label + three blinking dots; with animations off, the static text with its "…". */
    private fun waiting(align: Int, bubbleBackground: Int?, label: String, static: String, avatar: Boolean): Row {
        val reserved = if (avatar) avatarReserved else 0
        fun framed(view: View) = if (avatar) withAvatar(view) else view
        if (!Motion.enabled(context)) {
            val bubble = text(static, R.color.itg_text_secondary, bubbleBackground, reserved = reserved)
            return Row(column(align, framed(bubble)), bubble = bubble, reserved = reserved)
        }
        val dots = List(3) {
            View(context).apply {
                setBackgroundResource(R.drawable.status_dot)
                backgroundTintList = android.content.res.ColorStateList.valueOf(color(R.color.itg_text_secondary))
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
                setTextColor(color(R.color.itg_text_secondary))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            dots.forEach { addView(it) }
        }
        return Row(column(align, framed(line)), dots = TypingDots(dots), reserved = reserved)
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
        }, LinearLayout.LayoutParams(dp(AVATAR_DP), dp(AVATAR_DP)).apply { marginEnd = dp(AVATAR_GAP_DP) })
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
        const val AVATAR_DP = 28
        const val AVATAR_GAP_DP = 8
    }
}
