package com.abk.brodue

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isVisible

class SwipeCloseLayout(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {

    var onSwipeProgress: ((Float) -> Unit)? = null
    var onSwipeEnd: ((Boolean) -> Unit)? = null
    var swipeEnabled: Boolean = true

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var isOnToggle = false

    private fun isToggleAt(x: Float, y: Float): Boolean {
        // Check if touch is on a Switch (or its thumb/track) — universal: any SwitchMaterial/CompoundButton
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val localX = (x - loc[0]).toInt()
        val localY = (y - loc[1]).toInt()
        // Hit-test all switches inside this layout
        return findToggleAt(this, localX, localY)
    }

    private fun findToggleAt(view: android.view.View, x: Int, y: Int): Boolean {
        if (!view.isVisible) return false
        if (view is android.widget.CompoundButton || view is IosSwitch) {
            val vLoc = IntArray(2)
            view.getLocationOnScreen(vLoc)
            val vRect = android.graphics.Rect(vLoc[0], vLoc[1], vLoc[0] + view.width, vLoc[1] + view.height)
            vRect.inset(-12, -12)
            if (vRect.contains(x.toInt(), y.toInt())) return true
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (findToggleAt(child, x, y)) return true
            }
        }
        return false
    }

    private fun isTouchOnToggle(ev: MotionEvent): Boolean {
        // Use rawX/rawY to check against screen location of any Switch inside
        val switches = ArrayList<android.view.View>()
        collectSwitches(this, switches)
        for (sw in switches) {
            val loc = IntArray(2)
            sw.getLocationOnScreen(loc)
            val rect = android.graphics.Rect(loc[0], loc[1], loc[0] + sw.width, loc[1] + sw.height)
            // Expand touch area slightly for thumb
            rect.inset(-12, -12)
            if (rect.contains(ev.rawX.toInt(), ev.rawY.toInt())) return true
        }
        return false
    }

    private fun collectSwitches(v: View, out: MutableList<android.view.View>) {
        if (v is android.widget.CompoundButton || v is IosSwitch) out.add(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) collectSwitches(v.getChildAt(i), out)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!swipeEnabled) return super.onInterceptTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                tracking = false
                isOnToggle = isTouchOnToggle(ev)
                if (isOnToggle) return super.onInterceptTouchEvent(ev)
                // Ensure we receive the move events even from empty background
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (isOnToggle) return super.onInterceptTouchEvent(ev)
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                // Horizontal swipe takes precedence over vertical scroll, even from empty area
                if (!tracking && dx > touchSlop && Math.abs(dy) < dx * 0.8f) {
                    tracking = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    onSwipeProgress?.invoke(0f)
                    return true
                }
                if (tracking) return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isOnToggle = false
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!swipeEnabled) return super.onTouchEvent(ev)
        if (isOnToggle) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                tracking = false
                isOnToggle = isTouchOnToggle(ev)
                if (isOnToggle) return super.onTouchEvent(ev)
                // Must return true to receive subsequent MOVE/UP even from empty background
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isOnToggle) return super.onTouchEvent(ev)
                if (!tracking) {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!(dx > touchSlop && Math.abs(dy) < dx * 0.8f)) {
                        // Not a horizontal swipe, let children handle (e.g., vertical scroll)
                        if (Math.abs(dy) > touchSlop) return false
                        return true
                    }
                    tracking = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    onSwipeProgress?.invoke(0f)
                }
                onSwipeProgress?.invoke(((ev.rawX - downX) / width).coerceIn(0f, 1f))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isOnToggle) {
                    isOnToggle = false
                    return super.onTouchEvent(ev)
                }
                if (!tracking) {
                    // Tap on empty background should still be handled (e.g., close on tap outside?)
                    return true
                }
                val progress = ((ev.rawX - downX) / width).coerceIn(0f, 1f)
                tracking = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onSwipeEnd?.invoke(progress > 0.25f)
                return true
            }
        }
        return true
    }
}