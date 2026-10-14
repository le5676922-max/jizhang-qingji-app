package com.example.jizhang.recognition

import com.example.jizhang.domain.BusinessType
import com.example.jizhang.domain.EntrySource
import com.example.jizhang.domain.Money
import org.junit.Assert.*
import org.junit.Test

class DecisionEngineTest {
    private val base = TransactionCandidate(EntrySource.WECHAT, "a".repeat(64), amountCents = 1_000,
        direction = FundsDirection.OUTGOING, businessType = BusinessType.EXPENSE, merchant = "星巴克",
        occurredAt = 1_791_868_830_000, categorySuggestion = "餐饮", completion = CompletionState.COMPLETED,
        evidence = EvidenceType.NOTIFICATION, ruleVersion = "synthetic-test", observedAt = 1_791_868_830_000)

    @Test fun knownCompletedMerchantPaymentAndSalaryCanBeAutomatic() {
        assertEquals(RecognitionDecision.AUTO, DecisionEngine.evaluate(base).decision)
        assertEquals(RecognitionDecision.AUTO, DecisionEngine.evaluate(base.copy(direction = FundsDirection.INCOMING,
            businessType = BusinessType.INCOME)).decision)
    }

    @Test fun failedPendingAndCancelledAreIgnoredRegardlessOfOtherFacts() {
        listOf(CompletionState.FAILED, CompletionState.PENDING, CompletionState.CANCELLED).forEach {
            assertEquals(it.name, RecognitionDecision.IGNORE, DecisionEngine.evaluate(base.copy(completion = it)).decision)
        }
    }

    @Test fun unconfirmedCompletionAndUnknownDirectionRequireReview() {
        assertEquals(RecognitionDecision.REVIEW, DecisionEngine.evaluate(base.copy(completion = CompletionState.UNCONFIRMED)).decision)
        assertEquals(RecognitionDecision.REVIEW, DecisionEngine.evaluate(base.copy(direction = FundsDirection.UNKNOWN)).decision)
    }

    @Test fun transferRepaymentAndUnknownEconomicMeaningNeverBecomeAutomatic() {
        listOf(BusinessType.TRANSFER, BusinessType.REPAYMENT, BusinessType.UNKNOWN).forEach {
            assertEquals(it.name, RecognitionDecision.REVIEW, DecisionEngine.evaluate(base.copy(businessType = it)).decision)
        }
        assertEquals(RecognitionDecision.REVIEW, DecisionEngine.evaluate(base.copy(businessType = BusinessType.TRANSFER,
            direction = FundsDirection.INTERNAL)).decision)
    }

    @Test fun refundsRequireLinkedOriginalConsumptionAndIncomingDirection() {
        val refund = base.copy(businessType = BusinessType.REFUND, direction = FundsDirection.INCOMING)
        assertEquals(RecognitionDecision.REVIEW, DecisionEngine.evaluate(refund).decision)
        assertEquals(RecognitionDecision.AUTO, DecisionEngine.evaluate(refund, refundLinked = true).decision)
        assertEquals(RecognitionDecision.REVIEW, DecisionEngine.evaluate(base.copy(businessType = BusinessType.REFUND), refundLinked = true).decision)
    }

    @Test fun allImageSourcesAndLegacyEvidenceRequireReviewEvenWhenUserInitiated() {
        listOf(EvidenceType.OCR, EvidenceType.IMPORTED_IMAGE, EvidenceType.SHARED_IMAGE, EvidenceType.LEGACY).forEach {
            assertEquals(it.name, RecognitionDecision.REVIEW, DecisionEngine.evaluate(base.copy(evidence = it,
                userInitiated = true)).decision)
        }
    }

    @Test fun duplicateConflictAndMultipleAmountOptionsRequireReview() {
        assertEquals(RecognitionDecision.REVIEW, DecisionEngine.evaluate(base, duplicateConflict = true).decision)
        assertEquals(RecognitionDecision.REVIEW, DecisionEngine.evaluate(base.copy(amountOptions = listOf(1_000, 2_000))).decision)
        assertEquals(RecognitionDecision.REVIEW, DecisionEngine.evaluate(base.copy(amountOptions = listOf(2_000))).decision)
        assertEquals(RecognitionDecision.AUTO, DecisionEngine.evaluate(base.copy(amountOptions = listOf(1_000, 1_000))).decision)
    }

    @Test fun invalidAmountsAreIgnoredAndDirectionConflictsAreReviewed() {
        listOf(0L, -1L, Money.MAX_CENTS + 1).forEach {
            assertEquals(RecognitionDecision.IGNORE, DecisionEngine.evaluate(base.copy(amountCents = it)).decision)
        }
        assertEquals(RecognitionDecision.REVIEW, DecisionEngine.evaluate(base.copy(direction = FundsDirection.INCOMING)).decision)
    }

    @Test fun staleFutureAndWeakIdentityRecordsRequireReview() {
        assertEquals(RecognitionDecision.REVIEW, DecisionEngine.evaluate(base.copy(observedAt = base.occurredAt + 16 * 60_000)).decision)
        assertEquals(RecognitionDecision.REVIEW, DecisionEngine.evaluate(base.copy(occurredAt = base.observedAt + 6 * 60_000)).decision)
        assertEquals(RecognitionDecision.REVIEW, DecisionEngine.evaluate(base.copy(hasStableIdentity = false)).decision)
    }

    @Test fun explanationsNeverCopyUntrustedRawScreenText() {
        val result = DecisionEngine.evaluate(base.copy(reasons = listOf("验证码123456 手机13800138000")))
        assertEquals(RecognitionDecision.REVIEW, result.decision)
        assertFalse(result.reasons.joinToString().contains("123456"))
        assertFalse(result.reasons.joinToString().contains("13800138000"))
    }
}
