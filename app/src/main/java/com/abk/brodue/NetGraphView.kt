package com.abk.brodue

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

class NetGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val red = Color.parseColor("#D04444")
    private val green = Color.parseColor("#2E9E6E")
    private val grey = Color.parseColor("#8A93A3")
    private val gridColor = Color.parseColor("#EEF1F6")
    private val zeroColor = Color.parseColor("#D1D5DB")
    private val labelColor = Color.parseColor("#8A93A3")
    var displayMode: String = "net"
    private val density = resources.displayMetrics.density

    private val values = mutableListOf<Long>()

    fun setValues(data: LongArray) {
        values.clear()
        // Downsample huge histories: >300 points are invisible pixels anyway,
        // but each one costs segment objects + path ops every draw.
        if (data.size <= MAX_POINTS) {
            values.addAll(data.toList())
        } else {
            val stride = data.size.toDouble() / MAX_POINTS
            var i = 0
            while (i < MAX_POINTS - 1) {
                values.add(data[(i * stride).toInt()])
                i++
            }
            values.add(data.last()) // current net always exact
        }
        invalidate()
    }

    companion object {
        private const val MAX_POINTS = 300
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gridColor
        strokeWidth = 1f * density
    }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = zeroColor
        strokeWidth = 1.5f * density
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor
        textSize = 10f * density
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textAlign = Paint.Align.RIGHT
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val left = 44f * density
        val right = w - 18f * density
        val top = 20f * density
        val bottom = h - 18f * density

        // Net-balance graph: if CURRENT net is positive, positives go up (green).
        // If current net is negative, mirror the axis so the negatives go up (red).
        val flipNet = displayMode == "net" && values.isNotEmpty() && values.last() < 0
        fun plotVal(i: Int): Long = if (flipNet) -values[i] else values[i]

        var lo = 0L
        var hi = 0L
        for (i in values.indices) {
            val v = plotVal(i)
            if (v < lo) lo = v
            if (v > hi) hi = v
        }

        val yLo: Float
        val yHi: Float
        if (lo >= 0) {
            yLo = 0f
            yHi = if (hi > 0) hi * 1.1f else 1f
        } else if (hi <= 0) {
            yLo = if (lo < 0) lo * 1.1f else -1f
            yHi = 0f
        } else {
            val pad = (hi - lo) * 0.1f
            yLo = lo - pad
            yHi = hi + pad
        }
        val yRange = if (yHi - yLo > 0f) yHi - yLo else 1f

        fun mapY(v: Float): Float = bottom - (v - yLo) / yRange * (bottom - top)
        val zeroY = mapY(0f)

        for (i in 0..4) {
            val t = i / 4f
            val y = bottom - t * (bottom - top)
            canvas.drawLine(left, y, right, y, gridPaint)
            val tickVal = yLo + (yHi - yLo) * t
            canvas.drawText(
                formatAmount(tickVal.toLong()),
                left - 8f * density,
                y + labelPaint.textSize * 0.35f,
                labelPaint
            )
        }

        if (zeroY >= top - 0.5f && zeroY <= bottom + 0.5f) {
            canvas.drawLine(left, zeroY, right, zeroY, zeroPaint)
        }

        val n = values.size
        val stepX = (right - left) / n
        data class Seg(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val color: Int)
        val segs = ArrayList<Seg>(n)
        var prevX = left
        var prevY = zeroY
        var prevVal = 0L
        var prevRaw = 0L
        val isAllZero = values.all { it == 0L }
        val fixedColor = when (displayMode) {
            "neg", "send" -> red
            "pos", "receive" -> green
            else -> null // net uses per-segment green/red
        }
        // side color: positive = green (up), negative = red (down)
        fun sideColor(v: Long): Int = when {
            v > 0 -> green
            v < 0 -> red
            else -> grey
        }
        for (i in 0 until n) {
            val x2 = left + (i + 1) * stepX
            val y2 = mapY(plotVal(i).toFloat())
            val raw = values[i]
            val v2 = plotVal(i)
            if (fixedColor != null) {
                // Fixed color mode (pos/neg/send/receive) - no split, just use fixed
                segs.add(Seg(prevX, prevY, x2, y2, if (isAllZero) grey else fixedColor))
            } else {
                val sameSide = (prevVal >= 0 && v2 >= 0) || (prevVal <= 0 && v2 <= 0)
                if (sameSide) {
                    segs.add(Seg(prevX, prevY, x2, y2, if (isAllZero) grey else sideColor(raw)))
                } else {
                    // Zero crossing - split segment so colors change at the zero line
                    val t = prevVal.toDouble() / (prevVal - v2)
                    val xc = (prevX + (x2 - prevX) * t).toFloat()
                    segs.add(Seg(prevX, prevY, xc, zeroY, if (isAllZero) grey else sideColor(prevRaw)))
                    segs.add(Seg(xc, zeroY, x2, y2, if (isAllZero) grey else sideColor(raw)))
                }
            }
            prevX = x2
            prevY = y2
            prevVal = v2
            prevRaw = raw
        }

        for (s in segs) {
            fillPaint.color = s.color
            fillPaint.alpha = 26
            path.reset()
            path.moveTo(s.x1, zeroY)
            path.lineTo(s.x1, s.y1)
            path.lineTo(s.x2, s.y2)
            path.lineTo(s.x2, zeroY)
            path.close()
            canvas.drawPath(path, fillPaint)
        }
        fillPaint.alpha = 255

        var runColor = segs.first().color
        linePaint.color = runColor
        path.reset()
        path.moveTo(segs.first().x1, segs.first().y1)
        for (s in segs) {
            if (s.color != runColor) {
                canvas.drawPath(path, linePaint)
                path.reset()
                path.moveTo(s.x1, s.y1)
                runColor = s.color
                linePaint.color = runColor
            }
            path.lineTo(s.x2, s.y2)
        }
        canvas.drawPath(path, linePaint)
    }

    private fun formatAmount(value: Long): String {
        val abs = abs(value)
        val symbol = Formatters.getCurrencySymbol()
        return symbol + when {
            abs < 1000 -> abs.toString()
            abs < 100_000 -> trim(abs / 1000.0) + "K"
            abs < 10_000_000 -> trim(abs / 100_000.0) + "L"
            else -> trim(abs / 10_000_000.0) + "Cr"
        }
    }

    private fun trim(d: Double): String {
        val r = Math.round(d * 10) / 10.0
        return if (r == Math.floor(r) && !r.isInfinite()) r.toLong().toString() else r.toString()
    }
}