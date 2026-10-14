package com.example.jizhang.domain

enum class EntryType { EXPENSE, INCOME }

enum class EntrySource { MANUAL, WECHAT, ALIPAY }

/** Amounts are stored as integer cents to avoid floating point rounding. */
data class LedgerEntry(
    val id: Long = 0,
    val amountCents: Long,
    val type: EntryType,
    val category: String,
    val merchant: String = "",
    val note: String = "",
    val occurredAt: Long = System.currentTimeMillis(),
    val source: EntrySource = EntrySource.MANUAL,
    val fingerprint: String? = null,
    val needsReview: Boolean = false,
    val businessType: BusinessType = if (type == EntryType.EXPENSE) BusinessType.EXPENSE else BusinessType.INCOME,
    val relatedEntryId: Long? = null,
    val editedByUser: Boolean = false,
)

data class ParsedPayment(
    val amountCents: Long,
    val type: EntryType,
    val category: String,
    val merchant: String,
    val source: EntrySource,
    val needsReview: Boolean = false,
)
