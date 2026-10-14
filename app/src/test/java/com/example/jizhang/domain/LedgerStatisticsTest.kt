package com.example.jizhang.domain

import org.junit.Assert.*
import org.junit.Test

class LedgerStatisticsTest {
    private val purchase = LedgerEntry(id = 1, amountCents = 10_000, type = EntryType.EXPENSE,
        category = "购物", occurredAt = 100)
    private fun refund(id: Long, cents: Long, related: Long? = 1, time: Long = id) = LedgerEntry(
        id = id, amountCents = cents, type = EntryType.INCOME, category = "退款", businessType = BusinessType.REFUND,
        relatedEntryId = related, occurredAt = time)

    @Test fun defaultLegacyTypesKeepOriginalExpenseIncomeTotals() {
        val income = LedgerEntry(id = 2, amountCents = 20_000, type = EntryType.INCOME, category = "工资")
        val totals = LedgerStatistics.calculate(listOf(purchase, income))
        assertEquals(10_000L, totals.expenseCents)
        assertEquals(20_000L, totals.incomeCents)
        assertEquals(10_000L, totals.balanceCents)
    }

    @Test fun transfersRepaymentsUnknownAndReviewRowsAreExcluded() {
        val entries = listOf(purchase,
            purchase.copy(id = 2, businessType = BusinessType.TRANSFER),
            purchase.copy(id = 3, businessType = BusinessType.REPAYMENT),
            purchase.copy(id = 4, businessType = BusinessType.UNKNOWN),
            purchase.copy(id = 5, needsReview = true))
        assertEquals(10_000L, LedgerStatistics.calculate(entries).expenseCents)
    }

    @Test fun multiplePartialRefundsReduceExpenseAndItsOriginalCategory() {
        val entries = listOf(purchase, refund(2, 2_000), refund(3, 3_000))
        val totals = LedgerStatistics.calculate(entries)
        assertEquals(5_000L, totals.expenseCents)
        assertEquals(5_000L, totals.refundCents)
        assertEquals(0L, totals.incomeCents)
        assertEquals(mapOf("购物" to 5_000L), LedgerStatistics.expenseByCategory(entries))
    }

    @Test fun unrelatedUnconfirmedAndIncomeLinkedRefundsDoNotCreditExpenses() {
        val income = LedgerEntry(id = 9, amountCents = 8_000, type = EntryType.INCOME, category = "工资")
        val entries = listOf(purchase, income, refund(2, 2_000, null), refund(3, 3_000, 9),
            refund(4, 1_000).copy(needsReview = true))
        assertEquals(10_000L, LedgerStatistics.calculate(entries).expenseCents)
        assertEquals(0L, LedgerStatistics.calculate(entries).refundCents)
    }

    @Test fun overRefundCannotCreditMoreThanTheOriginalExpense() {
        val totals = LedgerStatistics.calculate(listOf(purchase, refund(2, 7_000), refund(3, 7_000)))
        assertEquals(0L, totals.expenseCents)
        assertEquals(10_000L, totals.refundCents)
        assertEquals(0L, totals.incomeCents)
    }

    @Test fun refundInLaterReportingPeriodCreditsThatPeriodUsingGlobalOriginal() {
        val oldRefund = refund(2, 8_000, time = 200)
        val thisMonthRefund = refund(3, 4_000, time = 300)
        val all = listOf(purchase, oldRefund, thisMonthRefund)
        val totals = LedgerStatistics.calculate(listOf(thisMonthRefund), all)
        assertEquals(-2_000L, totals.expenseCents)
        assertEquals(2_000L, totals.refundCents)
        assertEquals(mapOf("购物" to -2_000L), LedgerStatistics.expenseByCategory(listOf(thisMonthRefund), all))
    }
}
