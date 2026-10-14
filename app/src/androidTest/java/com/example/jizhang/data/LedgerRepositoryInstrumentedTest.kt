package com.example.jizhang.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.jizhang.automation.NotificationEventIdentity
import com.example.jizhang.domain.EntrySource
import com.example.jizhang.domain.EntryType
import com.example.jizhang.domain.LedgerEntry
import com.example.jizhang.domain.ParsedPayment
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Every test uses isolated files; the user's ledger is never opened or cleared. */
@RunWith(AndroidJUnit4::class)
class LedgerRepositoryInstrumentedTest {
    private lateinit var context: Context
    private lateinit var repository: LedgerRepository
    private lateinit var databaseName: String
    private lateinit var preferencesName: String

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        databaseName = "ledger-test-$suffix.db"
        preferencesName = "ledger-test-$suffix"
        repository = LedgerRepository.isolated(context, databaseName, preferencesName)
    }

    @After
    fun tearDown() = runBlocking {
        repository.closeForTesting()
        context.deleteDatabase(databaseName)
        context.deleteSharedPreferences(preferencesName)
        Unit
    }

    @Test
    fun duplicateEventDoesNotReturnAfterDeletionOrRestart() = runBlocking {
        val entry = payment("a".repeat(64))
        assertTrue(repository.add(entry))
        assertFalse(repository.add(entry))
        repository.delete(repository.entries.value.single().id)
        assertFalse(repository.add(entry))
        repository.closeForTesting()
        repository = LedgerRepository.isolated(context, databaseName, preferencesName)
        assertFalse(repository.add(entry))
        assertTrue(repository.entries.value.isEmpty())
    }

    @Test
    fun concurrentCallbacksInsertOnceButDistinctSameAmountEventsAreRetained() = runBlocking {
        val results = coroutineScope {
            List(16) { async(Dispatchers.IO) { repository.add(payment("b".repeat(64))) } }.awaitAll()
        }
        assertEquals(1, results.count { it })
        assertTrue(repository.add(payment("c".repeat(64))))
        assertEquals(2, repository.entries.value.size)
    }

    @Test
    fun updatedNotificationAliasesRemainSeenAfterDeletion() = runBlocking {
        val original = payment("f".repeat(64))
        val instanceHash = "1".repeat(64)
        assertTrue(repository.addNotification(original, listOf(instanceHash)))
        val update = payment("2".repeat(64))
        assertFalse(repository.addNotification(update, listOf(instanceHash)))
        repository.delete(repository.entries.value.single().id)
        assertFalse(repository.addNotification(payment("3".repeat(64)), listOf(instanceHash)))
        // The previously observed new postTime is also retained as an alias.
        assertFalse(repository.add(update))
        assertTrue(repository.entries.value.isEmpty())
    }

    @Test
    fun merchantEnrichmentWithANewPostTimeDoesNotDuplicateTheTransaction() = runBlocking {
        val parsed = ParsedPayment(1_230, EntryType.EXPENSE, "其他", "", EntrySource.WECHAT)
        val first = NotificationEventIdentity.create("com.tencent.mm", "key", 1_001, 1_000, parsed)
        val enriched = NotificationEventIdentity.create("com.tencent.mm", "key", 2_000, 1_000,
            parsed.copy(merchant = "新增商户", category = "餐饮"))
        assertTrue(repository.addNotification(payment(first.primary), listOfNotNull(first.stableTransaction)))
        assertFalse(repository.addNotification(payment(enriched.primary).copy(merchant = "新增商户"),
            listOfNotNull(enriched.stableTransaction)))
        assertEquals(1, repository.entries.value.size)
    }

    @Test
    fun editPreservesOriginalSourceAndEventIdentity() = runBlocking {
        repository.add(payment("d".repeat(64)))
        val original = repository.entries.value.single()
        repository.update(original.copy(
            amountCents = 2_340,
            note = "核对后的金额",
            source = EntrySource.MANUAL,
            fingerprint = null,
            needsReview = false,
        ))
        val updated = repository.entries.value.single()
        assertEquals(2_340L, updated.amountCents)
        assertEquals(EntrySource.WECHAT, updated.source)
        assertEquals(original.fingerprint, updated.fingerprint)
        assertFalse(repository.add(payment("d".repeat(64))))
    }

    @Test
    fun csvEscapesMultilineQuotesAndNeutralizesSpreadsheetFormulas() = runBlocking {
        repository.add(payment("e".repeat(64)).copy(
            merchant = "+mall",
            note = "=SUM(1,2)\n\"quoted\"",
        ))
        val csv = repository.exportCsv()
        assertTrue(csv.startsWith("\uFEFF日期"))
        assertTrue(csv.contains("\"'+mall\""))
        assertTrue(csv.contains("\"'=SUM(1,2)\n\"\"quoted\"\"\""))
        assertTrue(csv.contains("\"12.30\""))
    }

    @Test
    fun invalidAmountsAreRejectedWithoutWriting() = runBlocking {
        listOf(0L, -1L, LedgerRepository.MAX_AMOUNT_CENTS + 1).forEach { cents ->
            val result = runCatching { repository.add(payment(null).copy(amountCents = cents)) }
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(repository.entries.value.isEmpty())
    }

    @Test
    fun settingsPersistAndCaptureRequiresExplicitOptIn() = runBlocking {
        assertFalse(repository.settings.value.autoEnabled)
        assertEquals(Long.MAX_VALUE, repository.automationEnabledSince())
        val expected = LedgerSettings(true, false, true, 420_000)
        repository.updateSettings(expected)
        val enabledAt = repository.automationEnabledSince()
        assertTrue(enabledAt in 1..System.currentTimeMillis())
        repository.closeForTesting()
        repository = LedgerRepository.isolated(context, databaseName, preferencesName)
        assertEquals(expected, repository.settings.value)
        assertEquals(enabledAt, repository.automationEnabledSince())
        repository.updateSettings(expected.copy(autoEnabled = false))
        assertEquals(Long.MAX_VALUE, repository.automationEnabledSince())
    }

    @Test
    fun captureWarningSurvivesSuccessAndRestartUntilExplicitlyAcknowledged() = runBlocking {
        val warning = "部分通知未能处理，请核对并手动补录。"
        repository.reportCaptureError(warning)
        assertTrue(repository.add(payment(null)))
        assertEquals(warning, repository.captureError.value)
        repository.closeForTesting()
        repository = LedgerRepository.isolated(context, databaseName, preferencesName)
        assertEquals(warning, repository.captureError.value)
        repository.acknowledgeCaptureError()
        assertNull(repository.captureError.value)
        repository.closeForTesting()
        repository = LedgerRepository.isolated(context, databaseName, preferencesName)
        assertNull(repository.captureError.value)
    }

    private fun payment(fingerprint: String?) = LedgerEntry(
        amountCents = 1_230,
        type = EntryType.EXPENSE,
        category = "餐饮",
        source = EntrySource.WECHAT,
        fingerprint = fingerprint,
        needsReview = true,
    )
}
