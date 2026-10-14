package com.example.jizhang.recognition

import com.example.jizhang.domain.BusinessType
import com.example.jizhang.domain.EntrySource
import com.example.jizhang.domain.EntryType
import com.example.jizhang.domain.LedgerEntry
import org.junit.Assert.*
import org.junit.Test

class TransactionMatcherTest {
    private val order = "a".repeat(64)
    private val otherOrder = "b".repeat(64)
    private val now = 1_791_868_830_000L
    private val candidate = TransactionCandidate(EntrySource.WECHAT, "c".repeat(64), amountCents = 2_800,
        direction = FundsDirection.OUTGOING, businessType = BusinessType.EXPENSE, merchant = "星巴克",
        occurredAt = now, observedAt = now, categorySuggestion = "餐饮", completion = CompletionState.COMPLETED,
        evidence = EvidenceType.NOTIFICATION, orderId = "PAY202610130001", orderNamespace = "provider_transaction",
        ruleVersion = "wechat-notification-synthetic-test")
    private val entry = LedgerEntry(id = 7, amountCents = 2_800, type = EntryType.EXPENSE, category = "餐饮",
        merchant = "星巴克", occurredAt = now, source = EntrySource.WECHAT)
    private val event = MatcherEventSnapshot(candidate.copy(evidence = EvidenceType.ACCESSIBILITY), order, 7)

    private fun match(new: TransactionCandidate = candidate, hash: String? = order,
        events: List<MatcherEventSnapshot> = listOf(event),
        entries: List<MatcherEntrySnapshot> = listOf(MatcherEntrySnapshot(entry))) =
        TransactionMatcher.match(new, hash, events, entries)

    @Test fun sameTrustedProviderOrderAssociatesWithCanonicalEntry() {
        val result = match()
        assertEquals(7L, result.strongEntryId)
        assertFalse(result.identityConflict)
        assertTrue(result.suggestedEntryIds.isEmpty())
    }

    @Test fun storedRawOrderIdCanBeAbsentBecauseHashAndNamespaceCarryIdentity() {
        val result = match(events = listOf(event.copy(candidate = event.candidate.copy(orderId = null))))
        assertEquals(7L, result.strongEntryId)
    }

    @Test fun differentKnownOrdersStaySeparateEvenAtSamePriceMerchantAndMillisecond() {
        val result = match(hash = otherOrder)
        assertNull(result.strongEntryId)
        assertTrue(result.suggestedEntryIds.isEmpty())
        assertFalse(result.identityConflict)
    }

    @Test fun missingNamespaceOrDifferentProviderCannotMakeStrongAssociation() {
        assertNull(match(candidate.copy(orderNamespace = null)).strongEntryId)
        assertNull(match(candidate.copy(provider = EntrySource.ALIPAY)).strongEntryId)
        assertNull(match(candidate.copy(orderNamespace = "merchant_order")).strongEntryId)
    }

    @Test fun timeAmountAndMerchantAreOnlySuggestionsWithoutOwnOrderProof() {
        val result = match(candidate.copy(orderId = null, orderNamespace = null), hash = null)
        assertNull(result.strongEntryId)
        assertEquals(listOf(7L), result.suggestedEntryIds)
        assertFalse(result.identityConflict)
        assertNull(match(candidate.copy(orderId = null, occurredAt = now + 121_000), hash = null).strongEntryId)
        assertTrue(match(candidate.copy(orderId = null, occurredAt = now + 121_000), hash = null).suggestedEntryIds.isEmpty())
        assertTrue(match(candidate.copy(orderId = null, merchant = "麦当劳"), hash = null).suggestedEntryIds.isEmpty())
    }

    @Test fun imageEvidenceOnEitherSideCanOnlySuggestAnAssociation() {
        listOf(EvidenceType.OCR, EvidenceType.IMPORTED_IMAGE, EvidenceType.SHARED_IMAGE).forEach { evidence ->
            val result = match(candidate.copy(evidence = evidence))
            assertNull(result.strongEntryId)
            assertEquals(listOf(7L), result.suggestedEntryIds)
            val oldImage = match(events = listOf(event.copy(candidate = event.candidate.copy(evidence = evidence))))
            assertNull(oldImage.strongEntryId)
            assertEquals(listOf(7L), oldImage.suggestedEntryIds)
        }
    }

    @Test fun pendingIncompleteOrWeakEventsCannotAssertStrongCompletion() {
        listOf(CompletionState.UNCONFIRMED, CompletionState.PENDING, CompletionState.FAILED).forEach { state ->
            assertNull(match(candidate.copy(completion = state)).strongEntryId)
            assertNull(match(events = listOf(event.copy(candidate = event.candidate.copy(completion = state)))).strongEntryId)
        }
        assertNull(match(candidate.copy(hasStableIdentity = false)).strongEntryId)
    }

    @Test fun amountDirectionAndKnownBusinessDisagreementsAreIdentityConflicts() {
        listOf(candidate.copy(amountCents = 2_900), candidate.copy(direction = FundsDirection.INCOMING),
            candidate.copy(businessType = BusinessType.TRANSFER)).forEach { changed ->
            val result = match(changed)
            assertNull(result.strongEntryId)
            assertTrue(result.identityConflict)
        }
    }

    @Test fun editedCanonicalAmountCannotBeOverwrittenOrBypassedAsANewExpense() {
        val edited = entry.copy(amountCents = 2_500, editedByUser = true)
        val result = match(entries = listOf(MatcherEntrySnapshot(edited)))
        assertNull(result.strongEntryId)
        assertTrue(result.identityConflict)
        assertEquals(listOf(7L), result.suggestedEntryIds)
        // Matching the edited amount still conflicts with the original source facts.
        assertTrue(match(candidate.copy(amountCents = 2_500), entries = listOf(MatcherEntrySnapshot(edited))).identityConflict)
    }

    @Test fun merchantAndCategoryCorrectionsArePreservedWithoutBlockingExactFinancialIdentity() {
        val edited = entry.copy(category = "生活", merchant = "早餐", editedByUser = true)
        assertEquals(7L, match(entries = listOf(MatcherEntrySnapshot(edited))).strongEntryId)
        assertEquals("生活", edited.category)
    }

    @Test fun deletedStableOrderIsNotRecreatedEvenAfterItsAmountWasEdited() {
        val result = match(entries = listOf(MatcherEntrySnapshot(entry.copy(amountCents = 2_500,
            editedByUser = true), deleted = true)))
        assertTrue(result.deletedOrderSuppressed)
        assertNull(result.strongEntryId)
        assertTrue(result.suggestedEntryIds.isEmpty())
        val image = match(candidate.copy(evidence = EvidenceType.OCR), entries = listOf(MatcherEntrySnapshot(entry, true)))
        assertFalse(image.deletedOrderSuppressed)
        assertNull(image.strongEntryId)
        assertTrue(image.reasons.isNotEmpty())
    }

    @Test fun manualIgnoreCannotBeRevivedByAnotherTrustedChannel() {
        val result = match(events = listOf(event.copy(userIgnored = true)))
        assertTrue(result.userIgnoredOrderSuppressed)
        assertNull(result.strongEntryId)
        val image = match(candidate.copy(evidence = EvidenceType.IMPORTED_IMAGE), events = listOf(event.copy(userIgnored = true)))
        assertFalse(image.userIgnoredOrderSuppressed)
        assertNull(image.strongEntryId)
    }

    @Test fun refundOwnOrderNeverAssociatesWithAnOriginalExpense() {
        val refund = candidate.copy(businessType = BusinessType.REFUND, direction = FundsDirection.INCOMING,
            orderNamespace = "provider_refund", originalOrderId = candidate.orderId,
            originalOrderNamespace = "provider_transaction")
        assertNull(match(refund, hash = otherOrder).strongEntryId)
        assertTrue(match(refund, hash = otherOrder).suggestedEntryIds.isEmpty())
        // Even erroneous reuse of the original order namespace does not combine meanings.
        val conflicting = match(refund.copy(orderNamespace = "provider_transaction"))
        assertNull(conflicting.strongEntryId)
        assertTrue(conflicting.identityConflict)
        assertFalse(match(refund.copy(orderNamespace = "provider_transaction"),
            entries = listOf(MatcherEntrySnapshot(entry, true))).deletedOrderSuppressed)
    }

    @Test fun missingCanonicalOrMultipleCanonicalRowsNeedReconciliation() {
        assertTrue(match(entries = emptyList()).identityConflict)
        val multiple = match(events = listOf(event, event.copy(linkedEntryId = 8)),
            entries = listOf(MatcherEntrySnapshot(entry), MatcherEntrySnapshot(entry.copy(id = 8))))
        assertTrue(multiple.identityConflict)
        assertNull(multiple.strongEntryId)
        assertEquals(listOf(7L, 8L), multiple.suggestedEntryIds)
    }
}
