package com.example.jizhang.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test
    fun decimalInputIsConvertedWithoutFloatingPointRounding() {
        assertEquals(29L, Money.parseCents("0.29"))
        assertEquals(1L, Money.parseCents("0.01"))
        assertEquals(1200L, Money.parseCents("12"))
        assertEquals(1250L, Money.parseCents(" 12.5 "))
        assertEquals(50L, Money.parseCents(".50"))
    }

    @Test
    fun invalidOrOutOfRangeInputIsRejectedInsteadOfRounded() {
        listOf(
            "", " ", "0", "0.00", "-1", "+1", "12.345", "1e2", "NaN",
            "1,000.00", "￥1.00", "1.", "999999999.991", "1000000000.00",
            "999999999999999999999999999999999999999999999",
        ).forEach { assertNull("Should reject <$it>", Money.parseCents(it)) }
    }

    @Test
    fun maximumAllowedAmountIsExact() {
        assertEquals(Money.MAX_CENTS, Money.parseCents("999999999.99"))
    }

    @Test
    fun formattingAlwaysShowsTwoDecimalPlaces() {
        assertEquals("0.00", Money.format(0))
        assertEquals("0.01", Money.format(1))
        assertEquals("12.50", Money.format(1250))
        assertEquals("999999999.99", Money.format(Money.MAX_CENTS))
        assertEquals("-0.01", Money.format(-1))
        assertEquals("-92233720368547758.08", Money.format(Long.MIN_VALUE))
    }
}
