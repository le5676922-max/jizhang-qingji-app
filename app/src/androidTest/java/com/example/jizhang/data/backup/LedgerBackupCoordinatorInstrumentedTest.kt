package com.example.jizhang.data.backup

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.jizhang.data.LedgerSettings
import com.example.jizhang.data.RecognitionSchema
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Random isolated databases, preferences and private checkpoints only; never the installed ledger. */
@RunWith(AndroidJUnit4::class)
class LedgerBackupCoordinatorInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val harnesses = mutableListOf<Harness>()

    @After
    fun removeOnlyIsolatedData() {
        harnesses.forEach { harness ->
            harness.helper.close()
            context.deleteDatabase(harness.databaseName)
            context.deleteSharedPreferences(harness.preferencesName)
            listOf("", ".bak", ".new").forEach { suffix ->
                val file = File(context.filesDir, harness.journalName + suffix)
                if (file.isDirectory) file.listFiles().orEmpty().forEach { child ->
                    assertTrue(child.isFile)
                    assertTrue(child.delete())
                }
                assertTrue(!file.exists() || file.delete())
            }
        }
        harnesses.clear()
    }

    @Test
    fun encryptedRoundTripPreservesAllTablesAndTurnsOffCaptureWithoutImportingPermissions() {
        val source = harness(rich = true)
        val target = harness()
        val exporter = source.start()
        val restorer = target.start()
        val password = PASSWORD.toCharArray()
        val encrypted = exporter.createEncryptedBackup(password)
        assertArrayEquals(PASSWORD.toCharArray(), password) // Caller retains ownership of arrays.
        val prepared = restorer.prepareRestore(encrypted, password)
        assertEquals(4, prepared.preview.entryCount)
        assertEquals(2, prepared.preview.eventCount)
        assertEquals(1, prepared.preview.deletedEntryCount)
        assertEquals(1, prepared.preview.pendingEntryCount)
        assertTrue(prepared.preview.createdAt > 0)
        // Altering caller-owned input after preview cannot alter the private authenticated payload.
        encrypted.fill(0)
        password.fill('\u0000')

        restorer.restorePrepared(prepared)

        assertEquals(tableDump(source), tableDump(target))
        assertEquals(source.readSettings().copy(autoEnabled = false, screenEnabled = false, screenOcrEnabled = false),
            target.readSettings())
        assertFalse(target.preferences.contains("automation_enabled_since"))
        assertTrue(target.preferences.getBoolean("test_system_permission", false))
        assertEquals("原有诊断", target.preferences.getString("capture_error", null))
        assertTrue(prepared.isDisposed)
        assertFalse(restorer.captureBlocked)
        assertFalse(target.journal().exists())
        expectFailure(LedgerBackupFailure.PREVIEW_EXPIRED) { restorer.restorePrepared(prepared) }
    }

    @Test
    fun wrongPasswordCorruptedCiphertextAndShortExportPasswordNeverWritePersistentData() {
        val source = harness(rich = true)
        val target = harness()
        val exporter = source.start()
        val restorer = target.start()
        val encrypted = exporter.createEncryptedBackup(PASSWORD.toCharArray())
        val originalTables = tableDump(target)
        val originalSettings = target.readSettings()

        expectFailure(LedgerBackupFailure.INVALID_BACKUP) {
            restorer.prepareRestore(encrypted, "incorrect-password".toCharArray())
        }
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        expectFailure(LedgerBackupFailure.INVALID_BACKUP) { restorer.prepareRestore(encrypted, PASSWORD.toCharArray()) }
        expectFailure(LedgerBackupFailure.INVALID_PASSWORD) { restorer.createEncryptedBackup("short".toCharArray()) }

        assertEquals(originalTables, tableDump(target))
        assertEquals(originalSettings, target.readSettings())
        assertFalse(target.journal().exists())
        assertFalse(restorer.captureBlocked)
    }

    @Test
    fun authenticatedButInvalidRawDocumentAndDisposedPreviewCannotBypassValidation() {
        val target = harness()
        val restorer = target.start()
        val original = tableDump(target)
        val invalid = snapshot(rich = true).apply {
            getJSONObject("tables").getJSONArray("entries").getJSONObject(0).put("raw_screen", "个人原始截图文字")
        }.toString().toByteArray(Charsets.UTF_8)
        val encrypted = EncryptedBackupCodec.encrypt(invalid, PASSWORD.toCharArray())
        val failure = expectFailure(LedgerBackupFailure.INVALID_BACKUP) {
            restorer.prepareRestore(encrypted, PASSWORD.toCharArray())
        }
        assertFalse(failure.message.orEmpty().contains("个人原始截图文字"))
        invalid.fill(0)
        encrypted.fill(0)
        val valid = restorer.createEncryptedBackup(PASSWORD.toCharArray())
        val prepared = restorer.prepareRestore(valid, PASSWORD.toCharArray())
        prepared.dispose()
        expectFailure(LedgerBackupFailure.PREVIEW_EXPIRED) { restorer.restorePrepared(prepared) }

        assertEquals(original, tableDump(target))
        assertFalse(target.journal().exists())
    }

    @Test
    fun interruptedRestoreIsRolledBackBeforeRefreshAndCaptureOnAReopenedCoordinator() {
        val target = harness()
        val originalTables = tableDump(target)
        val originalSettings = target.readSettings()
        val original = BackupStorage.exportSnapshot(target.db, originalSettings)
        target.journal().writeOriginal(original)
        original.fill(0)
        val imported = BackupDocumentValidator.validate(snapshot(rich = true).toString().toByteArray(Charsets.UTF_8))
        BackupStorage.restoreSnapshot(target.db, imported)
        target.writeSettings(imported.settings.copy(autoEnabled = false, screenEnabled = false, screenOcrEnabled = false))
        val began = System.currentTimeMillis()
        var refreshedAfterRollback = false
        val restarted = target.coordinator(refreshExtra = {
            assertEquals(originalTables, tableDump(target))
            refreshedAfterRollback = true
        })
        assertTrue(restarted.captureBlocked)

        assertTrue(restarted.retryPendingRestore())

        assertTrue(refreshedAfterRollback)
        assertEquals(originalTables, tableDump(target))
        assertEquals(originalSettings, target.readSettings())
        assertTrue(target.preferences.getLong("automation_enabled_since", 0) >= began)
        assertFalse(restarted.captureBlocked)
        assertFalse(target.journal().exists())
        assertTrue(target.generation >= 3)
    }

    @Test
    fun preferencesCommitFailureRollsBackDatabaseAndAllSixSettings() {
        val source = harness(rich = true)
        val target = harness()
        val encrypted = source.start().createEncryptedBackup(PASSWORD.toCharArray())
        var commits = 0
        val restorer = target.start(commit = { editor ->
            // Android commit may update in-memory preferences even when durable persistence fails.
            editor.commit()
            ++commits != 1
        })
        val originalTables = tableDump(target)
        val originalSettings = target.readSettings()
        val prepared = restorer.prepareRestore(encrypted, PASSWORD.toCharArray())

        expectFailure(LedgerBackupFailure.RESTORE_FAILED) { restorer.restorePrepared(prepared) }

        assertEquals(2, commits)
        assertEquals(originalTables, tableDump(target))
        assertEquals(originalSettings, target.readSettings())
        assertEquals(originalSettings, target.lastAppliedSettings)
        assertTrue(prepared.isDisposed)
        assertFalse(restorer.captureBlocked)
        assertFalse(target.journal().exists())
    }

    @Test
    fun failedRollbackKeepsCheckpointAndBlocksCaptureUntilExplicitRetry() {
        val source = harness(rich = true)
        val target = harness()
        val encrypted = source.start().createEncryptedBackup(PASSWORD.toCharArray())
        val restorer = target.start(commit = { editor -> editor.commit(); false })
        val originalTables = tableDump(target)
        val originalSettings = target.readSettings()
        val prepared = restorer.prepareRestore(encrypted, PASSWORD.toCharArray())

        expectFailure(LedgerBackupFailure.RECOVERY_REQUIRED) { restorer.restorePrepared(prepared) }

        assertTrue(prepared.isDisposed)
        assertTrue(restorer.captureBlocked)
        assertTrue(target.journal().exists())
        expectFailure(LedgerBackupFailure.RECOVERY_REQUIRED) { restorer.createEncryptedBackup(PASSWORD.toCharArray()) }
        val restarted = target.coordinator()
        assertTrue(restarted.retryPendingRestore())
        assertEquals(originalTables, tableDump(target))
        assertEquals(originalSettings, target.readSettings())
        assertFalse(restarted.captureBlocked)
        assertFalse(target.journal().exists())
    }

    @Test
    fun damagedCheckpointNeverEnablesCaptureOrRefreshesUnrecoveredLedger() {
        val target = harness()
        val original = tableDump(target)
        val checkpoint = File(context.filesDir, target.journalName)
        val damaged = "个人原始数据-损坏检查点".toByteArray(Charsets.UTF_8)
        checkpoint.writeBytes(damaged)
        val coordinator = target.coordinator()

        val failure = expectFailure(LedgerBackupFailure.RECOVERY_REQUIRED) { coordinator.retryPendingRestore() }

        assertFalse(failure.message.orEmpty().contains("个人原始数据"))
        assertTrue(coordinator.captureBlocked)
        assertEquals(0, target.refreshCount)
        assertArrayEquals(damaged, checkpoint.readBytes())
        assertEquals(original, tableDump(target))
        expectFailure(LedgerBackupFailure.RECOVERY_REQUIRED) { coordinator.prepareRestore(ByteArray(1), PASSWORD.toCharArray()) }
    }

    @Test
    fun checkpointClearFailureStaysBlockedAndRetryRecoversOriginalData() {
        val source = harness(rich = true)
        val target = harness()
        val encrypted = source.start().createEncryptedBackup(PASSWORD.toCharArray())
        var obstructClear = false
        val obstacle = File(context.filesDir, "${target.journalName}.new")
        val restorer = target.start(refreshExtra = {
            if (obstructClear) {
                assertTrue(obstacle.mkdir())
                File(obstacle, "blocked").writeText("fixture")
                obstructClear = false
            }
        })
        val original = tableDump(target)
        val oldSettings = target.readSettings()
        val prepared = restorer.prepareRestore(encrypted, PASSWORD.toCharArray())
        obstructClear = true

        expectFailure(LedgerBackupFailure.RECOVERY_REQUIRED) { restorer.restorePrepared(prepared) }

        assertTrue(restorer.captureBlocked)
        assertTrue(File(context.filesDir, target.journalName).isFile)
        assertTrue(prepared.isDisposed)
        assertTrue(File(obstacle, "blocked").delete())
        assertTrue(obstacle.delete())
        assertTrue(restorer.retryPendingRestore())
        assertEquals(original, tableDump(target))
        assertEquals(oldSettings, target.readSettings())
        assertFalse(restorer.captureBlocked)
        assertFalse(target.journal().exists())
    }

    @Test
    fun sharedSettingsLockPreventsAConcurrentUpdateDuringDatabaseAndPreferencesRestore() {
        val source = harness(rich = true)
        val target = harness()
        val encrypted = source.start().createEncryptedBackup(PASSWORD.toCharArray())
        val pauseRefresh = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val settingsAttempted = CountDownLatch(1)
        val settingsFinished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val restorer = target.start(refreshExtra = {
            if (pauseRefresh.get()) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        })
        val prepared = restorer.prepareRestore(encrypted, PASSWORD.toCharArray())
        pauseRefresh.set(true)
        val restoreThread = Thread { try { restorer.restorePrepared(prepared) } catch (error: Throwable) { failure.set(error) } }
        val settingsThread = Thread {
            settingsAttempted.countDown()
            synchronized(target.settingsLock) { settingsFinished.countDown() }
        }
        restoreThread.start()
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            settingsThread.start()
            assertTrue(settingsAttempted.await(10, TimeUnit.SECONDS))
            assertFalse(settingsFinished.await(100, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            restoreThread.join(10_000)
            if (settingsThread.isAlive) settingsThread.join(10_000)
        }
        assertFalse(restoreThread.isAlive)
        assertNull(failure.get())
        assertTrue(settingsFinished.await(1, TimeUnit.SECONDS))
        assertFalse(restorer.captureBlocked)
    }

    private fun harness(rich: Boolean = false): Harness = Harness(rich).also { harnesses += it }

    private inner class Harness(rich: Boolean) {
        private val unique = UUID.randomUUID().toString()
        val databaseName = "backup-coordinator-test-$unique.db"
        val preferencesName = "backup-coordinator-test-$unique"
        val journalName = LedgerBackupCoordinator.journalFileName(databaseName)
        val settingsLock = Any()
        val preferences: SharedPreferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        var refreshCount = 0
        var generation = 0L
        var lastAppliedSettings: LedgerSettings? = null
        val helper = object : SQLiteOpenHelper(context, databaseName, null, 3) {
            override fun onConfigure(db: SQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("""CREATE TABLE entries (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    amount_cents INTEGER NOT NULL CHECK(amount_cents > 0 AND amount_cents <= 99999999999),
                    type TEXT NOT NULL CHECK(type IN ('EXPENSE','INCOME')), category TEXT NOT NULL,
                    merchant TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '', occurred_at INTEGER NOT NULL,
                    source TEXT NOT NULL CHECK(source IN ('MANUAL','WECHAT','ALIPAY')), fingerprint TEXT UNIQUE,
                    needs_review INTEGER NOT NULL DEFAULT 0,
                    capture_kind TEXT NOT NULL CHECK(capture_kind IN ('MANUAL','NOTIFICATION','SCREEN','LEGACY')),
                    business_type TEXT NOT NULL CHECK(business_type IN ('EXPENSE','INCOME','TRANSFER','REFUND','REPAYMENT','UNKNOWN')),
                    related_entry_id INTEGER REFERENCES entries(id), edited_by_user INTEGER NOT NULL DEFAULT 0,
                    deleted_at INTEGER)""".trimIndent())
                db.execSQL("CREATE TABLE seen_events (fingerprint TEXT PRIMARY KEY, observed_at INTEGER NOT NULL)")
                RecognitionSchema.install(db)
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Test-only database")
        }
        val db: SQLiteDatabase get() = helper.writableDatabase
        init {
            val initial = BackupDocumentValidator.validate(snapshot(rich).toString().toByteArray(Charsets.UTF_8))
            BackupStorage.restoreSnapshot(db, initial)
            writeSettings(initial.settings)
            preferences.edit().putLong("automation_enabled_since", 1).putBoolean("test_system_permission", true)
                .putString("capture_error", "原有诊断").commit()
        }
        fun coordinator(commit: (SharedPreferences.Editor) -> Boolean = { it.commit() }, refreshExtra: () -> Unit = {}) =
            LedgerBackupCoordinator(context, databaseName, { db }, preferences, settingsLock, { readSettings() },
                { lastAppliedSettings = it; generation++ }, { generation++ }, { refreshCount++; refreshExtra() }, commit)
        fun start(commit: (SharedPreferences.Editor) -> Boolean = { it.commit() }, refreshExtra: () -> Unit = {}) =
            coordinator(commit, refreshExtra).also { assertFalse(it.retryPendingRestore()) }
        fun journal() = RestoreJournal(context, journalName)
        fun writeSettings(settings: LedgerSettings) {
            assertTrue(preferences.edit().putBoolean("auto_enabled", settings.autoEnabled)
                .putBoolean("wechat_enabled", settings.wechatEnabled).putBoolean("alipay_enabled", settings.alipayEnabled)
                .putLong("monthly_budget_cents", settings.monthlyBudgetCents).putBoolean("screen_enabled", settings.screenEnabled)
                .putBoolean("screen_ocr_enabled", settings.screenOcrEnabled).commit())
        }
        fun readSettings() = LedgerSettings(preferences.getBoolean("auto_enabled", false),
            preferences.getBoolean("wechat_enabled", true), preferences.getBoolean("alipay_enabled", true),
            preferences.getLong("monthly_budget_cents", 300_000), preferences.getBoolean("screen_enabled", false),
            preferences.getBoolean("screen_ocr_enabled", false))
    }

    private fun tableDump(harness: Harness): String {
        val bytes = BackupStorage.exportSnapshot(harness.db, harness.readSettings())
        return try { BackupDocumentValidator.validate(bytes).document.getJSONObject("tables").toString() }
        finally { bytes.fill(0) }
    }

    private fun snapshot(rich: Boolean): JSONObject {
        val tables = JSONObject()
        BackupDocumentValidator.tableColumns.keys.forEach { tables.put(it, JSONArray()) }
        val entries = tables.getJSONArray("entries")
        if (!rich) entries.put(entry(1, 777).put("merchant", "旧账本")) else {
            entries.put(entry(10, 10_000).put("source", "WECHAT").put("capture_kind", "NOTIFICATION").put("fingerprint", HASH_A))
                .put(entry(14, 1_000, "INCOME", "REFUND").put("related_entry_id", 10).put("edited_by_user", 1))
                .put(entry(4, 2_000, business = "UNKNOWN").put("needs_review", 1))
                .put(entry(20, 250).put("deleted_at", NOW + 1))
            listOf(HASH_A, HASH_B, HASH_D, HASH_E).forEach {
                tables.getJSONArray("seen_events").put(JSONObject().put("fingerprint", it).put("observed_at", NOW))
            }
            tables.getJSONArray("source_events").put(event(31, HASH_A).put("linked_entry_id", 10)
                .put("order_hash", HASH_C).put("order_namespace", "payment").put("association", "CREATED"))
                .put(event(32, HASH_E).put("evidence", "OCR").put("decision", "REVIEW").put("has_stable_identity", 0)
                    .put("suggested_entry_id", 10).put("amount_options", "[10000,20000]").put("reasons", "[\"识别金额有歧义\"]"))
            listOf(HASH_A to 31, HASH_D to 31, HASH_E to 32).forEach { (hash, id) ->
                tables.getJSONArray("event_aliases").put(JSONObject().put("fingerprint", hash).put("event_id", id))
            }
            tables.getJSONArray("merchant_rules").put(JSONObject().put("provider", "WECHAT").put("merchant_key", "星巴克")
                .put("type", "EXPENSE").put("category", "餐饮").put("updated_at", NOW))
            tables.getJSONArray("link_actions").put(JSONObject().put("id", 6).put("event_id", 31)
                .put("from_entry_id", JSONObject.NULL).put("to_entry_id", 10).put("action", "CONFIRM").put("occurred_at", NOW))
            tables.getJSONArray("recognition_diagnostics").put(JSONObject().put("channel", "OCR").put("last_success_at", NOW)
                .put("last_failure_at", NOW + 1).put("last_failure_reason", "识别暂时不可用").put("last_duration_ms", 42))
        }
        return JSONObject().put("formatVersion", 2).put("schemaVersion", 3).put("createdAt", NOW).put("tables", tables)
            .put("settings", JSONObject().put("autoEnabled", true).put("wechatEnabled", !rich).put("alipayEnabled", rich)
                .put("monthlyBudgetCents", if (rich) 420_000 else 111_000).put("screenEnabled", true).put("screenOcrEnabled", true))
    }

    private fun entry(id: Long, amount: Long, type: String = "EXPENSE", business: String = "EXPENSE") = JSONObject()
        .put("id", id).put("amount_cents", amount).put("type", type).put("category", "餐饮").put("merchant", "星巴克")
        .put("note", "个人备注").put("occurred_at", NOW).put("source", "MANUAL").put("fingerprint", JSONObject.NULL)
        .put("needs_review", 0).put("capture_kind", "MANUAL").put("business_type", business)
        .put("related_entry_id", JSONObject.NULL).put("edited_by_user", 0).put("deleted_at", JSONObject.NULL)
    private fun event(id: Long, hash: String) = JSONObject().put("id", id).put("event_fingerprint", hash)
        .put("provider", "WECHAT").put("evidence", "NOTIFICATION").put("amount_cents", 10_000).put("direction", "OUTGOING")
        .put("business_type", "EXPENSE").put("merchant", "星巴克").put("occurred_at", NOW).put("observed_at", NOW)
        .put("category_suggestion", "餐饮").put("completion", "COMPLETED").put("has_stable_identity", 1).put("user_initiated", 0)
        .put("rule_version", "notification/2").put("amount_options", "[]").put("order_hash", JSONObject.NULL)
        .put("order_namespace", JSONObject.NULL).put("original_order_hash", JSONObject.NULL).put("original_order_namespace", JSONObject.NULL)
        .put("decision", "AUTO").put("reasons", "[]").put("linked_entry_id", JSONObject.NULL)
        .put("suggested_entry_id", JSONObject.NULL).put("related_entry_id", JSONObject.NULL).put("association", "NONE")

    private fun expectFailure(reason: LedgerBackupFailure, action: () -> Any?): LedgerBackupException {
        try { action(); fail("Expected safe backup failure") } catch (failure: LedgerBackupException) {
            assertEquals(reason, failure.reason)
            assertNull(failure.cause)
            return failure
        }
        error("Expected backup failure")
    }

    companion object {
        private const val PASSWORD = "complete-backup-password"
        private const val NOW = 1_760_000_000_000L
        private val HASH_A = "a".repeat(64)
        private val HASH_B = "b".repeat(64)
        private val HASH_C = "c".repeat(64)
        private val HASH_D = "d".repeat(64)
        private val HASH_E = "e".repeat(64)
    }
}
