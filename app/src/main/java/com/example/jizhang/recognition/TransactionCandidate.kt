package com.example.jizhang.recognition

import com.example.jizhang.domain.BusinessType
import com.example.jizhang.domain.EntrySource

enum class FundsDirection { OUTGOING, INCOMING, INTERNAL, UNKNOWN }
enum class CompletionState { COMPLETED, UNCONFIRMED, FAILED, PENDING, CANCELLED }
enum class EvidenceType { NOTIFICATION, ACCESSIBILITY, OCR, IMPORTED_IMAGE, SHARED_IMAGE, LEGACY }
enum class RecognitionDecision { AUTO, REVIEW, IGNORE }

/** Extracted facts only. Raw notifications, screen dumps, passwords, and images are not retained. */
data class TransactionCandidate(
    val provider: EntrySource,
    val eventFingerprint: String,
    val alternateFingerprints: List<String> = emptyList(),
    val amountCents: Long,
    val direction: FundsDirection,
    val businessType: BusinessType,
    val merchant: String,
    val occurredAt: Long,
    val observedAt: Long = System.currentTimeMillis(),
    val categorySuggestion: String,
    val completion: CompletionState,
    val evidence: EvidenceType,
    val orderId: String? = null,
    val originalOrderId: String? = null,
    val hasStableIdentity: Boolean = true,
    val ruleVersion: String,
    val reasons: List<String> = emptyList(),
    val amountOptions: List<Long> = emptyList(),
    val userInitiated: Boolean = false,
    val orderNamespace: String? = null,
    val originalOrderNamespace: String? = null,
    val legacyFingerprints: List<String> = emptyList(),
)

data class DecisionResult(val decision: RecognitionDecision, val reasons: List<String>)
