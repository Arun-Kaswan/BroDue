package com.abk.brodue

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat

class SwipeLogoutView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onSwipeComplete: (() -> Unit)? = null
    var label: String = "Swipe to logout"

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(6f * density, 0f, 2f * density, Color.parseColor("#22000000"))
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 13f * density
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.positive)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val trackRect = RectF()
    private val thumbRect = RectF()

    private var thumbPosition = 0f // 0 = start, 1 = end
    private var isDragging = false
    private var downX = 0f
    private var downY = 0f
    private var animator: ValueAnimator? = null

    private val accentTrackColor = Color.parseColor("#DDF0E4") // positive_container
    private val accentTrackBorder = ContextCompat.getColor(context, R.color.positive_border)
    private val greenTrackColor = Color.parseColor("#F7E3E0") // negative_container
    private val greenTrackBorder = ContextCompat.getColor(context, R.color.negative_border)
    private val greenThumbColor = ContextCompat.getColor(context, R.color.negative)
    private val accentThumbColor = ContextCompat.getColor(context, R.color.positive)

    init {
        isClickable = true
        isFocusable = true
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = (64 * density).toInt()
        val h = resolveSize(desiredH, heightMeasureSpec)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, h)
    }

    private fun animateThumb(to: Float, onEnd: (() -> Unit)? = null) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(thumbPosition, to).apply {
            duration = 260
            interpolator = android.view.animation.DecelerateInterpolator(1.5f)
            addUpdateListener {
                thumbPosition = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    private fun blendColor(from: Int, to: Int, ratio: Float): Int {
        val r = Color.red(from) + ((Color.red(to) - Color.red(from)) * ratio).toInt()
        val g = Color.green(from) + ((Color.green(to) - Color.green(from)) * ratio).toInt()
        val b = Color.blue(from) + ((Color.blue(to) - Color.blue(from)) * ratio).toInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = h / 2f
        trackRect.set(0f, 0f, w, h)

        // Track with 1dp border, fill blended red->green
        val t = thumbPosition
        val strokeW = 1f *      density
        val inset = strokeW / 2f
        val borderRect = RectF(trackRect.left + inset, trackRect.top + inset, trackRect.right - inset, trackRect.bottom - inset)
        val borderRadius = radius - inset
        trackPaint.style = Paint.Style.FILL
        trackPaint.color = blendColor(accentTrackColor, greenTrackColor, t)
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)
        // Border blended red->green - inset so stroke stays inside
        trackPaint.style = Paint.Style.STROKE
        trackPaint.color = blendColor(accentTrackBorder, greenTrackBorder, t)
        trackPaint.strokeWidth = strokeW
        canvas.drawRoundRect(borderRect, borderRadius, borderRadius, trackPaint)
        trackPaint.style = Paint.Style.FILL

        // Text centered
        val textY = h / 2f + textPaint.textSize * 0.35f
        // Fade text as thumb moves
        textPaint.alpha = ((1f - thumbPosition * 0.9f) * 255).toInt().coerceIn(0, 255)
        canvas.drawText(label, w / 2f, textY, textPaint)
        textPaint.alpha = 255

        // Thumb
        val thumbSize = h - 8 * density
        val thumbRadius = thumbSize / 2f
        val startX = 4 * density + thumbRadius
        val endX = w - 4 * density - thumbRadius
        val cx = startX + (endX - startX) * thumbPosition
        val cy = h / 2f

        // Thumb circle white
        thumbPaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, thumbRadius, thumbPaint)
        // Icon color blended red->green
        arrowPaint.color = blendColor(accentThumbColor, greenThumbColor, t)
        // Morphing icon: > (chevron) -> ✓ (check) - no fade, transform
        // Arrow size
        val arrowSize = 10 * density
        arrowPaint.strokeWidth = 2.2f * density
        // Chevron points (>)
        val chevX1 = -arrowSize * 0.35f; val chevY1 = -arrowSize * 0.35f
        val chevX2 = arrowSize * 0.35f; val chevY2 = 0f
        val chevX3 = -arrowSize * 0.35f; val chevY3 = arrowSize * 0.35f
        // Check points (✓)
        val checkX1 = -arrowSize * 0.45f; val checkY1 = arrowSize * 0.05f
        val checkX2 = -arrowSize * 0.05f; val checkY2 = arrowSize * 0.35f
        val checkX3 = arrowSize * 0.45f; val checkY3 = -arrowSize * 0.35f
        // Interpolate
        fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
        val x1 = lerp(chevX1, checkX1, t)
        val y1 = lerp(chevY1, checkY1, t)
        val x2 = lerp(chevX2, checkX2, t)
        val y2 = lerp(chevY2, checkY2, t)
        val x3 = lerp(chevX3, checkX3, t)
        val y3 = lerp(chevY3, checkY3, t)
        // Draw two lines that morph
        canvas.drawLine(cx + x1, cy + y1, cx + x2, cy + y2, arrowPaint)
        canvas.drawLine(cx + x2, cy + y2, cx + x3, cy + y3, arrowPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                // Check if touch is on thumb
                val w = width.toFloat()
                val h = height.toFloat()
                val thumbSize = h - 8 * density
                val thumbRadius = thumbSize / 2f
                val startX = 4 * density + thumbRadius
                val endX = w - 4 * density - thumbRadius
                val cx = startX + (endX - startX) * thumbPosition
                val cy = h / 2f
                val dist = kotlin.math.hypot(event.x - cx, event.y - cy)
                if (dist > thumbRadius + 12 * density) {
                    // Touch not on thumb, ignore for drag, but still allow tap to start
                    // We allow drag from anywhere on track
                }
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!isDragging && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                    isDragging = true
                }
                if (isDragging) {
                    val w = width.toFloat()
                    val h = height.toFloat()
                    val thumbSize = h - 8 * density
                    val startX = 4 * density + thumbSize / 2f
                    val endX = w - 4 * density - thumbSize / 2f
                    val progress = ((event.x - startX) / (endX - startX)).coerceIn(0f, 1f)
                    thumbPosition = progress
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (thumbPosition > 0.85f) {
                        // Success - complete
                        animateThumb(1f) {
                            onSwipeComplete?.invoke()
                        }
                    } else {
                        // Snap back
                        animateThumb(0f)
                    }
                    return true
                } else {
                    // Tap - do nothing, need swipe
                    val dx = kotlin.math.abs(event.x - downX)
                    val dy = kotlin.math.abs(event.y - downY)
                    if (dx < touchSlop && dy < touchSlop) {
                        performClick()
                        return true
                    }
                }
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
