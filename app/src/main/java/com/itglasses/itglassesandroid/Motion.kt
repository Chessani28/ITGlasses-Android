package com.itglasses.itglassesandroid

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Light animations (stage A) that respect the system setting: with animations off, everything jumps to its
 * final state. ValueAnimator.areAnimatorsEnabled() exists from API 26; on 24-25 the duration scale is read.
 */
internal object Motion {
    /** Pure decision, tested on the JVM: API 26+ asks the system, older versions use the duration scale. */
    fun enabledFrom(sdk: Int, areAnimatorsEnabled: () -> Boolean, durationScale: () -> Float): Boolean =
        if (sdk >= Build.VERSION_CODES.O) areAnimatorsEnabled() else durationScale() > 0f

    fun enabled(context: Context): Boolean = enabledFrom(
        Build.VERSION.SDK_INT,
        areAnimatorsEnabled = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ValueAnimator.areAnimatorsEnabled() else true
        },
        durationScale = {
            try {
                Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            } catch (_: RuntimeException) {
                1f
            }
        },
    )
}

/** Slow opacity pulse (1.2 s, 1 -> 0.35 and back). start() is a no-op while running or with animations off. */
internal class Pulse(private val view: View) {
    private var animator: ObjectAnimator? = null

    fun start() {
        if (animator != null) return
        if (!Motion.enabled(view.context)) {
            view.alpha = 1f
            return
        }
        animator = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.35f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
        view.alpha = 1f
    }
}

/** 300 ms colour change from the current colour to a new target; immediate the first time or with animations off. */
internal class ColorTransition(private val context: Context, private val apply: (Int) -> Unit) {
    private var animator: ValueAnimator? = null
    private var current: Int? = null
    private var target: Int? = null

    fun to(color: Int) {
        if (color == target) return  // The countdown re-renders every second: do not restart.
        target = color
        animator?.cancel()
        val from = current
        if (from == null || !Motion.enabled(context)) {
            current = color
            apply(color)
            return
        }
        animator = ValueAnimator.ofObject(ArgbEvaluator(), from, color).apply {
            duration = 300
            addUpdateListener { value ->
                val c = value.animatedValue as Int
                current = c
                apply(c)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) animator = null
                }
            })
            start()
        }
    }
}

/** Three dots that blink in sequence (0, 200 and 400 ms, 1.2 s cycles). Callers show a static text instead
 *  when Motion.enabled() is false; start() is then a no-op. */
internal class TypingDots(private val dots: List<View>) {
    private val animators = mutableListOf<ObjectAnimator>()

    fun start() {
        if (animators.isNotEmpty() || dots.isEmpty() || !Motion.enabled(dots.first().context)) return
        dots.forEachIndexed { index, dot ->
            animators += ObjectAnimator.ofFloat(dot, View.ALPHA, 0.25f, 1f).apply {
                duration = 600
                startDelay = index * 200L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        }
    }

    fun stop() {
        animators.forEach { it.cancel() }
        animators.clear()
        dots.forEach { it.alpha = 1f }
    }
}

/** New bubble: 180 ms fade and 8 dp rise; nothing with animations off. */
/** Shows at once; hides with a 150 ms fade (at once with animations off). Safe to call repeatedly. */
internal fun fadeVisibility(view: View, shown: Boolean) {
    if (shown) {
        view.animate().cancel()
        view.alpha = 1f
        view.visibility = View.VISIBLE
        return
    }
    if (view.visibility != View.VISIBLE) return
    if (!Motion.enabled(view.context)) {
        view.visibility = View.GONE
        return
    }
    view.animate().alpha(0f).setDuration(150).withEndAction {
        view.visibility = View.GONE
        view.alpha = 1f
    }.start()
}

internal fun fadeIn(view: View) {
    if (!Motion.enabled(view.context)) return
    view.alpha = 0f
    view.translationY = 8 * view.resources.displayMetrics.density
    view.animate().alpha(1f).translationY(0f).setDuration(180).start()
}
