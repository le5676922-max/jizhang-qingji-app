package com.example.jizhang.data.backup

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.jizhang.data.LedgerSettings
import com.example.jizhang.data.RecognitionSchema
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Every database has a random test-only name; the installed user's ledger is never opened. */
@RunWith(AndroidJUnit4::class)
class BackupStorageInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databases = mutableListOf<Pair<String, SQLiteOpenHelper>>()
    private val settings = LedgerSettings(autoEnabled = true, wechatEnabled = false, alipayEnabled = true,
        monthlyBudgetCents = 420_000, screenEnabled = true, screenOcrEnabled = true)

    @After
    fun removeIsolatedDatabases() {
        databases.forEach { (name, helper) ->
            helper.close()
            context.deleteDatabase(name)
        }
        databases.clear()
    }

    @Test
    fun completeRoundTripRetainsPendingDeletedAliasesTombstonesAndRefundLinks() {
        val source = database().apply { seedComplete(this) }
        val validated = BackupDocumentValidator.validate(BackupStorage.exportSnapshot(source, settings))
        assertEquals(settings, validated.settings)
        assertEquals(4, validated.entryCount)
        assertEquals(2, validated.eventCount)
        val entries = table(validated.document, "entries")
        assertTrue(entries.getJSONObject(0).isNull("fingerprint"))
        assertTrue(entries.getJSONObject(0).has("fingerprint"))
        // Deliberately place the refund before its expense: restoration needs two passes.
        val reversed = JSONArray()
        for (index in entries.length() - 1 downTo 0) reversed.put(entries.getJSONObject(index))
        validated.document.getJSONObject("tables").put("entries", reversed)
        val target = database().apply { seedSentinel(this) }

        BackupStorage.restoreSnapshot(target, validated)

        val restored = BackupDocumentValidator.validate(BackupStorage.exportSnapshot(target, settings))
        val sourceTables = BackupDocumentValidator.validate(BackupStorage.exportSnapshot(source, settings))
            .document.getJSONObject("tables").toString()
        assertEquals(sourceTables, restored.document.getJSONObject("tables").toString())
        assertEquals(10L, scalar(target, "SELECT related_entry_id FROM entries WHERE id = 14"))
        assertEquals(1L, scalar(target, "SELECT needs_review FROM entries WHERE id = 4"))
        assertEquals(NOW + 1, scalar(target, "SELECT deleted_at FROM entries WHERE id = 20"))
        assertEquals(1L, scalar(target, "SELECT COUNT(*) FROM seen_events WHERE fingerprint = ?", arrayOf(HASH_B)))
        assertEquals(0L, scalar(target, "SELECT COUNT(*) FROM entries WHERE id = 1"))
        assertEquals(1L, scalar(target, "PRAGMA foreign_keys"))
        assertFalse(target.inTransaction())
    }

    @Test
    fun failurePartwayThroughReplacementRollsBackEveryOriginalTable() {
        val source = database().apply { seedComplete(this) }
        val validated = BackupDocumentValidator.validate(BackupStorage.exportSnapshot(source, settings))
        val target = database().apply { seedComplete(this); seedSentinel(this) }
        val before = BackupDocumentValidator.validate(BackupStorage.exportSnapshot(target, settings))
            .document.getJSONObject("tables").toString()
        target.execSQL("""CREATE TRIGGER fixture_restore_failure BEFORE INSERT ON entries
            WHEN NEW.id = 14 BEGIN SELECT RAISE(ABORT, 'fixture restore failure'); END""".trimIndent())

        try {
            BackupStorage.restoreSnapshot(target, validated)
            fail("Expected mid-transaction database failure")
        } catch (failure: BackupStorageException) {
            assertFalse(failure.message.orEmpty().contains("fixture restore failure"))
        }

        val after = BackupDocumentValidator.validate(BackupStorage.exportSnapshot(target, settings))
            .document.getJSONObject("tables").toString()
        assertEquals(before, after)
        assertEquals(1L, scalar(target, "PRAGMA foreign_keys"))
        assertFalse(target.inTransaction())
    }

    @Test
    fun mutatingValidatedJsonCannotBypassPreflightOrClearTheExistingLedger() {
        val source = database().apply { seedComplete(this) }
        val validated = BackupDocumentValidator.validate(BackupStorage.exportSnapshot(source, settings))
        table(validated.document, "link_actions").getJSONObject(0).put("to_entry_id", 999)
        val target = database().apply { seedSentinel(this) }
        val before = BackupStorage.exportSnapshot(target, settings)

        try {
            BackupStorage.restoreSnapshot(target, validated)
            fail("Expected invalid foreign key before mutation")
        } catch (_: BackupValidationException) { }

        assertEquals(BackupDocumentValidator.validate(before).document.getJSONObject("tables").toString(),
            BackupDocumentValidator.validate(BackupStorage.exportSnapshot(target, settings))
                .document.getJSONObject("tables").toString())
        assertFalse(target.inTransaction())
    }

    @Test
    fun exportRejectsRealMoneyInsteadOfSilentlyTruncatingIt() {
        val db = database().apply { seedSentinel(this) }
        db.update("entries", ContentValues().apply { put("amount_cents", 5_000.5) }, "id = ?", arrayOf("1"))

        try {
            BackupStorage.exportSnapshot(db, settings)
            fail("Expected non-integral SQLite value to be rejected")
        } catch (_: BackupStorageException) { }

        db.rawQuery("SELECT amount_cents FROM entries WHERE id = 1", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(5_000.5, cursor.getDouble(0), 0.0)
        }
        assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM seen_events"))
        assertFalse(db.inTransaction())
    }

    private fun database(): SQLiteDatabase {
        val name = "backup-storage-test-${UUID.randomUUID()}.db"
        val helper = object : SQLiteOpenHelper(context, name, null, 3) {
            override fun onConfigure(db: SQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("""CREATE TABLE entries (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    amount_cents INTEGER NOT NULL CHECK(amount_cents > 0 AND amount_cents <= 99999999999),
                    type TEXT NOT NULL CHECK(type IN ('EXPENSE','INCOME')),
                    category TEXT NOT NULL, merchant TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '',
                    occurred_at INTEGER NOT NULL, source TEXT NOT NULL CHECK(source IN ('MANUAL','WECHAT','ALIPAY')),
                    fingerprint TEXT UNIQUE, needs_review INTEGER NOT NULL DEFAULT 0,
                    capture_kind TEXT NOT NULL DEFAULT 'LEGACY' CHECK(capture_kind IN ('MANUAL','NOTIFICATION','SCREEN','LEGACY')),
                    business_type TEXT NOT NULL CHECK(business_type IN ('EXPENSE','INCOME','TRANSFER','REFUND','REPAYMENT','UNKNOWN')),
                    related_entry_id INTEGER REFERENCES entries(id), edited_by_user INTEGER NOT NULL DEFAULT 0,
                    deleted_at INTEGER)""".trimIndent())
                db.execSQL("CREATE INDEX entries_date ON entries(occurred_at DESC, id DESC)")
                db.execSQL("CREATE TABLE seen_events (fingerprint TEXT PRIMARY KEY, observed_at INTEGER NOT NULL)")
                RecognitionSchema.install(db)
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                error("Isolated backup test must not upgrade a user database")
            }
        }
        databases += name to helper
        return helper.writableDatabase
    }

    private fun seedSentinel(db: SQLiteDatabase) {
        insert(db, "entries", entry(1, 5_000).put("note", "原有账单"))
        insert(db, "seen_events", JSONObject().put("fingerprint", HASH_F).put("observed_at", NOW))
    }

    private fun seedComplete(db: SQLiteDatabase) {
        insert(db, "entries", entry(10, 10_000).put("fingerprint", HASH_A).put("source", "WECHAT")
            .put("capture_kind", "NOTIFICATION").put("category", "餐饮"))
        insert(db, "entries", entry(14, 1_000, "INCOME", "REFUND").put("category", "退款")
            .put("related_entry_id", 10).put("edited_by_user", 1))
        insert(db, "entries", entry(4, 2_000, business = "UNKNOWN").put("needs_review", 1))
        insert(db, "entries", entry(20, 250).put("deleted_at", NOW + 1))
        listOf(HASH_A, HASH_B, HASH_D, HASH_E).forEach {
            insert(db, "seen_events", JSONObject().put("fingerprint", it).put("observed_at", NOW))
        }
        insert(db, "source_events", event(31, HASH_A).put("linked_entry_id", 10)
            .put("order_hash", HASH_C).put("order_namespace", "payment").put("association", "CREATED"))
        insert(db, "source_events", event(32, HASH_E).put("evidence", "OCR").put("decision", "REVIEW")
            .put("has_stable_identity", 0).put("suggested_entry_id", 10).put("amount_options", "[10000,20000]")
            .put("reasons", "[\"识别金额有歧义\"]"))
        listOf(HASH_A to 31L, HASH_D to 31L, HASH_E to 32L).forEach { (fingerprint, id) ->
            insert(db, "event_aliases", JSONObject().put("fingerprint", fingerprint).put("event_id", id))
        }
        insert(db, "merchant_rules", JSONObject().put("provider", "WECHAT").put("merchant_key", "星巴克")
            .put("type", "EXPENSE").put("category", "餐饮").put("updated_at", NOW))
        insert(db, "link_actions", JSONObject().put("id", 6).put("event_id", 31).put("from_entry_id", JSONObject.NULL)
            .put("to_entry_id", 10).put("action", "CONFIRM").put("occurred_at", NOW))
        insert(db, "recognition_diagnostics", JSONObject().put("channel", "NOTIFICATION").put("last_success_at", NOW)
            .put("last_failure_at", NOW + 1).put("last_failure_reason", "存储暂时不可用").put("last_duration_ms", 42))
    }

    private fun entry(id: Long, amount: Long, type: String = "EXPENSE", business: String = "EXPENSE") = JSONObject()
        .put("id", id).put("amount_cents", amount).put("type", type).put("category", "其他")
        .put("merchant", "星巴克").put("note", "fixture").put("occurred_at", NOW).put("source", "MANUAL")
        .put("fingerprint", JSONObject.NULL).put("needs_review", 0).put("capture_kind", "MANUAL")
        .put("business_type", business).put("related_entry_id", JSONObject.NULL).put("edited_by_user", 0)
        .put("deleted_at", JSONObject.NULL)

    private fun event(id: Long, fingerprint: String) = JSONObject().put("id", id).put("event_fingerprint", fingerprint)
        .put("provider", "WECHAT").put("evidence", "NOTIFICATION").put("amount_cents", 10_000)
        .put("direction", "OUTGOING").put("business_type", "EXPENSE").put("merchant", "星巴克")
        .put("occurred_at", NOW).put("observed_at", NOW).put("category_suggestion", "餐饮")
        .put("completion", "COMPLETED").put("has_stable_identity", 1).put("user_initiated", 0)
        .put("rule_version", "notification/2").put("amount_options", "[]").put("order_hash", JSONObject.NULL)
        .put("order_namespace", JSONObject.NULL).put("original_order_hash", JSONObject.NULL)
        .put("original_order_namespace", JSONObject.NULL).put("decision", "AUTO").put("reasons", "[]")
        .put("linked_entry_id", JSONObject.NULL).put("suggested_entry_id", JSONObject.NULL)
        .put("related_entry_id", JSONObject.NULL).put("association", "NONE")

    private fun insert(db: SQLiteDatabase, table: String, row: JSONObject) {
        val values = ContentValues()
        row.keys().forEach { column ->
            when (val value = row.get(column)) {
                JSONObject.NULL -> values.putNull(column)
                is String -> values.put(column, value)
                is Number -> values.put(column, value.toLong())
                else -> error("Unsupported fixture value")
            }
        }
        db.insertOrThrow(table, null, values)
    }

    private fun table(document: JSONObject, name: String): JSONArray = document.getJSONObject("tables").getJSONArray(name)
    private fun scalar(db: SQLiteDatabase, sql: String, args: Array<String>? = null): Long =
        db.rawQuery(sql, args).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getLong(0) }

    companion object {
        private const val NOW = 1_760_000_000_000L
        private val HASH_A = "a".repeat(64)
        private val HASH_B = "b".repeat(64)
        private val HASH_C = "c".repeat(64)
        private val HASH_D = "d".repeat(64)
        private val HASH_E = "e".repeat(64)
        private val HASH_F = "f".repeat(64)
    }
}
