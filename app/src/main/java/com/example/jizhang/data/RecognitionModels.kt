package com.example.jizhang.data

import com.example.jizhang.domain.EntrySource
import com.example.jizhang.domain.EntryType
import com.example.jizhang.recognition.RecognitionDecision
import com.example.jizhang.recognition.TransactionCandidate

/** One observed event may point to a ledger entry shared with other evidence. */
data class RecognitionEvent(
    val id: Long,
    val candidate: TransactionCandidate,
    val decision: RecognitionDecision,
    val reasons: List<String>,
    val linkedEntryId: Long? = null,
    val suggestedEntryId: Long? = null,
    val relatedEntryId: Long? = null,
    val association: String = "NONE",
    val linkedEntryDeleted: Boolean = false,
    val orderHash: String? = null,
    val originalOrderHash: String? = null,
    val userIgnored: Boolean = false,
)

data class RecognitionSaveResult(
    val decision: RecognitionDecision,
    val eventId: Long? = null,
    val entryId: Long? = null,
    val duplicate: Boolean = false,
    val disabled: Boolean = false,
    val reasons: List<String> = emptyList(),
)

data class MerchantCategoryRule(
    val source: EntrySource,
    val merchantKey: String,
    val type: EntryType,
    val category: String,
    val updatedAt: Long,
)

data class RecognitionDiagnostic(
    val channel: String,
    val lastSuccessAt: Long = 0,
    val lastFailureAt: Long = 0,
    val lastFailureReason: String = "",
    val lastDurationMs: Long = 0,
)
