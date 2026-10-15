package com.example.jizhang.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.jizhang.domain.EntrySource
import com.example.jizhang.domain.EntryType
import com.example.jizhang.domain.LedgerEntry
import com.example.jizhang.domain.ScreenPayment
import com.example.jizhang.domain.ScreenPaymentParser
import com.example.jizhang.domain.BusinessType
import com.example.jizhang.recognition.RecognitionDecision
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Integration tests use unique files and never open or clear the user's ledger. */
@RunWith(AndroidJUnit4::class)
class ScreenRepositoryInstrumentedTest {
    private lateinit var context: Context
    private lateinit var repository: LedgerRepository
    private lateinit var databaseName: String
    private lateinit var preferencesName: String

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        databaseName = "screen-repository-test-$suffix.db"
        preferencesName = "screen-repository-test-$suffix"
        repository = LedgerRepository.isolated(context, databaseName, preferencesName)
        runBlocking { repository.retryPendingRestore() }
    }

    @After
    fun tearDown() = runBlocking {
        repository.closeForTesting()
        context.deleteDatabase(databaseName)
        context.deleteSharedPreferences(preferencesName)
        Unit
    }

    @Test
    fun bothScreenOptionsDefaultToOffAfterOpeningAndReopening() = runBlocking {
        assertFalse(repository.settings.value.screenEnabled)
        assertFalse(repository.settings.value.screenOcrEnabled)
        reopen()
        assertFalse(repository.settings.value.screenEnabled)
        assertFalse(repository.settings.value.screenOcrEnabled)
    }

    @Test
    fun explicitScreenAndOcrChoicesPersistAcrossRestart() = runBlocking {
        val enabled = repository.settings.value.copy(screenEnabled = true, screenOcrEnabled = true)
        repository.updateSettings(enabled)
        reopen()
        assertEquals(enabled, repository.settings.value)
        val nodesOnly = enabled.copy(screenOcrEnabled = false)
        repository.updateSettings(nodesOnly)
        reopen()
        assertEquals(nodesOnly, repository.settings.value)
    }

    @Test
    fun automaticCaptureDoesNotWriteBeforeScreenOptIn() = runBlocking {
        val result = repository.addScreenPayment(receipt("TRANSFER20261013A"), System.currentTimeMillis())
        assertEquals(ScreenSaveResult.SOURCE_DISABLED, result)
        assertTrue(repository.entries.value.isEmpty())
        // Read the actual stored rows as well as the initially empty StateFlow.
        assertEquals(1, repository.exportCsv().lineSequence().count { it.isNotEmpty() })
    }

    @Test
    fun aStableCompletedOutgoingReceiptCanBeSavedForBothProviders() = runBlocking {
        enableScreen()
        val now = System.currentTimeMillis()
        listOf(WECHAT, ALIPAY).forEach { packageName ->
            val screen = receipt("TRANSFER20261013A", packageName = packageName)
            assertEquals(ScreenSaveResult.INSERTED, repository.addScreenPayment(screen, now))
        }
        val entries = repository.entries.value
        assertEquals(2, entries.size)
        assertEquals(setOf(EntrySource.WECHAT, EntrySource.ALIPAY), entries.map { it.source }.toSet())
        entries.forEach { entry ->
            assertEquals(EntryType.EXPENSE, entry.type)
            assertEquals(10_000L, entry.amountCents)
            assertEquals("张三", entry.merchant)
            assertFalse(entry.needsReview)
            assertTrue(entry.fingerprint?.matches(Regex("[a-f0-9]{64}")) == true)
            assertFalse(entry.note.contains("TRANSFER20261013A"))
        }
    }

    @Test
    fun ocrKeepsEvenAnIdentifiedReceiptOutOfConfirmedTotals() = runBlocking {
        enableScreen()
        val screen = receipt("TRANSFER20261013OCR", fromOcr = true)
        assertEquals(ScreenSaveResult.NEEDS_REVIEW, repository.addScreenPayment(screen, System.currentTimeMillis()))
        assertTrue(repository.entries.value.isEmpty())
        assertEquals(RecognitionDecision.REVIEW, repository.recognitionEvents.value.single().decision)
        assertEquals(0L, confirmedExpenseCents())
    }

    @Test
    fun aReceiptWithoutTransactionIdentityRequiresReview() = runBlocking {
        enableScreen()
        val screen = receipt(null)
        assertEquals(ScreenSaveResult.NEEDS_REVIEW, repository.addScreenPayment(screen, System.currentTimeMillis()))
        assertTrue(repository.entries.value.isEmpty())
        val event = repository.recognitionEvents.value.single()
        assertEquals(RecognitionDecision.REVIEW, event.decision)
        assertTrue(event.reasons.any { it.contains("缺少稳定交易标识") })
        assertEquals(0L, confirmedExpenseCents())
    }

    @Test
    fun minutePrecisionWithoutAnIdPreservesBothEqualPaymentsForReview() = runBlocking {
        enableScreen()
        val minute = shanghaiTime(includeSeconds = false)
        val first = receipt(null, transactionTime = minute)
        val second = receipt(null, transactionTime = minute)
        assertFalse(first.hasStableIdentity)
        assertFalse(second.hasStableIdentity)
        assertTrue(first.payment.needsReview)
        val now = System.currentTimeMillis()
        assertEquals(ScreenSaveResult.NEEDS_REVIEW, repository.addScreenPayment(first, now))
        assertEquals(ScreenSaveResult.NEEDS_REVIEW, repository.addScreenPayment(second, now + 1_000))
        val events = repository.recognitionEvents.value
        assertTrue(repository.entries.value.isEmpty())
        assertEquals(2, events.size)
        assertTrue(events.all { it.decision == RecognitionDecision.REVIEW })
        assertEquals(2, events.map { it.candidate.eventFingerprint }.distinct().size)
        assertEquals(0L, confirmedExpenseCents())
    }

    @Test
    fun addingAnOrderIdToASecondPrecisionReceiptDoesNotConfirmItTwice() = runBlocking {
        enableScreen()
        val second = shanghaiTime(includeSeconds = true)
        val timeOnly = receipt(null, transactionTime = second)
        val enriched = receipt("TRANSFER20261013ENRICHED", transactionTime = second)
        assertTrue(timeOnly.hasStableIdentity)
        assertTrue(enriched.hasStableIdentity)
        assertEquals(timeOnly.occurredAt, enriched.occurredAt)
        val now = System.currentTimeMillis()
        assertEquals(ScreenSaveResult.INSERTED, repository.addScreenPayment(timeOnly, now))
        assertEquals(ScreenSaveResult.NEEDS_REVIEW, repository.addScreenPayment(enriched, now + 1_000))
        val entries = repository.entries.value
        assertEquals(1, entries.size)
        assertFalse(entries.single().needsReview)
        assertEquals(2, repository.recognitionEvents.value.size)
        assertEquals(entries.single().id, repository.recognitionEvents.value.single { it.decision == RecognitionDecision.REVIEW }.suggestedEntryId)
        assertEquals(10_000L, confirmedExpenseCents())
    }

    @Test
    fun aDeletedTimeOnlyReceiptDoesNotBecomeConfirmedWhenItsOrderIdAppears() = runBlocking {
        enableScreen()
        val second = shanghaiTime(includeSeconds = true)
        val timeOnly = receipt(null, transactionTime = second)
        val now = System.currentTimeMillis()
        assertEquals(ScreenSaveResult.INSERTED, repository.addScreenPayment(timeOnly, now))
        repository.delete(repository.entries.value.single().id)
        assertTrue(repository.entries.value.isEmpty())
        reopen()
        val enriched = receipt("TRANSFER20261013DELETED", transactionTime = second)
        assertEquals(ScreenSaveResult.NEEDS_REVIEW, repository.addScreenPayment(enriched, now + 1_000))
        assertTrue(repository.entries.value.isEmpty())
        assertEquals(1, repository.deletedEntries.value.size)
        assertEquals(1, repository.recognitionEvents.value.count { it.decision == RecognitionDecision.REVIEW })
        assertEquals(0L, confirmedExpenseCents())
    }

    @Test
    fun stableReceiptRemainsSeenAfterRedrawDeletionAndRestart() = runBlocking {
        enableScreen()
        val screen = receipt("TRANSFER20261013A")
        val now = System.currentTimeMillis()
        assertEquals(ScreenSaveResult.INSERTED, repository.addScreenPayment(screen, now))
        assertEquals(ScreenSaveResult.DUPLICATE, repository.addScreenPayment(screen, now + 1_000))
        assertEquals(1, repository.entries.value.size)
        repository.delete(repository.entries.value.single().id)
        assertEquals(ScreenSaveResult.DUPLICATE, repository.addScreenPayment(screen, now + 2_000))
        assertTrue(repository.entries.value.isEmpty())
        reopen()
        assertEquals(ScreenSaveResult.DUPLICATE, repository.addScreenPayment(screen, now + 3_000))
        assertTrue(repository.entries.value.isEmpty())
    }

    @Test
    fun twoEqualPaymentsWithDifferentTransactionIdsAreRetained() = runBlocking {
        enableScreen()
        val now = System.currentTimeMillis()
        assertEquals(ScreenSaveResult.INSERTED, repository.addScreenPayment(receipt("TRANSFER20261013A"), now))
        assertEquals(ScreenSaveResult.INSERTED, repository.addScreenPayment(receipt("TRANSFER20261013B"), now + 1_000))
        assertEquals(2, repository.entries.value.size)
        assertEquals(2, repository.entries.value.map { it.fingerprint }.distinct().size)
        assertEquals(20_000L, confirmedExpenseCents())
    }

    @Test
    fun screenAfterMatchingNotificationIsReviewOnly() = runBlocking {
        enableScreen()
        val now = System.currentTimeMillis()
        assertTrue(repository.addNotification(notification("a".repeat(64), now), listOf("b".repeat(64))))
        assertEquals(ScreenSaveResult.NEEDS_REVIEW,
            repository.addScreenPayment(receipt("TRANSFER20261013A"), now + 1_000))
        val entries = repository.entries.value
        assertEquals(1, entries.size)
        assertFalse(entries.single().needsReview)
        assertEquals("a".repeat(64), entries.single().fingerprint)
        assertEquals(1, repository.recognitionEvents.value.count { it.decision == RecognitionDecision.REVIEW })
        assertEquals(10_000L, confirmedExpenseCents())
    }

    @Test
    fun notificationAfterMatchingScreenIsReviewOnly() = runBlocking {
        enableScreen()
        val now = System.currentTimeMillis()
        assertEquals(ScreenSaveResult.INSERTED, repository.addScreenPayment(receipt("TRANSFER20261013A"), now))
        assertTrue(repository.addNotification(notification("c".repeat(64), now + 1_000), listOf("d".repeat(64))))
        val entries = repository.entries.value
        assertEquals(1, entries.size)
        assertFalse(entries.single().needsReview)
        assertEquals(RecognitionDecision.REVIEW, repository.recognitionEvents.value.single { it.candidate.eventFingerprint == "c".repeat(64) }.decision)
        assertEquals(10_000L, confirmedExpenseCents())
    }

    @Test
    fun editingAndReopeningAScreenEntryDoesNotEraseItsCaptureChannel() = runBlocking {
        enableScreen()
        val now = System.currentTimeMillis()
        assertEquals(ScreenSaveResult.INSERTED, repository.addScreenPayment(receipt("TRANSFER20261013EDIT"), now))
        val screenEntry = repository.entries.value.single()
        repository.update(screenEntry.copy(note = "核对后的转账"))
        reopen()
        assertTrue(repository.addNotification(notification("e".repeat(64), now + 1_000), emptyList()))
        val entries = repository.entries.value
        assertEquals("核对后的转账", entries.single { it.id == screenEntry.id }.note)
        assertEquals(RecognitionDecision.REVIEW, repository.recognitionEvents.value.single { it.candidate.eventFingerprint == "e".repeat(64) }.decision)
        assertEquals(1, entries.size)
        assertEquals(10_000L, confirmedExpenseCents())
    }

    @Test
    fun aScreenLookingNoteCannotChangeANotificationsCaptureChannelAfterRestart() = runBlocking {
        enableScreen()
        val now = System.currentTimeMillis()
        assertTrue(repository.addNotification(notification("f".repeat(64), now), emptyList()))
        val notificationEntry = repository.entries.value.single()
        // A user-controlled prefix must not be interpreted as immutable capture metadata.
        repository.update(notificationEntry.copy(note = "屏幕识别 · 核对后的转账"))
        reopen()
        assertEquals(ScreenSaveResult.NEEDS_REVIEW,
            repository.addScreenPayment(receipt("TRANSFER20261013EDIT"), now + 1_000))
        val entries = repository.entries.value
        assertFalse(entries.single { it.id == notificationEntry.id }.needsReview)
        assertEquals(1, repository.recognitionEvents.value.count { it.decision == RecognitionDecision.REVIEW })
        assertEquals(1, entries.size)
        assertEquals(10_000L, confirmedExpenseCents())
    }

    @Test
    fun userRequestedScreenshotCanSaveForReviewWithAutomaticScreenCaptureOff() = runBlocking {
        assertFalse(repository.settings.value.screenEnabled)
        assertFalse(repository.settings.value.screenOcrEnabled)
        val screen = receipt("TRANSFER20261013MANUAL", fromOcr = true)
        assertEquals(ScreenSaveResult.NEEDS_REVIEW,
            repository.addScreenPayment(screen, System.currentTimeMillis(), userInitiated = true))
        assertTrue(repository.entries.value.isEmpty())
        assertEquals(RecognitionDecision.REVIEW, repository.recognitionEvents.value.single().decision)
        assertEquals(0L, confirmedExpenseCents())
        assertFalse(repository.settings.value.screenEnabled)
    }

    @Test
    fun automaticCaptureHonorsEachPaymentProviderSwitch() = runBlocking {
        enableScreen()
        repository.updateSettings(repository.settings.value.copy(wechatEnabled = false, alipayEnabled = false))
        listOf(WECHAT, ALIPAY).forEach { packageName ->
            assertEquals(ScreenSaveResult.SOURCE_DISABLED, repository.addScreenPayment(
                receipt("TRANSFER20261013A", packageName = packageName), System.currentTimeMillis(),
            ))
        }
        assertTrue(repository.entries.value.isEmpty())
    }

    @Test
    fun completedFriendTransferIsAReviewCandidateRatherThanConsumption() = runBlocking {
        enableScreen()
        val transfer = requireNotNull(ScreenPaymentParser.parse(WECHAT,
            "转账详情\n转账成功\n转账金额：100.00元\n收款人：张三\n交易单号：TRANSFERREVIEW0001"))
        assertEquals(BusinessType.TRANSFER, transfer.businessType)
        assertEquals(ScreenSaveResult.NEEDS_REVIEW, repository.addScreenPayment(transfer, System.currentTimeMillis()))
        assertTrue(repository.entries.value.isEmpty())
        assertEquals(BusinessType.TRANSFER, repository.recognitionEvents.value.single().candidate.businessType)
        assertEquals(RecognitionDecision.REVIEW, repository.recognitionEvents.value.single().decision)
    }

    private fun enableScreen() {
        repository.updateSettings(repository.settings.value.copy(autoEnabled = true, screenEnabled = true, screenOcrEnabled = true))
    }

    private suspend fun reopen() {
        repository.closeForTesting()
        repository = LedgerRepository.isolated(context, databaseName, preferencesName)
        repository.retryPendingRestore()
    }

    private fun receipt(
        transactionId: String?,
        fromOcr: Boolean = false,
        packageName: String = WECHAT,
        transactionTime: String? = null,
    ): ScreenPayment {
        val text = buildList {
            // Dedup/storage fixtures are merchant expenses; generic friend transfers are REVIEW in V2.
            add("付款详情")
            add("支付成功")
            add("实付金额：100.00元")
            add("商户：张三")
            if (transactionId != null) add("交易单号：$transactionId")
            if (transactionTime != null) add("交易时间：$transactionTime")
        }.joinToString("\n")
        return requireNotNull(ScreenPaymentParser.parse(packageName, text, fromOcr)) {
            "The completed outgoing receipt fixture must parse"
        }
    }

    private fun shanghaiTime(includeSeconds: Boolean): String = SimpleDateFormat(
        if (includeSeconds) "yyyy-MM-dd HH:mm:ss" else "yyyy-MM-dd HH:mm",
        Locale.ROOT,
    ).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }.format(Date())

    private fun notification(fingerprint: String, occurredAt: Long) = LedgerEntry(
        amountCents = 10_000,
        type = EntryType.EXPENSE,
        category = "其他",
        merchant = "张三",
        source = EntrySource.WECHAT,
        fingerprint = fingerprint,
        occurredAt = occurredAt,
        note = "付款通知",
        needsReview = false,
    )

    private fun confirmedExpenseCents(): Long = repository.entries.value
        .filter { it.type == EntryType.EXPENSE && !it.needsReview }
        .sumOf { it.amountCents }

    companion object {
        private const val WECHAT = "com.tencent.mm"
        private const val ALIPAY = "com.eg.android.AlipayGphone"
    }
}
