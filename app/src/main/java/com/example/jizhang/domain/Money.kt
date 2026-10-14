package com.example.jizhang.domain

import java.math.BigDecimal

object Money {
    const val MAX_CENTS: Long = 99_999_999_999L
    private val decimal = Regex("(?:[0-9]+(?:\\.[0-9]{1,2})?|\\.[0-9]{1,2})")

    /** Only positive plain decimal amounts, with at most two fractional digits. */
    fun parseCents(input: String): Long? {
        val value = input.trim()
        if (value.length > 32 || !decimal.matches(value)) return null
        return try {
            BigDecimal(value).movePointRight(2).longValueExact()
                .takeIf { it in 1..MAX_CENTS }
        } catch (_: NumberFormatException) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }

    fun format(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()
}
