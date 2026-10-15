package com.example.jizhang.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.jizhang.domain.BusinessType
import com.example.jizhang.domain.EntrySource
import com.example.jizhang.domain.EntryType
import com.example.jizhang.domain.LedgerEntry
import com.example.jizhang.recognition.CompletionState
import com.example.jizhang.recognition.EvidenceType
import com.example.jizhang.recognition.FundsDirection
import com.example.jizhang.recognition.RecognitionDecision
import com.example.jizhang.recognition.TransactionCandidate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Faults and records belong to unique disposable databases, never the user's ledger. */
@RunWith(AndroidJUnit4::class)
class RecognitionDiagnosticInstrumentedTest {
    private lateinit var context: Context
    private lateinit var repository: LedgerRepository
    private lateinit var databaseName: String
    private lateinit var preferencesName: String

    @Before fun setup() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        databaseName = "diagnostic-test-$suffix.db"
        preferencesName = "diagnostic-test-$suffix"
        repository = LedgerRepository.isolated(context, databaseName, preferencesName)
        repository.retryPendingRestore()
        repository.updateSettings(repository.settings.value.copy(autoEnabled = true,
            screenEnabled = true, screenOcrEnabled = true))
    }

    @After fun cleanup() = runBlocking {
        repository.closeForTesting()
        context.deleteDatabase(databaseName)
        context.deleteSharedPreferences(preferencesName)
        Unit
    }

    @Test fun diagnosticCanPersistWithoutReadingAnUnavailableFinancialTableOrChangingItsFlows() = runBlocking {
        repository.add(LedgerEntry(amountCents = 1_200, type = EntryType.EXPENSE,
            category = "餐饮", merchant = "合成小店"), rememberCategory = true)
        repository.add(LedgerEntry(amountCents = 300, type = EntryType.EXPENSE,
            category = "交通", merchant = "合成车站"))
        repository.delete(repository.entries.value.first { it.amountCents == 300L }.id)
        val now = System.currentTimeMillis()
        val captured = repository.ingest(TransactionCandidate(
            provider = EntrySource.WECHAT,
            eventFingerprint = identityHash("synthetic-diagnostic-pending"),
            amountCents = 700, direction = FundsDirection.OUTGOING,
            businessType = BusinessType.EXPENSE, merchant = "合成待核对商户",
            occurredAt = now, observedAt = now, categorySuggestion = "购物",
            completion = CompletionState.COMPLETED, evidence = EvidenceType.OCR,
            ruleVersion = "synthetic-diagnostic-test", hasStableIdentity = false,
        ))
        assertEquals(RecognitionDecision.REVIEW, captured.decision)
        repository.reportCaptureError("合成持久警告，需要人工检查")

        val entries = repository.entries.value
        val events = repository.recognitionEvents.value
        val deleted = repository.deletedEntries.value
        val rules = repository.merchantRules.value
        val settings = repository.settings.value
        val captureError = repository.captureError.value
        val generation = repository.recognitionConfigurationGeneration()
        val activation = repository.automationEnabledSince()
        assertEquals(1, entries.size)
        assertEquals(1, events.size)
        assertEquals(1, deleted.size)
        assertEquals(1, rules.size)

        // If reporting still reloaded the ledger, this real SQLite fault would fail that reload.
        // Diagnostic writes only need their own table and must not publish any financial state.
        withDatabase { it.execSQL("ALTER TABLE merchant_rules RENAME TO diagnostic_test_hidden_rules") }
        try {
            repository.reportRecognitionDiagnostic(EvidenceType.OCR, "合成识别成功", true, durationMs = 43)
            val diagnostic = withTimeout(10_000) {
                repository.diagnostics.first { values -> values.any { it.channel == "OCR" && it.lastDurationMs == 43L } }
            }.single { it.channel == "OCR" }
            assertTrue(diagnostic.lastSuccessAt > 0)
            assertNull(repository.storageError.value)
            assertFalse(repository.restoreBlocked.value)
            assertSame(entries, repository.entries.value)
            assertSame(events, repository.recognitionEvents.value)
            assertSame(deleted, repository.deletedEntries.value)
            assertSame(rules, repository.merchantRules.value)
            assertEquals(settings, repository.settings.value)
            assertEquals(captureError, repository.captureError.value)
            assertEquals(generation, repository.recognitionConfigurationGeneration())
            assertEquals(activation, repository.automationEnabledSince())
            withDatabase { db ->
                assertEquals(2, count(db, "entries"))
                assertEquals(1, count(db, "source_events"))
                assertEquals(1, count(db, "diagnostic_test_hidden_rules"))
                db.rawQuery("SELECT last_duration_ms FROM recognition_diagnostics WHERE channel='OCR'", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(43L, it.getLong(0))
                }
            }
        } finally {
            withDatabase { it.execSQL("ALTER TABLE diagnostic_test_hidden_rules RENAME TO merchant_rules") }
        }
    }

    @Test fun successfulDiagnosticAfterStorageFailureDoesNotDismissExistingErrorOrReloadLedger() = runBlocking {
        repository.add(LedgerEntry(amountCents = 321, type = EntryType.EXPENSE, category = "其他"))
        val entries = repository.entries.value
        withDatabase { it.execSQL("ALTER TABLE recognition_diagnostics RENAME TO diagnostic_test_hidden_status") }
        try {
            repository.reportRecognitionDiagnostic(EvidenceType.ACCESSIBILITY, "合成保存失败", false)
            withTimeout(10_000) { repository.storageError.first { it != null } }
        } finally {
            withDatabase { it.execSQL("ALTER TABLE diagnostic_test_hidden_status RENAME TO recognition_diagnostics") }
        }
        val error = repository.storageError.value
        assertNotNull(error)
        repository.reportRecognitionDiagnostic(EvidenceType.ACCESSIBILITY, "合成保存成功", true, durationMs = 71)
        withTimeout(10_000) {
            repository.diagnostics.first { values -> values.any { it.channel == "ACCESSIBILITY" && it.lastDurationMs == 71L } }
        }
        assertEquals(error, repository.storageError.value)
        assertSame(entries, repository.entries.value)
        assertFalse(repository.restoreBlocked.value)
        // Only an explicit complete ledger read may establish recovery and dismiss its error.
        repository.retryPendingRestore()
        assertNull(repository.storageError.value)
        assertEquals(listOf(321L), repository.entries.value.map { it.amountCents })
    }

    private fun withDatabase(block: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).absolutePath, null,
            SQLiteDatabase.OPEN_READWRITE).use(block)
    }

    /** Table names are fixed test literals rather than caller or device input. */
    private fun count(database: SQLiteDatabase, table: String): Int =
        database.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
}
