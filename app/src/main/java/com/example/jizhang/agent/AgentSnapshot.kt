package com.example.jizhang.agent

import com.example.jizhang.data.RecognitionEvent
import com.example.jizhang.domain.BusinessType
import com.example.jizhang.domain.LedgerEntry
import com.example.jizhang.recognition.CompletionState
import com.example.jizhang.recognition.EvidenceType
import com.example.jizhang.recognition.FundsDirection
import com.example.jizhang.recognition.RecognitionDecision

/** Categorize metadata locally; captured reason text and transaction identifiers are never exported. */
internal fun agentSnapshot(
    activeEntries: List<LedgerEntry>,
    events: List<RecognitionEvent>,
    connection: AgentConnectionSnapshot,
): AgentLedgerSnapshot {
    val pending = events.filter { it.decision == RecognitionDecision.REVIEW }
    val counts = linkedMapOf<AgentPendingReason, Int>()
    pending.forEach { event ->
        val candidate = event.candidate
        val reasons = buildSet {
            if (candidate.amountOptions.size > 1 || candidate.amountCents <= 0) add(AgentPendingReason.AMOUNT)
            if (event.suggestedEntryId != null || event.linkedEntryId != null) add(AgentPendingReason.DUPLICATE)
            if (candidate.businessType in setOf(BusinessType.UNKNOWN, BusinessType.TRANSFER, BusinessType.REPAYMENT) ||
                candidate.direction == FundsDirection.UNKNOWN) add(AgentPendingReason.BUSINESS_TYPE)
            if (candidate.completion != CompletionState.COMPLETED) add(AgentPendingReason.COMPLETION)
            if (candidate.businessType == BusinessType.REFUND) add(AgentPendingReason.REFUND_LINK)
            if (candidate.evidence in setOf(EvidenceType.OCR, EvidenceType.IMPORTED_IMAGE, EvidenceType.SHARED_IMAGE)) add(AgentPendingReason.IMAGE_REVIEW)
            if (candidate.occurredAt <= 0) add(AgentPendingReason.TIME)
            if (isEmpty()) add(AgentPendingReason.OTHER)
        }
        reasons.forEach { counts[it] = (counts[it] ?: 0) + 1 }
    }
    return AgentLedgerSnapshot(activeEntries.toList(), pending.size, counts.toMap(), connection)
}
