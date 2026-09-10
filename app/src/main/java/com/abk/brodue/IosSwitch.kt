package com.abk.brodue

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat

class IosSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var checkedChangeListener: ((IosSwitch, Boolean) -> Unit)? = null

    var isChecked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            animateThumb(if (value) 1f else 0f)
            checkedChangeListener?.invoke(this, value)
            // For accessibility / parent listeners
            isSelected = value
        }

    private var thumbPosition = 0f // 0 = off, 1 = on
    private var animator: ValueAnimator? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var isDragging = false

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val trackRect = RectF()

    // Colors from app theme - reuse existing design system
    private val accentColor = ContextCompat.getColor(context, R.color.primary) // #5B6BA3
    private val offTrackColor = ContextCompat.getColor(context, R.color.outline_variant) // neutral #DCE0EC
    private val offTrackColorAlt = Color.parseColor("#E9EDF3") // border_light for subtle

    private val density = resources.displayMetrics.density
    private val trackRadius: Float get() = height / 2f

    init {
        isClickable = true
        isFocusable = true
        // Default size will be set via onMeasure if not specified
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Compact iOS size: 44dp x 26dp (pill), thumb 22dp
        val desiredW = (44 * density).toInt()
        val desiredH = (26 * density).toInt()
        val w = resolveSize(desiredW, widthMeasureSpec)
        val h = resolveSize(desiredH, heightMeasureSpec)
        // Keep aspect pill: height is the limiting factor
        val finalH = h.coerceAtMost((w * 0.6f).toInt())
        setMeasuredDimension(w, finalH)
    }

    private fun animateThumb(to: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(thumbPosition, to).apply {
            duration = 220
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                thumbPosition = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = h / 2f
        trackRect.set(0f, 0f, w, h)
        // Track color with smooth blend
        val trackColor = blendColor(offTrackColor, accentColor, thumbPosition)
        // For subtle off state, use a very light grey with border - but we blend to accent
        trackPaint.color = trackColor
        trackPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

        // Thumb: circular, 22dp (with 2dp inset from track)
        val thumbSize = h - 4 * density // 2dp padding top/bottom
        val thumbRadius = thumbSize / 2f
        val startX = 2 * density + thumbRadius
        val endX = w - 2 * density - thumbRadius
        val cx = startX + (endX - startX) * thumbPosition
        val cy = h / 2f
        // Thumb with subtle contrast - white with shadow (already set)
        canvas.drawCircle(cx, cy, thumbRadius, thumbPaint)
    }

    private fun blendColor(from: Int, to: Int, ratio: Float): Int {
        val r = Color.red(from) + ((Color.red(to) - Color.red(from)) * ratio).toInt()
        val g = Color.green(from) + ((Color.green(to) - Color.green(from)) * ratio).toInt()
        val b = Color.blue(from) + ((Color.blue(to) - Color.blue(from)) * ratio).toInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!isDragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                    isDragging = true
                }
                if (isDragging) {
                    // Allow dragging to change state without lifting
                    val progress = ((event.x - (height / 2f)) / (width - height)).coerceIn(0f, 1f)
                    // Update thumb position live for drag
                    thumbPosition = progress
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    // Decide based on final position
                    val shouldBeOn = thumbPosition > 0.5f
                    isChecked = shouldBeOn
                    isDragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                } else {
                    // Tap - toggle
                    val dx = Math.abs(event.x - downX)
                    val dy = Math.abs(event.y - downY)
                    if (dx < touchSlop && dy < touchSlop) {
                        performClick()
                        isDragging = false
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
        isChecked = !isChecked
        return true
    }

    fun setOnCheckedChangeListener(listener: (IosSwitch, Boolean) -> Unit) {
        checkedChangeListener = listener
    }

    // For accessibility and state saving
    override fun onSaveInstanceState(): android.os.Parcelable {
        val superState = super.onSaveInstanceState()
        val ss = SavedState(superState)
        ss.checked = isChecked
        return ss
    }

    override fun onRestoreInstanceState(state: android.os.Parcelable?) {
        if (state is SavedState) {
            super.onRestoreInstanceState(state.superState)
            isChecked = state.checked
            thumbPosition = if (isChecked) 1f else 0f
        } else {
            super.onRestoreInstanceState(state)
        }
    }

    private class SavedState : BaseSavedState {
        var checked: Boolean = false
        constructor(superState: android.os.Parcelable?) : super(superState)
        constructor(parcel: android.os.Parcel) : super(parcel) {
            checked = parcel.readInt() == 1
        }
        override fun writeToParcel(out: android.os.Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeInt(if (checked) 1 else 0)
        }
        companion object {
            @JvmField
            val CREATOR = object : android.os.Parcelable.Creator<SavedState> {
                override fun createFromParcel(source: android.os.Parcel): SavedState = SavedState(source)
                override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
            }
        }
    }
}
