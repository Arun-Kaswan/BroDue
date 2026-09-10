package com.abk.brodue

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

class FlowLayout(context: Context, attrs: AttributeSet?) : ViewGroup(context, attrs) {

    private val gapPx = (10 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        var x = paddingLeft
        var y = paddingTop
        var rowH = 0
        var totalH = paddingTop
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            if (x + cw > width - paddingRight) {
                x = paddingLeft
                y += rowH + gapPx
                rowH = 0
            }
            x += cw + gapPx
            rowH = max(rowH, ch)
            totalH = y + rowH
        }
        setMeasuredDimension(width, totalH + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        var x = paddingLeft
        var y = paddingTop
        var rowH = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            if (x + cw > width - paddingRight) {
                x = paddingLeft
                y += rowH + gapPx
                rowH = 0
            }
            child.layout(x, y, x + cw, y + ch)
            x += cw + gapPx
            rowH = max(rowH, ch)
        }
    }
}