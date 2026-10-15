package com.example.jizhang.data.backup

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import com.example.jizhang.data.LedgerSettings
import java.security.MessageDigest

enum class LedgerBackupFailure {
    INVALID_PASSWORD, INVALID_BACKUP, UNAVAILABLE, PREVIEW_EXPIRED, RESTORE_FAILED, RECOVERY_REQUIRED,
}

/** Messages and causes never expose decrypted values, raw JSON, database SQL or filesystem paths. */
class LedgerBackupException(val reason: LedgerBackupFailure, message: String) : Exception(message)

/**
 * Invoke every operation on the IO dispatcher while holding the repository's database mutex.
 * This helper deliberately does not acquire that mutex recursively. updateSettings must use the
 * same settingsLock and reject updates while captureBlocked; hooks update flows/generation only.
 * Call retryPendingRestore before any initial ledger read/capture. It also initializes this helper.
 * The caller owns password/encrypted arrays and clears them in finally; no password is retained.
 */
class LedgerBackupCoordinator(
    context: Context,
    databaseName: String,
    private val database: () -> SQLiteDatabase,
    private val preferences: SharedPreferences,
    private val settingsLock: Any,
    private val readSettings: () -> LedgerSettings,
    private val onSettingsApplied: (LedgerSettings) -> Unit,
    private val onCaptureBlocked: (Boolean) -> Unit,
    private val refresh: () -> Unit,
    private val commitPreferences: (SharedPreferences.Editor) -> Boolean = { it.commit() },
) {
    private val journal = RestoreJournal(context, journalFileName(databaseName))
    private val preparedOwner = Any()
    @Volatile private var initialized = false
    @Volatile var captureBlocked: Boolean = true
        private set

    fun createEncryptedBackup(password: CharArray): ByteArray = synchronized(settingsLock) {
        requireReady()
        if (password.size !in 8..EncryptedBackupCodec.MAX_PASSWORD_CHARS) {
            throw LedgerBackupException(LedgerBackupFailure.INVALID_PASSWORD, "备份密码需为8至1024个字符。")
        }
        val temporaryPassword = password.copyOf()
        var payload: ByteArray? = null
        try {
            payload = BackupStorage.exportSnapshot(database(), readSettings())
            EncryptedBackupCodec.encrypt(payload, temporaryPassword)
        } catch (failure: BackupCodecException) {
            throw codecFailure(failure)
        } catch (_: Exception) {
            throw LedgerBackupException(LedgerBackupFailure.UNAVAILABLE, "未能生成完整加密备份，请保留原账本后重试。")
        } finally {
            temporaryPassword.fill('\u0000')
            payload?.fill(0)
        }
    }

    fun prepareRestore(encrypted: ByteArray, password: CharArray): PreparedRestore = synchronized(settingsLock) {
        requireReady()
        if (password.isEmpty() || password.size > EncryptedBackupCodec.MAX_PASSWORD_CHARS) {
            throw LedgerBackupException(LedgerBackupFailure.INVALID_PASSWORD, "请输入有效的备份密码。")
        }
        val temporaryPassword = password.copyOf()
        var payload: ByteArray? = null
        try {
            payload = EncryptedBackupCodec.decrypt(encrypted, temporaryPassword)
            val validated = BackupDocumentValidator.validate(payload)
            val entries = validated.document.getJSONObject("tables").getJSONArray("entries")
            var deleted = 0
            var pending = 0
            for (index in 0 until entries.length()) {
                val row = entries.getJSONObject(index)
                if (!row.isNull("deleted_at")) deleted++
                if (row.getLong("needs_review") == 1L) pending++
            }
            PreparedRestore.create(preparedOwner, payload, BackupPreview(
                validated.document.getLong("createdAt"), validated.entryCount, validated.eventCount, deleted, pending))
        } catch (failure: BackupCodecException) {
            throw codecFailure(failure)
        } catch (_: Exception) {
            throw LedgerBackupException(LedgerBackupFailure.INVALID_BACKUP, "备份内容不完整或关联校验失败，原账本未被替换。")
        } finally {
            temporaryPassword.fill('\u0000')
            payload?.fill(0)
        }
    }

    fun restorePrepared(prepared: PreparedRestore) = synchronized(settingsLock) {
        var payload: ByteArray? = null
        var original: ByteArray? = null
        var checkpointWritten = false
        var mutationStarted = false
        try {
            requireReady()
            payload = prepared.consume(preparedOwner)
            // Validation precedes any checkpoint or persistent data mutation, even for forged models.
            val target = try {
                BackupDocumentValidator.validate(payload)
            } catch (_: Exception) {
                throw LedgerBackupException(LedgerBackupFailure.INVALID_BACKUP, "恢复内容校验失败，原账本未被替换。")
            }
            blockCapture(true)
            val db = database()
            original = BackupStorage.exportSnapshot(db, readSettings())
            journal.writeOriginal(original)
            checkpointWritten = true
            mutationStarted = true
            BackupStorage.restoreSnapshot(db, target)
            applySettings(target.settings.copy(autoEnabled = false, screenEnabled = false, screenOcrEnabled = false))
            refresh()
            // No restore is successful while the rollback checkpoint remains.
            try {
                journal.clear()
            } catch (_: Exception) {
                throw LedgerBackupException(LedgerBackupFailure.RECOVERY_REQUIRED, RECOVERY_MESSAGE)
            }
            blockCapture(false)
        } catch (failure: Exception) {
            if (mutationStarted && original != null) {
                try {
                    restoreOriginal(original)
                    initialized = true
                    blockCapture(false)
                } catch (_: Exception) {
                    initialized = false
                    captureBlocked = true
                    throw LedgerBackupException(LedgerBackupFailure.RECOVERY_REQUIRED, RECOVERY_MESSAGE)
                }
                throw LedgerBackupException(LedgerBackupFailure.RESTORE_FAILED,
                    "恢复未完成，已保留覆盖前的账本和设置，请重新检查备份后再试。")
            }
            if (checkpointWritten || pendingOrUncertainJournal()) {
                initialized = false
                captureBlocked = true
                throw LedgerBackupException(LedgerBackupFailure.RECOVERY_REQUIRED, RECOVERY_MESSAGE)
            }
            if (initialized && captureBlocked) blockCapture(false)
            if (failure is LedgerBackupException) throw failure
            throw LedgerBackupException(LedgerBackupFailure.RESTORE_FAILED, "恢复尚未开始，原账本和设置保持完整，请重试。")
        } finally {
            prepared.dispose()
            payload?.fill(0)
            original?.fill(0)
        }
    }

    /** Returns true after recovering an interrupted restore, false when no checkpoint existed. */
    fun retryPendingRestore(): Boolean = synchronized(settingsLock) {
        blockCapture(true)
        try {
            val original = journal.readOriginal()
            if (original == null) {
                refresh()
                initialized = true
                blockCapture(false)
                return@synchronized false
            }
            try {
                restoreOriginal(original)
            } finally {
                original.fill(0)
            }
            initialized = true
            blockCapture(false)
            true
        } catch (_: Exception) {
            initialized = false
            captureBlocked = true
            throw LedgerBackupException(LedgerBackupFailure.RECOVERY_REQUIRED, RECOVERY_MESSAGE)
        }
    }

    private fun restoreOriginal(original: ByteArray) {
        val snapshot = BackupDocumentValidator.validate(original)
        // A hook can fail after clear completed. Re-establish crash protection before rollback.
        if (!journal.exists()) journal.writeOriginal(original)
        BackupStorage.restoreSnapshot(database(), snapshot)
        applySettings(snapshot.settings)
        refresh()
        journal.clear()
    }

    private fun applySettings(settings: LedgerSettings) {
        val editor = preferences.edit().putBoolean("auto_enabled", settings.autoEnabled)
            .putBoolean("wechat_enabled", settings.wechatEnabled).putBoolean("alipay_enabled", settings.alipayEnabled)
            .putLong("monthly_budget_cents", settings.monthlyBudgetCents).putBoolean("screen_enabled", settings.screenEnabled)
            .putBoolean("screen_ocr_enabled", settings.screenOcrEnabled)
        // This is a fresh activation gate on rollback, never a timestamp imported from a backup.
        if (settings.autoEnabled) editor.putLong("automation_enabled_since", System.currentTimeMillis())
        else editor.remove("automation_enabled_since")
        if (!commitPreferences(editor)) throw LedgerBackupException(LedgerBackupFailure.RECOVERY_REQUIRED, RECOVERY_MESSAGE)
        onSettingsApplied(settings)
    }

    private fun requireReady() {
        if (!initialized || captureBlocked) throw LedgerBackupException(LedgerBackupFailure.RECOVERY_REQUIRED, RECOVERY_MESSAGE)
        if (pendingOrUncertainJournal()) {
            initialized = false
            blockCapture(true)
            throw LedgerBackupException(LedgerBackupFailure.RECOVERY_REQUIRED, RECOVERY_MESSAGE)
        }
    }

    private fun pendingOrUncertainJournal(): Boolean = try { journal.exists() } catch (_: Exception) { true }

    private fun blockCapture(blocked: Boolean) {
        if (blocked) captureBlocked = true
        try {
            onCaptureBlocked(blocked)
        } catch (_: Exception) {
            initialized = false
            captureBlocked = true
            throw LedgerBackupException(LedgerBackupFailure.RECOVERY_REQUIRED, RECOVERY_MESSAGE)
        }
        if (!blocked) captureBlocked = false
    }

    private fun codecFailure(failure: BackupCodecException): LedgerBackupException = when (failure.reason) {
        BackupFailure.INVALID_PASSWORD -> LedgerBackupException(LedgerBackupFailure.INVALID_PASSWORD, "请输入有效的备份密码。")
        BackupFailure.CRYPTO_UNAVAILABLE -> LedgerBackupException(LedgerBackupFailure.UNAVAILABLE, "设备暂时无法处理加密备份，请保留原账本和文件后重试。")
        else -> LedgerBackupException(LedgerBackupFailure.INVALID_BACKUP, "密码不正确，或备份文件已损坏、不完整或版本不受支持。")
    }

    companion object {
        private const val RECOVERY_MESSAGE = "恢复检查点尚未安全处理，自动识别已暂停；请重试恢复原账本。"
        fun journalFileName(databaseName: String): String {
            val hash = MessageDigest.getInstance("SHA-256").digest(databaseName.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return "restore-${hash.take(32)}.checkpoint"
        }
    }
}
