package com.example.jizhang.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.jizhang.domain.*
import com.example.jizhang.recognition.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Transactional combinations use disposable files and synthetic facts, never payment accounts. */
@RunWith(AndroidJUnit4::class)
class RecognitionCoreInstrumentedTest {
    private lateinit var context: Context
    private lateinit var repository: LedgerRepository
    private lateinit var databaseName: String
    private lateinit var preferencesName: String

    @Before fun setup() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        databaseName = "recognition-core-$suffix.db"
        preferencesName = "recognition-core-$suffix"
        repository = LedgerRepository.isolated(context, databaseName, preferencesName)
        enable()
    }

    @After fun cleanup() = runBlocking {
        repository.closeForTesting()
        context.deleteDatabase(databaseName)
        context.deleteSharedPreferences(preferencesName)
        Unit
    }

    @Test fun sameTrustedOrderAcrossChannelsLinksWithoutChangingCanonical() = runBlocking {
        val source = payment("first")
        val first = repository.ingest(source)
        val canonical = repository.entries.value.single()
        repository.update(canonical.copy(category = "生活", merchant = "用户纠正商户"))
        val corrected = repository.entries.value.single()
        val second = repository.ingest(source.copy(eventFingerprint = hash("notification"), evidence = EvidenceType.NOTIFICATION))
        assertEquals(RecognitionDecision.AUTO, second.decision)
        assertEquals(first.entryId, second.entryId)
        assertTrue(second.duplicate)
        assertEquals(listOf(corrected), repository.entries.value)
        assertEquals(2, repository.recognitionEvents.value.size)
        assertEquals("ORDER", repository.recognitionEvents.value.first { it.id == second.eventId }.association)
    }

    @Test fun distinctReliableOrdersAtIdenticalAmountTimeAndMerchantRemainSeparate() = runBlocking {
        val first = payment("first", "PAYMENTTEST0001")
        val second = first.copy(eventFingerprint = hash("second"), orderId = "PAYMENTTEST0002")
        assertEquals(RecognitionDecision.AUTO, repository.ingest(first).decision)
        assertEquals(RecognitionDecision.AUTO, repository.ingest(second).decision)
        assertEquals(2, repository.entries.value.size)
        assertEquals(5_600L, LedgerStatistics.calculate(repository.entries.value).expenseCents)
    }

    @Test fun amountAndTimeOnlyAreReviewSuggestionsAndImagesNeverStronglyLink() = runBlocking {
        val original = payment("original")
        val posted = repository.ingest(original)
        val weak = repository.ingest(original.copy(eventFingerprint = hash("weak"), orderId = null, orderNamespace = null))
        val image = repository.ingest(original.copy(eventFingerprint = hash("image"), evidence = EvidenceType.OCR))
        assertEquals(RecognitionDecision.REVIEW, weak.decision)
        assertEquals(RecognitionDecision.REVIEW, image.decision)
        assertEquals(1, repository.entries.value.size)
        assertEquals(posted.entryId, repository.recognitionEvents.value.first { it.id == weak.eventId }.suggestedEntryId)
        repository.linkEvent(image.eventId!!, posted.entryId!!, hash("image"))
        assertEquals(1, repository.entries.value.size)
        assertEquals("MANUAL", repository.recognitionEvents.value.first { it.id == image.eventId }.association)
    }

    @Test fun pendingExactEventCanCompleteButCannotRegressOrReviveHumanIgnore() = runBlocking {
        val original = payment("pending")
        val pending = repository.ingest(original.copy(completion = CompletionState.PENDING))
        assertEquals(RecognitionDecision.IGNORE, pending.decision)
        assertTrue(repository.entries.value.isEmpty())
        val completed = repository.ingest(original)
        assertEquals(pending.eventId, completed.eventId)
        assertEquals(RecognitionDecision.AUTO, completed.decision)
        repository.ingest(original.copy(completion = CompletionState.FAILED))
        assertEquals(CompletionState.COMPLETED, repository.recognitionEvents.value.single().candidate.completion)
        assertEquals(1, repository.entries.value.size)

        val ignored = payment("ignored", "PAYMENTIGNORED01").copy(completion = CompletionState.UNCONFIRMED)
        val review = repository.ingest(ignored)
        repository.ignoreEvent(review.eventId!!, ignored.eventFingerprint)
        repository.ingest(ignored.copy(completion = CompletionState.COMPLETED))
        val cross = repository.ingest(ignored.copy(eventFingerprint = hash("ignored-notification"),
            evidence = EvidenceType.NOTIFICATION, completion = CompletionState.COMPLETED))
        assertEquals(RecognitionDecision.IGNORE, cross.decision)
        assertEquals(1, repository.entries.value.size)
        repository.undoIgnoreEvent(review.eventId, ignored.eventFingerprint)
        assertFalse(repository.recognitionEvents.value.first { it.id == review.eventId }.userIgnored)
        repository.ingest(ignored.copy(completion = CompletionState.COMPLETED))
        assertEquals(2, repository.entries.value.size)
    }

    @Test fun exactFinancialConflictIsResolvedByExplicitEditingOfTheSameCanonicalRow() = runBlocking {
        val source = payment("same")
        val first = repository.ingest(source)
        val canonical = repository.entries.value.single()
        val conflict = repository.ingest(source.copy(amountCents = 2_900))
        assertEquals(RecognitionDecision.REVIEW, conflict.decision)
        assertEquals(first.entryId, conflict.entryId)
        assertEquals(canonical, repository.entries.value.single())
        repository.confirmEvent(conflict.eventId!!, canonical.copy(amountCents = 2_900), expectedFingerprint = source.eventFingerprint)
        val updated = repository.entries.value.single()
        assertEquals(canonical.id, updated.id)
        assertEquals(2_900L, updated.amountCents)
        assertEquals(canonical.fingerprint, updated.fingerprint)
        assertEquals(canonical.source, updated.source)
        assertEquals(RecognitionDecision.AUTO, repository.recognitionEvents.value.single().decision)
    }

    @Test fun anEditedCanonicalAmountMakesNewOrderEvidenceReviewInsteadOfOverwritingOrPosting() = runBlocking {
        val source = payment("first")
        repository.ingest(source)
        repository.update(repository.entries.value.single().copy(amountCents = 2_500))
        val result = repository.ingest(source.copy(eventFingerprint = hash("second"), evidence = EvidenceType.NOTIFICATION))
        assertEquals(RecognitionDecision.REVIEW, result.decision)
        assertNull(result.entryId)
        assertEquals(2_500L, repository.entries.value.single().amountCents)
        assertEquals(repository.entries.value.single().id, repository.recognitionEvents.value.first { it.id == result.eventId }.suggestedEntryId)
    }

    @Test fun unlinkOnlyDetachesEvidenceAndRepeatedCaptureDoesNotUndoTheUserAction() = runBlocking {
        val source = payment("first")
        repository.ingest(source)
        val canonical = repository.entries.value.single()
        val second = source.copy(eventFingerprint = hash("second"), evidence = EvidenceType.NOTIFICATION)
        val associated = repository.ingest(second)
        repository.unlinkEvent(associated.eventId!!, second.eventFingerprint)
        repository.ingest(second)
        val detached = repository.recognitionEvents.value.first { it.id == associated.eventId }
        assertNull(detached.linkedEntryId)
        assertEquals("DETACHED", detached.association)
        assertEquals(RecognitionDecision.REVIEW, detached.decision)
        assertEquals(listOf(canonical), repository.entries.value)
        repository.linkEvent(detached.id, canonical.id, detached.candidate.eventFingerprint)
        assertEquals(listOf(canonical), repository.entries.value)
    }

    @Test fun deletedStableOrderIsSuppressedAcrossRestartAndTimeOnlyUpgradeIsReview() = runBlocking {
        val source = payment("first")
        val posted = repository.ingest(source)
        repository.delete(posted.entryId!!)
        reopen()
        val repeated = repository.ingest(source.copy(eventFingerprint = hash("cross"), evidence = EvidenceType.NOTIFICATION,
            observedAt = System.currentTimeMillis()))
        assertEquals(RecognitionDecision.IGNORE, repeated.decision)
        assertTrue(repository.entries.value.isEmpty())
        repository.restoreDeleted(posted.entryId)
        assertEquals(1, repository.entries.value.size)
        assertEquals(RecognitionDecision.AUTO, repository.recognitionEvents.value.first { it.id == repeated.eventId }.decision)

        val timeOnly = payment("time-only", "PAYMENTTIME0001").copy(orderId = null, orderNamespace = null,
            amountCents = 1_234, merchant = "另一合成商户")
        val timePosted = repository.ingest(timeOnly)
        repository.delete(timePosted.entryId!!)
        val enriched = repository.ingest(timeOnly.copy(eventFingerprint = hash("enriched"), orderId = "PAYMENTTIME0002", orderNamespace = "provider_transaction"))
        assertEquals(RecognitionDecision.REVIEW, enriched.decision)
        assertNull(enriched.entryId)
        assertEquals(1, repository.entries.value.size)
    }

    @Test fun refundRequiresUniqueOriginalAndAggregateLimitAndDoesNotBecomeIncome() = runBlocking {
        val original = payment("purchase", "PURCHASETEST0001").copy(amountCents = 10_000)
        val originalId = repository.ingest(original).entryId!!
        val refund = refund("refund-one", "REFUNDTEST0001", original.orderId!!, 3_000)
        val result = repository.ingest(refund)
        assertEquals(RecognitionDecision.AUTO, result.decision)
        assertEquals(originalId, repository.entries.value.first { it.id == result.entryId }.relatedEntryId)
        assertEquals("餐饮", repository.entries.value.first { it.id == result.entryId }.category)
        val overflow = repository.ingest(refund("refund-two", "REFUNDTEST0002", original.orderId, 8_000))
        assertEquals(RecognitionDecision.REVIEW, overflow.decision)
        assertEquals(2, repository.entries.value.size)
        val totals = LedgerStatistics.calculate(repository.entries.value)
        assertEquals(7_000L, totals.expenseCents)
        assertEquals(0L, totals.incomeCents)
        assertTrue(runCatching { repository.confirmEvent(overflow.eventId!!,
            LedgerEntry(amountCents = 8_000, type = EntryType.INCOME, category = "餐饮", businessType = BusinessType.REFUND, relatedEntryId = originalId)) }.isFailure)
        assertEquals(2, repository.entries.value.size)
    }

    @Test fun sameRefundIdWithDifferentKnownOriginalIsReviewOnNewChannelAndExactUpdate() = runBlocking {
        val firstPurchase = payment("purchase-one", "PURCHASETEST0001").copy(amountCents = 10_000)
        val secondPurchase = firstPurchase.copy(eventFingerprint = hash("purchase-two"), orderId = "PURCHASETEST0002")
        val firstId = repository.ingest(firstPurchase).entryId!!
        val secondId = repository.ingest(secondPurchase).entryId!!
        val refund = refund("refund", "REFUNDTEST0001", firstPurchase.orderId!!, 2_000)
        val posted = repository.ingest(refund)
        val canonical = repository.entries.value.first { it.id == posted.entryId }
        val contradictory = refund.copy(eventFingerprint = hash("refund-notification"), evidence = EvidenceType.NOTIFICATION,
            originalOrderId = secondPurchase.orderId)
        assertEquals(RecognitionDecision.REVIEW, repository.ingest(contradictory).decision)
        assertEquals(firstId, repository.entries.value.first { it.id == posted.entryId }.relatedEntryId)
        val exact = repository.ingest(refund.copy(originalOrderId = secondPurchase.orderId))
        assertEquals(RecognitionDecision.REVIEW, exact.decision)
        assertEquals(canonical, repository.entries.value.first { it.id == posted.entryId })
        repository.confirmEvent(exact.eventId!!, canonical.copy(relatedEntryId = secondId), expectedFingerprint = refund.eventFingerprint)
        assertEquals(canonical.id, repository.entries.value.first { it.id == posted.entryId }.id)
        assertEquals(secondId, repository.entries.value.first { it.id == posted.entryId }.relatedEntryId)
    }

    @Test fun refundEditsDeletionAndRestorePreserveAggregateAndNetExpense() = runBlocking {
        repository.add(manualExpense(10_000))
        val original = repository.entries.value.single()
        repository.add(manualRefund(3_000, original.id))
        repository.add(manualRefund(5_000, original.id))
        val firstRefund = repository.entries.value.first { it.amountCents == 3_000L }
        assertTrue(runCatching { repository.update(firstRefund.copy(amountCents = 7_000)) }.isFailure)
        assertTrue(runCatching { repository.update(original.copy(amountCents = 7_000)) }.isFailure)
        assertEquals(2_000L, LedgerStatistics.calculate(repository.entries.value).expenseCents)
        repository.delete(original.id)
        assertTrue(repository.entries.value.all { it.needsReview })
        assertEquals(2, repository.recognitionEvents.value.count { it.decision == RecognitionDecision.REVIEW })
        assertEquals(0L, LedgerStatistics.calculate(repository.entries.value).incomeCents)
        repository.restoreDeleted(original.id)
        assertTrue(repository.entries.value.none { it.needsReview })
        assertEquals(2_000L, LedgerStatistics.calculate(repository.entries.value).expenseCents)
        repository.delete(firstRefund.id)
        assertEquals(5_000L, LedgerStatistics.calculate(repository.entries.value).expenseCents)
        repository.restoreDeleted(firstRefund.id)
        assertEquals(2_000L, LedgerStatistics.calculate(repository.entries.value).expenseCents)
    }

    @Test fun newlyIdentifiedRefundOriginalCannotOverrideAHumanSelectedOriginal() = runBlocking {
        val firstId = repository.ingest(payment("original-one", "ORIGINALTEST0001").copy(amountCents = 10_000)).entryId!!
        repository.ingest(payment("original-two", "ORIGINALTEST0002").copy(amountCents = 10_000))
        val unidentified = refund("unidentified", "REFUNDORIGIN0001", "ORIGINALTEST0001", 1_000).copy(
            originalOrderId = null, originalOrderNamespace = null)
        val pending = repository.ingest(unidentified)
        repository.confirmEvent(pending.eventId!!, manualRefund(1_000, firstId))
        val newSource = unidentified.copy(eventFingerprint = hash("identified"), evidence = EvidenceType.NOTIFICATION,
            originalOrderId = "ORIGINALTEST0002", originalOrderNamespace = "provider_transaction")
        val result = repository.ingest(newSource)
        assertEquals(RecognitionDecision.REVIEW, result.decision)
        assertNull(result.entryId)
        assertEquals(firstId, repository.entries.value.single { it.businessType == BusinessType.REFUND }.relatedEntryId)
    }

    @Test fun categoryMemoryChangesOnlySuggestionsAndIsAtomicWithConfirmation() = runBlocking {
        val source = payment("image").copy(evidence = EvidenceType.OCR)
        val result = repository.ingest(source)
        repository.confirmEvent(result.eventId!!, manualExpense(2_800).copy(category = "生活", merchant = source.merchant), rememberCategory = true)
        val next = source.copy(eventFingerprint = hash("next"), evidence = EvidenceType.ACCESSIBILITY, orderId = "PAYMENTNEXT0001", amountCents = 2_900)
        repository.ingest(next)
        assertEquals("生活", repository.entries.value.first { it.fingerprint == next.eventFingerprint }.category)
        val transfer = next.copy(eventFingerprint = hash("transfer"), orderId = "TRANSFERTEST0001", businessType = BusinessType.TRANSFER,
            categorySuggestion = "其他")
        val review = repository.ingest(transfer)
        assertEquals(RecognitionDecision.REVIEW, review.decision)
        assertEquals("其他", repository.recognitionEvents.value.first { it.id == review.eventId }.candidate.categorySuggestion)
        assertTrue(runCatching { repository.confirmEvent(review.eventId!!,
            manualExpense(2_900).copy(businessType = BusinessType.TRANSFER, category = "转账", merchant = source.merchant), rememberCategory = true) }.isFailure)
        assertEquals(2, repository.entries.value.size)
        val rule = repository.merchantRules.value.single()
        repository.deleteMerchantRule(rule.source, rule.merchantKey, rule.type)
        assertTrue(repository.merchantRules.value.isEmpty())
    }

    @Test fun concurrentCallbacksAndRefundConfirmationCannotDoublePostOrOverspend() = runBlocking {
        val source = payment("parallel")
        val results = coroutineScope { List(16) { async(Dispatchers.IO) { repository.ingest(source) } }.awaitAll() }
        assertEquals(1, results.count { !it.duplicate })
        assertEquals(1, repository.entries.value.size)
        assertEquals(1, repository.recognitionEvents.value.size)
        repository.add(manualExpense(10_000).copy(merchant = "退款原消费"))
        val original = repository.entries.value.first { it.amountCents == 10_000L }
        val reviews = listOf("refund-a", "refund-b").map { seed -> repository.ingest(payment(seed, "${seed.replace('-', '_')}00000001").copy(
            amountCents = 6_000, direction = FundsDirection.INCOMING, businessType = BusinessType.REFUND, evidence = EvidenceType.OCR)).eventId!! }
        val saves = coroutineScope { reviews.map { eventId -> async(Dispatchers.IO) {
            runCatching { repository.confirmEvent(eventId, manualRefund(6_000, original.id), recordSeparately = true) }.isSuccess
        } }.awaitAll() }
        assertEquals(1, saves.count { it })
        assertEquals(6_000L, repository.entries.value.filter { it.businessType == BusinessType.REFUND }.sumOf { it.amountCents })
    }

    @Test fun staleUiFingerprintAndLateCaptureCancellationLeaveAllRowsUnchanged() = runBlocking {
        val source = payment("cancelled")
        var checks = 0
        val rejected = repository.ingest(source) { ++checks == 1 }
        assertTrue(rejected.disabled)
        assertTrue(repository.entries.value.isEmpty())
        assertTrue(repository.recognitionEvents.value.isEmpty())
        val posted = repository.ingest(source)
        assertEquals(RecognitionDecision.AUTO, posted.decision)
        val image = repository.ingest(payment("image", "PAYMENTIMAGE0001").copy(evidence = EvidenceType.OCR))
        assertTrue(runCatching { repository.ignoreEvent(image.eventId!!, hash("wrong-fingerprint")) }.isFailure)
        assertEquals(RecognitionDecision.REVIEW, repository.recognitionEvents.value.first { it.id == image.eventId }.decision)
    }

    @Test fun legacyIgnoreUndoAndConfirmReuseTheOriginalPendingIdWithoutResurrection() = runBlocking {
        seedLegacyPending()
        val original = repository.entries.value.single()
        val event = repository.recognitionEvents.value.single()
        repository.ignoreEvent(event.id, event.candidate.eventFingerprint)
        assertTrue(repository.entries.value.isEmpty())
        assertEquals(original.id, repository.deletedEntries.value.single().id)
        assertNull(repository.recognitionEvents.value.single().linkedEntryId)
        repository.undoIgnoreEvent(event.id, event.candidate.eventFingerprint)
        assertTrue(repository.entries.value.isEmpty())
        repository.confirmEvent(event.id, original.copy(needsReview = false), expectedFingerprint = event.candidate.eventFingerprint)
        assertEquals(original.id, repository.entries.value.single().id)
        assertFalse(repository.entries.value.single().needsReview)
        assertTrue(repository.deletedEntries.value.isEmpty())
    }

    @Test fun legacyPendingCanManuallyLinkToPostedTargetWithoutDeletingTarget() = runBlocking {
        seedLegacyPending()
        val pending = repository.entries.value.single()
        val event = repository.recognitionEvents.value.single()
        repository.add(manualExpense(pending.amountCents))
        val target = repository.entries.value.first { !it.needsReview }
        repository.linkEvent(event.id, target.id, event.candidate.eventFingerprint)
        assertEquals(listOf(target), repository.entries.value)
        assertEquals(pending.id, repository.deletedEntries.value.single().id)
        assertEquals(target.id, repository.recognitionEvents.value.single().linkedEntryId)
        repository.unlinkEvent(event.id, event.candidate.eventFingerprint)
        assertEquals(listOf(target), repository.entries.value)
    }

    @Test fun migratedLegacyAliasesSuppressLateDuplicateAndDeletedReceiptWithoutRegisteringSharedAlias() = runBlocking {
        seedLegacyPending(needsReview = false, occurredAt = System.currentTimeMillis() - 5 * 60_000)
        val legacy = repository.entries.value.single()
        val source = payment("new-version", "LEGACYPAYMENT0001").copy(legacyFingerprints = listOf(legacy.fingerprint!!))
        val duplicate = repository.ingest(source)
        assertTrue(duplicate.duplicate)
        assertEquals(RecognitionDecision.IGNORE, duplicate.decision)
        assertEquals(listOf(legacy), repository.entries.value)
        assertEquals(1, repository.recognitionEvents.value.size)
        repository.delete(legacy.id)
        reopen()
        val deleted = repository.ingest(source.copy(eventFingerprint = hash("new-channel"), evidence = EvidenceType.NOTIFICATION,
            observedAt = System.currentTimeMillis()))
        assertTrue(deleted.duplicate)
        assertTrue(repository.entries.value.isEmpty())
        assertEquals(1, repository.deletedEntries.value.size)
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT event_id FROM event_aliases WHERE fingerprint=?", arrayOf(source.eventFingerprint)).use { assertFalse(it.moveToFirst()) }
        }
    }

    @Test fun anAliasOfANewV2EventIsNotTreatedAsALegacyCrossChannelTombstone() = runBlocking {
        val first = payment("v2-first", "NEWV2PAYMENT0001")
        repository.ingest(first)
        val second = payment("v2-second", "NEWV2PAYMENT0002").copy(legacyFingerprints = listOf(first.eventFingerprint))
        assertEquals(RecognitionDecision.AUTO, repository.ingest(second).decision)
        assertEquals(2, repository.entries.value.size)
    }

    @Test fun confirmingOneOfTwoPendingImagesMakesTheOthersSuggestionReachableWithoutAutoPosting() = runBlocking {
        val first = payment("pending-first").copy(evidence = EvidenceType.OCR)
        val second = first.copy(eventFingerprint = hash("pending-second"))
        val firstResult = repository.ingest(first)
        val secondResult = repository.ingest(second)
        assertTrue(repository.recognitionEvents.value.all { it.suggestedEntryId == null })
        repository.confirmEvent(firstResult.eventId!!, manualExpense(2_800).copy(merchant = first.merchant))
        val posted = repository.entries.value.single()
        val updated = repository.recognitionEvents.value.first { it.id == secondResult.eventId }
        assertEquals(RecognitionDecision.REVIEW, updated.decision)
        assertNull(updated.linkedEntryId)
        assertEquals(posted.id, updated.suggestedEntryId)
        assertTrue(updated.reasons.any { it.contains("关联或独立记录") })
        repository.linkEvent(updated.id, posted.id, updated.candidate.eventFingerprint)
        assertEquals(listOf(posted), repository.entries.value)
    }

    @Test fun pendingRefundReferencesCannotCreateInvalidBackupRelationships() = runBlocking {
        repository.add(LedgerEntry(amountCents = 100, type = EntryType.INCOME, category = "收款"))
        val income = repository.entries.value.single()
        assertTrue(runCatching { repository.add(manualRefund(25, income.id).copy(needsReview = true)) }.isFailure)
        repository.add(LedgerEntry(amountCents = 25, type = EntryType.INCOME, category = "退款", businessType = BusinessType.REFUND, needsReview = true))
        val pending = repository.entries.value.first { it.businessType == BusinessType.REFUND }
        assertTrue(runCatching { repository.update(pending.copy(relatedEntryId = pending.id)) }.isFailure)
        assertEquals(2, repository.entries.value.size)
    }

    @Test fun committedWriteWithFailedRefreshReturnsSuccessBlocksRetriesAndRecoversOneRow() = runBlocking {
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE recognition_diagnostics")
        }
        assertTrue(repository.add(manualExpense(1_234)))
        assertTrue(repository.restoreBlocked.value)
        assertTrue(repository.storageError.value.orEmpty().contains("账单已保存"))
        assertTrue(repository.entries.value.isEmpty()) // Previous complete snapshot, not partially refreshed tables.
        assertTrue(runCatching { repository.add(manualExpense(1_234)) }.isFailure)
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM entries", null).use { cursor -> cursor.moveToFirst(); assertEquals(1, cursor.getInt(0)) }
            db.execSQL("CREATE TABLE recognition_diagnostics (channel TEXT PRIMARY KEY,last_success_at INTEGER NOT NULL DEFAULT 0,last_failure_at INTEGER NOT NULL DEFAULT 0,last_failure_reason TEXT NOT NULL DEFAULT '',last_duration_ms INTEGER NOT NULL DEFAULT 0)")
        }
        repository.retryPendingRestore()
        assertFalse(repository.restoreBlocked.value)
        assertNull(repository.storageError.value)
        assertEquals(1, repository.entries.value.size)
        assertEquals(1_234L, repository.entries.value.single().amountCents)
    }

    @Test fun completionAcknowledgmentAndFinancialSemanticValidationAreMandatory() = runBlocking {
        val uncertain = payment("uncertain").copy(completion = CompletionState.UNCONFIRMED)
        val event = repository.ingest(uncertain)
        assertTrue(runCatching { repository.confirmEvent(event.eventId!!, manualExpense(2_800)) }.isFailure)
        repository.confirmEvent(event.eventId!!, manualExpense(2_800), confirmCompleted = true)
        val failed = repository.ingest(payment("failed", "FAILEDTEST0001").copy(completion = CompletionState.FAILED))
        assertTrue(runCatching { repository.confirmEvent(failed.eventId!!, manualExpense(2_800), confirmCompleted = true) }.isFailure)
        listOf(manualExpense(100).copy(type = EntryType.INCOME),
            manualExpense(100).copy(businessType = BusinessType.UNKNOWN),
            manualExpense(100).copy(relatedEntryId = repository.entries.value.single().id)).forEach { invalid ->
            assertTrue(runCatching { repository.add(invalid) }.isFailure)
        }
        assertEquals(1, repository.entries.value.size)
    }

    @Test fun encryptedBackupThroughRepositoryPreservesEventsAndDisablesCaptureOnRestore() = runBlocking {
        val posted = repository.ingest(payment("purchase"))
        repository.update(repository.entries.value.single().copy(category = "生活"), rememberCategory = true)
        val ignored = repository.ingest(payment("ignored-backup", "BACKUPIGNORE0001").copy(evidence = EvidenceType.OCR))
        repository.ignoreEvent(ignored.eventId!!)
        val password = "synthetic-backup-pass".toCharArray()
        var encrypted: ByteArray? = null
        try {
            encrypted = repository.createEncryptedBackup(password)
            repository.add(manualExpense(9_999))
            val preview = repository.prepareRestore(encrypted, password)
            assertEquals(1, preview.preview.entryCount)
            assertEquals(2, preview.preview.eventCount)
            repository.restorePrepared(preview)
            assertTrue(preview.isDisposed)
            assertEquals(posted.entryId, repository.entries.value.single().id)
            assertEquals("生活", repository.entries.value.single().category)
            assertEquals(1, repository.merchantRules.value.size)
            assertTrue(repository.recognitionEvents.value.first { it.id == ignored.eventId }.userIgnored)
            assertFalse(repository.settings.value.autoEnabled)
            assertFalse(repository.settings.value.screenEnabled)
            assertFalse(repository.settings.value.screenOcrEnabled)
            assertEquals(Long.MAX_VALUE, repository.automationEnabledSince())
            assertFalse(repository.restoreBlocked.value)
        } finally { password.fill('\u0000'); encrypted?.fill(0) }
    }

    private suspend fun enable() {
        repository.retryPendingRestore()
        repository.updateSettings(repository.settings.value.copy(autoEnabled = true, screenEnabled = true, screenOcrEnabled = true))
    }
    private suspend fun reopen() {
        repository.closeForTesting()
        repository = LedgerRepository.isolated(context, databaseName, preferencesName)
        repository.retryPendingRestore()
    }
    private fun hash(seed: String) = identityHash("synthetic-core|$seed")
    private fun payment(seed: String, order: String = "PAYMENTTEST0001") = TransactionCandidate(
        provider = EntrySource.WECHAT, eventFingerprint = hash(seed), amountCents = 2_800,
        direction = FundsDirection.OUTGOING, businessType = BusinessType.EXPENSE, merchant = "合成咖啡店",
        occurredAt = System.currentTimeMillis(), categorySuggestion = "餐饮", completion = CompletionState.COMPLETED,
        evidence = EvidenceType.ACCESSIBILITY, orderId = order, orderNamespace = "provider_transaction", ruleVersion = "wechat-synthetic-core-v2")
    private fun refund(seed: String, order: String, original: String, amount: Long) = payment(seed, order).copy(
        amountCents = amount, direction = FundsDirection.INCOMING, businessType = BusinessType.REFUND,
        originalOrderId = original, orderNamespace = "provider_refund", originalOrderNamespace = "provider_transaction")
    private fun manualExpense(amount: Long) = LedgerEntry(amountCents = amount, type = EntryType.EXPENSE, category = "购物", merchant = "合成手动消费")
    private fun manualRefund(amount: Long, originalId: Long) = LedgerEntry(amountCents = amount, type = EntryType.INCOME,
        businessType = BusinessType.REFUND, category = "购物", relatedEntryId = originalId)

    private suspend fun seedLegacyPending(needsReview: Boolean = true, occurredAt: Long = System.currentTimeMillis()) {
        repository.closeForTesting()
        context.deleteDatabase(databaseName)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName), null).use { db ->
            db.execSQL("CREATE TABLE entries (id INTEGER PRIMARY KEY AUTOINCREMENT, amount_cents INTEGER NOT NULL,type TEXT NOT NULL,category TEXT NOT NULL,merchant TEXT NOT NULL DEFAULT '',note TEXT NOT NULL DEFAULT '',occurred_at INTEGER NOT NULL,source TEXT NOT NULL,fingerprint TEXT UNIQUE,needs_review INTEGER NOT NULL DEFAULT 0,capture_kind TEXT NOT NULL DEFAULT 'LEGACY')")
            db.execSQL("CREATE TABLE seen_events (fingerprint TEXT PRIMARY KEY,observed_at INTEGER NOT NULL)")
            val hash = hash("legacy")
            db.execSQL("INSERT INTO entries(id,amount_cents,type,category,occurred_at,source,fingerprint,needs_review,capture_kind) VALUES (10,2800,'EXPENSE','购物',?,'WECHAT',?,?,'SCREEN')", arrayOf<Any>(occurredAt, hash, if (needsReview) 1 else 0))
            db.execSQL("INSERT INTO seen_events VALUES (?,?)", arrayOf<Any>(hash, occurredAt))
            db.version = 2
        }
        repository = LedgerRepository.isolated(context, databaseName, preferencesName)
        enable()
    }
}
