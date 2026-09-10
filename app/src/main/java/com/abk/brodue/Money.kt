package com.abk.brodue

// Money is stored as integer minor units (paise/cents) in Long fields
// everywhere (local JSON, Firestore, models). This helper converts
// between user-typed decimals and stored paise.
object Money {

    // "1,234.50" -> 123450. Half-up rounding, 0 when invalid/negative.
    fun parseToPaise(input: String): Long {
        val clean = input.replace(",", "").replace(" ", "").replace("₹", "").trim()
        if (clean.isEmpty()) return 0L
        val v = clean.toDoubleOrNull() ?: return 0L
        if (!v.isFinite() || v < 0) return 0L
        return try {
            Math.round(v * 100)
        } catch (_: Exception) {
            0L
        }
    }

    // Paise -> editable text ("500", "99.5", "0.05").
    fun formatPaise(paise: Long): String {
        if (paise == 0L) return ""
        return try {
            java.math.BigDecimal(paise).movePointLeft(2).stripTrailingZeros().toPlainString()
        } catch (_: Exception) {
            ""
        }
    }
}
