package com.example.jizhang.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.jizhang.domain.EntrySource
import com.example.jizhang.domain.EntryType
import com.example.jizhang.domain.LedgerEntry
import com.example.jizhang.domain.ScreenPaymentParser
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Seeds a genuine version 1 schema in unique files; no installed user database is touched. */
@RunWith(AndroidJUnit4::class)
class LegacyLedgerMigrationInstrumentedTest {
    private lateinit var context: Context
    private lateinit var repository: LedgerRepository
    private lateinit var databaseName: String
    private lateinit var preferencesName: String
    private var legacyTimestamp = 0L

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        databaseName = "ledger-v1-migration-test-$suffix.db"
        preferencesName = "ledger-v1-migration-test-$suffix"
        legacyTimestamp = System.currentTimeMillis() - 5_000
        seedVersionOne()
        repository = LedgerRepository.isolated(context, databaseName, preferencesName)
        runBlocking { repository.retryPendingRestore() }
    }

    @After
    fun tearDown() = runBlocking {
        if (::repository.isInitialized) repository.closeForTesting()
        context.deleteDatabase(databaseName)
        context.deleteSharedPreferences(preferencesName)
        Unit
    }

    @Test
    fun upgradingPreservesLedgerFieldsAndDeletedEventTombstonesAcrossRestart() = runBlocking {
        awaitEntries(2)
        val automatic = repository.entries.value.single { it.source == EntrySource.WECHAT }
        assertEquals(10_000L, automatic.amountCents)
        assertEquals(EntryType.EXPENSE, automatic.type)
        assertEquals("其他", automatic.category)
        assertEquals("张三", automatic.merchant)
        assertEquals("存量付款", automatic.note)
        assertEquals(legacyTimestamp, automatic.occurredAt)
        assertEquals(AUTO_HASH, automatic.fingerprint)
        assertFalse(automatic.needsReview)
        val manual = repository.entries.value.single { it.source == EntrySource.MANUAL }
        assertEquals(88_888L, manual.amountCents)
        assertEquals(EntryType.INCOME, manual.type)
        assertEquals("手动收入", manual.note)
        assertCaptureKindsAndSeenEvents()

        assertFalse(repository.add(notification(AUTO_HASH)))
        assertFalse(repository.add(notification(DELETED_HASH)))
        assertEquals(2, repository.entries.value.size)
        repository.closeForTesting()
        repository = LedgerRepository.isolated(context, databaseName, preferencesName)
        runBlocking { repository.retryPendingRestore() }
        awaitEntries(2)
        assertFalse(repository.add(notification(DELETED_HASH)))
        assertEquals(2, repository.entries.value.size)
        assertCaptureKindsAndSeenEvents()
    }

    @Test
    fun migratedAutomaticEntriesRequireReviewForMatchingNewCaptureChannels() = runBlocking {
        awaitEntries(2)
        repository.updateSettings(repository.settings.value.copy(screenEnabled = true, autoEnabled=true))
        val screen = requireNotNull(ScreenPaymentParser.parse(
            "com.tencent.mm",
            "转账详情\n转账成功\n转账金额：100.00元\n收款人：张三\n交易单号：TRANSFER20261013MIGRATE",
        ))
        assertEquals(ScreenSaveResult.NEEDS_REVIEW,
            repository.addScreenPayment(screen, System.currentTimeMillis()))
        assertTrue(repository.addNotification(notification("c".repeat(64)).copy(occurredAt=System.currentTimeMillis()), emptyList()))
        val entries = repository.entries.value
        // V2 review evidence is separate; the two preserved legacy entries are untouched.
        assertEquals(2, entries.size)
        assertEquals(0, entries.count { it.needsReview })
        assertFalse(entries.single { it.fingerprint == AUTO_HASH }.needsReview)
        val reviewEvents=repository.recognitionEvents.value.filter {
            it.decision==com.example.jizhang.recognition.RecognitionDecision.REVIEW }
        assertEquals(2,reviewEvents.size)
        assertTrue(reviewEvents.any { it.candidate.eventFingerprint=="c".repeat(64) })
        assertTrue(reviewEvents.all { it.linkedEntryId==null })
        assertEquals(10_000L, entries.filter { it.type == EntryType.EXPENSE && !it.needsReview }.sumOf { it.amountCents })
    }

    private suspend fun awaitEntries(count: Int) {
        withTimeout(5_000) { repository.entries.first { it.size == count } }
    }

    private fun assertCaptureKindsAndSeenEvents() {
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).absolutePath, null,
            SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(3, db.version)
            val kinds = db.query("entries", arrayOf("source", "capture_kind"), null, null,
                null, null, null).use { cursor ->
                buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
            }
            assertEquals("LEGACY", kinds["WECHAT"])
            assertEquals("MANUAL", kinds["MANUAL"])
            val seen = db.query("seen_events", arrayOf("fingerprint"), null, null,
                null, null, null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertEquals(setOf(AUTO_HASH, DELETED_HASH), seen)
        }
    }

    private fun notification(fingerprint: String) = LedgerEntry(
        amountCents = 10_000,
        type = EntryType.EXPENSE,
        category = "其他",
        merchant = "张三",
        note = "新付款通知",
        occurredAt = legacyTimestamp,
        source = EntrySource.WECHAT,
        fingerprint = fingerprint,
    )

    private fun seedVersionOne() {
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("""
                CREATE TABLE entries (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    amount_cents INTEGER NOT NULL CHECK(amount_cents > 0 AND amount_cents <= 99999999999),
                    type TEXT NOT NULL CHECK(type IN ('EXPENSE', 'INCOME')),
                    category TEXT NOT NULL,
                    merchant TEXT NOT NULL DEFAULT '',
                    note TEXT NOT NULL DEFAULT '',
                    occurred_at INTEGER NOT NULL,
                    source TEXT NOT NULL CHECK(source IN ('MANUAL', 'WECHAT', 'ALIPAY')),
                    fingerprint TEXT UNIQUE,
                    needs_review INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX entries_date ON entries(occurred_at DESC, id DESC)")
            db.execSQL("CREATE TABLE seen_events (fingerprint TEXT PRIMARY KEY, observed_at INTEGER NOT NULL)")
            db.execSQL("""
                INSERT INTO entries (amount_cents, type, category, merchant, note, occurred_at, source, fingerprint, needs_review)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(), arrayOf<Any>(10_000L, "EXPENSE", "其他", "张三", "存量付款",
                legacyTimestamp, "WECHAT", AUTO_HASH, 0))
            db.execSQL("""
                INSERT INTO entries (amount_cents, type, category, note, occurred_at, source, needs_review)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(), arrayOf<Any>(88_888L, "INCOME", "工资", "手动收入",
                legacyTimestamp, "MANUAL", 0))
            listOf(AUTO_HASH, DELETED_HASH).forEach { fingerprint ->
                db.execSQL("INSERT INTO seen_events (fingerprint, observed_at) VALUES (?, ?)",
                    arrayOf<Any>(fingerprint, legacyTimestamp))
            }
            db.version = 1
        }
    }

    companion object {
        private val AUTO_HASH = "a".repeat(64)
        private val DELETED_HASH = "b".repeat(64)
    }
}
