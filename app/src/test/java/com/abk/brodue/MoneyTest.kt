package com.abk.brodue

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class MoneyTest {

    @Test
    fun parseToPaise_basics() {
        assertEquals(9950L, Money.parseToPaise("99.50"))
        assertEquals(9950L, Money.parseToPaise("99.5"))
        assertEquals(50000L, Money.parseToPaise("500"))
        assertEquals(100000L, Money.parseToPaise("1,000"))
        assertEquals(100050L, Money.parseToPaise("1,000.50"))
        assertEquals(5L, Money.parseToPaise("0.05"))
        assertEquals(10000L, Money.parseToPaise("99.999"))
        assertEquals(0L, Money.parseToPaise(""))
        assertEquals(0L, Money.parseToPaise("abc"))
        assertEquals(0L, Money.parseToPaise("0"))
        assertEquals(0L, Money.parseToPaise("0.00"))
    }

    @Test
    fun formatPaise_roundTrip() {
        assertEquals("500", Money.formatPaise(50000L))
        assertEquals("99.5", Money.formatPaise(9950L))
        assertEquals("0.05", Money.formatPaise(5L))
        assertEquals("", Money.formatPaise(0L))
    }

    @Test
    fun amount_rendersPaise() {
        Locale.setDefault(Locale.US)
        assertEquals("₹500", Formatters.renderAmountString(50000L, "₹"))
        assertEquals("₹99.50", Formatters.renderAmountString(9950L, "₹"))
        assertEquals("-₹99.50", Formatters.renderAmountString(-9950L, "₹"))
        assertEquals("₹1,234.50", Formatters.renderAmountString(123450L, "₹"))
        assertEquals("₹0", Formatters.renderAmountString(0L, "₹"))
    }
}
