package com.itglasses.itglassesandroid

import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.itglasses.itglassesandroid.databinding.VoiceScreenBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Full voice screen (stage B2), added on top of android.R.id.content while listening; activity_main is not touched.
 * TalkBack: pane title "Escuchando…", focus on "Detener y enviar", everything behind hidden while it is open.
 * UI thread only.
 */
internal class VoiceScreen(
    private val activity: AppCompatActivity,
    /** Root of activity_main: hidden from TalkBack while the screen is open. */
    private val behind: View,
    private val onStop: () -> Unit,
    private val onCancel: () -> Unit,
) {
    private var binding: VoiceScreenBinding? = null
    private var levels: Job? = null
    private var behindImportance = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    private val history = LevelHistory(VoiceShapes.BARS)

    val isShown: Boolean get() = binding != null

    fun show(meter: VoiceMeter) {
        if (binding != null) return
        hideKeyboard()
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val b = VoiceScreenBinding.inflate(activity.layoutInflater, content, false)
        val animated = Motion.enabled(activity)
        b.voiceVisualizer.animated = animated
        b.tvVoiceSeconds.text = activity.getString(R.string.voice_screen_seconds, 0)
        b.btnVoiceStop.setOnClickListener { onStop() }
        b.btnVoiceCancel.setOnClickListener { onCancel() }
        ViewCompat.setAccessibilityPaneTitle(b.root, activity.getString(R.string.voice_screen_listening))

        behindImportance = behind.importantForAccessibility
        behind.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        content.addView(b.root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        binding = b
        if (animated) {
            b.root.alpha = 0f
            b.root.animate().alpha(1f).setDuration(FADE_MS).start()
        }
        b.btnVoiceStop.post { b.btnVoiceStop.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }

        history.clear()
        levels = activity.lifecycleScope.launch {
            var shown = 0f
            while (isActive) {
                delay(POLL_MS)
                shown = VoiceLevel.smooth(shown, meter.level, animated)
                history.push(shown)
                b.voiceVisualizer.setLevels(shown, history.snapshot())
            }
        }
    }

    fun seconds(value: Int) {
        binding?.tvVoiceSeconds?.text = activity.getString(R.string.voice_screen_seconds, value)
    }

    /** Safe to call twice. TalkBack focus goes back to [focusAfter] (btn_voice). */
    fun hide(focusAfter: View?) {
        val b = binding ?: return
        binding = null
        levels?.cancel()
        levels = null
        behind.importantForAccessibility = behindImportance
        b.btnVoiceStop.setOnClickListener(null)
        b.btnVoiceCancel.setOnClickListener(null)
        val remove = { (b.root.parent as? ViewGroup)?.removeView(b.root); Unit }
        if (Motion.enabled(activity)) {
            b.root.isClickable = true  // Still blocks touches while it fades.
            b.root.animate().alpha(0f).setDuration(FADE_MS).withEndAction(remove).start()
        } else remove()
        focusAfter?.post { focusAfter.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
    }

    private fun hideKeyboard() {
        val focused = activity.currentFocus ?: return
        activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(focused.windowToken, 0)
        focused.clearFocus()
    }

    private companion object {
        const val FADE_MS = 150L
        const val POLL_MS = 50L
    }
}
