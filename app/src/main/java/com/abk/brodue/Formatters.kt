package com.abk.brodue

import android.text.SpannableString
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Formatters {

    @Volatile
    private var overrideSymbol: String? = null

    fun setCurrencySymbol(symbol: String) {
        overrideSymbol = symbol
    }

    fun getCurrencySymbol(): String = overrideSymbol ?: "₹"

    // Fraction styling: smaller size, 75% opacity (font weight untouched).
    private class FractionStyleSpan : MetricAffectingSpan() {
        override fun updateDrawState(ds: TextPaint) {
            ds.textSize = ds.textSize * 0.75f
            ds.alpha = (ds.alpha * 0.85f).toInt()
        }

        override fun updateMeasureState(ds: TextPaint) {
            ds.textSize = ds.textSize * 0.75f
        }
    }

    // Pure string rendering (unit-testable, no Android classes).
    fun renderAmountString(paise: Long, symbol: String): String {
        val sign = if (paise < 0) "-" else ""
        val abs = Math.abs(paise)
        val nf = NumberFormat.getNumberInstance(Locale.getDefault())
        return if (abs % 100 == 0L) {
            sign + symbol + nf.format(abs / 100)
        } else {
            nf.minimumFractionDigits = 2
            nf.maximumFractionDigits = 2
            sign + symbol + nf.format(abs / 100.0)
        }
    }

    private fun render(paise: Long, symbol: String): SpannableString {
        val str = renderAmountString(paise, symbol)
        val abs = Math.abs(paise)
        val ss = SpannableString(str)
        // Style the decimal separator + 2 fraction digits (always the tail here)
        if (abs % 100 != 0L && str.length >= 3) {
            ss.setSpan(
                FractionStyleSpan(),
                str.length - 3, str.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return ss
    }

    // Value is minor units (paise): 500 -> "₹500", 9950 -> "₹99.50"
    // (fraction rendered smaller/lighter/at 75% opacity).
    fun amount(paise: Long): SpannableString =
        render(paise, overrideSymbol ?: "₹")

    fun amountWithCurrency(paise: Long, context: android.content.Context): SpannableString =
        render(paise, CurrencyManager.getSymbol(context))

    fun amountWithSymbol(paise: Long, symbol: String): SpannableString =
        render(paise, symbol.ifBlank { overrideSymbol ?: "₹" })

    fun date(millis: Long): String {
        if (millis <= 0) return ""
        return SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(millis))
    }

    fun time(millis: Long): String {
        if (millis <= 0) return ""
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))
    }

    fun spanFrom(millis: Long): String {
        if (millis <= 0) return "Today"
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = millis
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        val from = c.timeInMillis
        val now = java.util.Calendar.getInstance()
        now.set(java.util.Calendar.HOUR_OF_DAY, 0)
        now.set(java.util.Calendar.MINUTE, 0)
        now.set(java.util.Calendar.SECOND, 0)
        now.set(java.util.Calendar.MILLISECOND, 0)
        var days = ((now.timeInMillis - from) / 86_400_000L).toInt()
        if (days < 0) days = 0
        if (days == 0) return "Today"

        val years = days / 365
        days %= 365
        val months = days / 30
        days %= 30
        val parts = mutableListOf<String>()
        if (years > 0) parts.add("$years ${if (years == 1) "year" else "years"}")
        if (months > 0) parts.add("$months ${if (months == 1) "month" else "months"}")
        if (days > 0) parts.add("$days ${if (days == 1) "day" else "days"}")
        return if (parts.isEmpty()) "Today" else parts.joinToString(" ")
    }
}