package com.example.jizhang.domain

data class LedgerTotals(
    val incomeCents: Long,
    val expenseCents: Long,
    val grossExpenseCents: Long,
    val refundCents: Long,
    val balanceCents: Long,
) {
    val netExpenseCents: Long get() = expenseCents
}

/** Refunds reduce expense in the refund's reporting period; they never increase earned income. */
object LedgerStatistics {
    fun calculate(entries: List<LedgerEntry>, allEntries: List<LedgerEntry> = entries): LedgerTotals {
        val confirmed = entries.filterNot { it.needsReview }
        val gross = confirmed.filter { it.businessType == BusinessType.EXPENSE && it.type == EntryType.EXPENSE }
            .sumOf { it.amountCents }
        val income = confirmed.filter { it.businessType == BusinessType.INCOME && it.type == EntryType.INCOME }
            .sumOf { it.amountCents }
        val credits = refundCredits(allEntries)
        val refund = confirmed.sumOf { credits[it] ?: 0L }
        return LedgerTotals(income, gross - refund, gross, refund, income - (gross - refund))
    }

    fun expenseByCategory(entries: List<LedgerEntry>, allEntries: List<LedgerEntry> = entries): Map<String, Long> {
        val result = linkedMapOf<String, Long>()
        val originals = allEntries.filter { !it.needsReview && it.businessType == BusinessType.EXPENSE &&
            it.type == EntryType.EXPENSE && it.id > 0 }.associateBy { it.id }
        val credits = refundCredits(allEntries)
        entries.filterNot { it.needsReview }.forEach { entry ->
            if (entry.businessType == BusinessType.EXPENSE && entry.type == EntryType.EXPENSE) {
                result[entry.category] = (result[entry.category] ?: 0) + entry.amountCents
            }
            val refund = credits[entry] ?: 0
            if (refund > 0) originals[entry.relatedEntryId]?.let { original ->
                result[original.category] = (result[original.category] ?: 0) - refund
            }
        }
        return result
    }

    private fun refundCredits(allEntries: List<LedgerEntry>): Map<LedgerEntry, Long> {
        val confirmed = allEntries.filterNot { it.needsReview }
        val originals = confirmed.filter { it.businessType == BusinessType.EXPENSE &&
            it.type == EntryType.EXPENSE && it.id > 0 }.associateBy { it.id }
        val remaining = originals.mapValues { it.value.amountCents }.toMutableMap()
        val credits = mutableMapOf<LedgerEntry, Long>()
        confirmed.filter { it.businessType == BusinessType.REFUND && it.type == EntryType.INCOME }
            .sortedWith(compareBy<LedgerEntry> { it.occurredAt }.thenBy { it.id }).forEach { refund ->
                val original = originals[refund.relatedEntryId] ?: return@forEach
                val credit = refund.amountCents.coerceAtLeast(0).coerceAtMost(remaining[original.id] ?: 0)
                remaining[original.id] = (remaining[original.id] ?: 0) - credit
                credits[refund] = credit
            }
        return credits
    }
}
