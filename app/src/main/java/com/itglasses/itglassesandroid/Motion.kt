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
