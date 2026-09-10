package com.abk.brodue

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

// App-wide rule: edge-to-edge with ALWAYS BLACK status bar icons (no per-page overrides)
fun AppCompatActivity.edgeToEdgeBlackIcons() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.light(
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT
        ),
        navigationBarStyle = SystemBarStyle.light(
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT
        )
    )
}

object UiUtils {

    val EASE_OUT: Interpolator = PathInterpolator(0f, 0f, 0.58f, 1f)

    // Low-end devices (Android Go / <=2-3GB RAM): skip continuous animations
    // (rolling numbers, list change anims) and snap to final values. One-shot
    // micro-anims (pulses, bounces) are cheap enough to keep everywhere.
    @Volatile
    private var lowRam: Boolean? = null

    fun reducedMotion(context: android.content.Context): Boolean {
        lowRam?.let { return it }
        val v = try {
            val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as? android.app.ActivityManager
            am?.isLowRamDevice == true
        } catch (_: Exception) {
            false
        }
        lowRam = v
        return v
    }

    fun crossfade(button: MaterialButton, swap: () -> Unit) {
        button.animate().cancel()
        button.animate()
            .alpha(0f)
            .setDuration(160)
            .setInterpolator(EASE_OUT)
            .withEndAction {
                swap()
                button.animate().alpha(1f).setDuration(160).setInterpolator(EASE_OUT).start()
            }
            .start()
    }

    private fun blendColor(from: Int, to: Int, ratio: Float): Int {
        val f = ratio.coerceIn(0f, 1f)
        val a = (android.graphics.Color.alpha(from) +
            ((android.graphics.Color.alpha(to) - android.graphics.Color.alpha(from)) * f)).toInt()
        val r = (android.graphics.Color.red(from) +
            ((android.graphics.Color.red(to) - android.graphics.Color.red(from)) * f)).toInt()
        val g = (android.graphics.Color.green(from) +
            ((android.graphics.Color.green(to) - android.graphics.Color.green(from)) * f)).toInt()
        val b = (android.graphics.Color.blue(from) +
            ((android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * f)).toInt()
        return android.graphics.Color.argb(
            a.coerceIn(0, 255), r.coerceIn(0, 255),
            g.coerceIn(0, 255), b.coerceIn(0, 255)
        )
    }

    // One-shot pop-in for red-dot badges (plays once per appearance)
    fun popShow(view: android.view.View) {
        view.visibility = android.view.View.VISIBLE
        view.alpha = 0f
        view.scaleX = 0.4f
        view.scaleY = 0.4f
        view.animate()
            .alpha(1f)
            .scaleX(1.15f)
            .scaleY(1.15f)
            .setDuration(180)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.8f))
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            .start()
    }

    fun popHide(view: android.view.View) {
        view.animate().cancel()
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.visibility = android.view.View.GONE
    }

    private class Roll(val anim: ValueAnimator, val to: Long)
    private val rolls = java.util.WeakHashMap<android.widget.TextView, Roll>()

    // A roll to this exact value is already playing - silent re-sets must
    // leave it alone or the value flashes final, jumps back, then rolls.
    fun isRollingTo(textView: android.widget.TextView, value: Long): Boolean {
        val r = rolls[textView] ?: return false
        return r.to == value && r.anim.isStarted
    }

    fun cancelRoll(textView: android.widget.TextView) {
        rolls[textView]?.anim?.cancel()
    }

    fun rollingAmount(
        textView: android.widget.TextView,
        from: Long,
        to: Long,
        formatter: (Long) -> CharSequence,
        fromColor: Int = textView.currentTextColor,
        toColor: Int = textView.currentTextColor
    ) {
        rolls[textView]?.anim?.cancel()
        if (from == to || reducedMotion(textView.context)) {
            rolls.remove(textView)
            textView.text = formatter(to)
            textView.setTextColor(toColor)
            return
        }
        val delta = to - from
        // Whole-rupee endpoints: snap intermediates to whole rupees so no
        // decimal flashes when nothing actually has paise.
        val wholeOnly = from % 100 == 0L && to % 100 == 0L
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700L
            startDelay = 250L
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                var v = from + (delta * fraction).toLong()
                if (wholeOnly) v = Math.round(v / 100.0) * 100
                textView.text = formatter(v)
                textView.setTextColor(blendColor(fromColor, toColor, fraction))
            }
        }
        rolls[textView] = Roll(animator, to)
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                // Guarded: a cancel() may also route here - only the current
                // roll may settle the final value.
                if (rolls[textView]?.anim === animation) {
                    rolls.remove(textView)
                    textView.text = formatter(to)
                    textView.setTextColor(toColor)
                }
            }

            override fun onAnimationCancel(animation: android.animation.Animator) {
                if (rolls[textView]?.anim === animation) rolls.remove(textView)
            }
        })
        animator.start()
    }
}