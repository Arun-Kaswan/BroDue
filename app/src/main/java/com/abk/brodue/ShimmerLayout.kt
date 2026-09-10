package com.abk.brodue

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout

class ShimmerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val sweep = 180f * resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    }
    private var translate = -sweep
    private var animator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (childCount > 0) startShimmer()
    }

    override fun onViewAdded(child: View?) {
        super.onViewAdded(child)
        if (isAttachedToWindow) startShimmer()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (childCount == 0 || width == 0) {
            super.dispatchDraw(canvas)
            return
        }
        val sc = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), paint)
        super.dispatchDraw(canvas)
        highlight.shader = LinearGradient(
            translate - sweep, 0f, translate + sweep, 0f,
            intArrayOf(Color.TRANSPARENT, Color.WHITE, Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), highlight)
        canvas.restoreToCount(sc)
    }

    private fun startShimmer() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                translate = -sweep + (it.animatedValue as Float) * (width.toFloat() + 2 * sweep)
                invalidate()
            }
            start()
        }
    }
}
