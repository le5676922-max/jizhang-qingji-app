package com.example.jizhang.data.backup

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.example.jizhang.data.LedgerSettings
import org.json.JSONArray
import org.json.JSONObject

class BackupStorageException(message: String) : Exception(message)

/** Caller supplies its isolated/production database under the repository mutex and owns its lifetime. */
object BackupStorage {
    private val orderBy = mapOf(
        "entries" to "id ASC",
        "seen_events" to "fingerprint ASC",
        "source_events" to "id ASC",
        "event_aliases" to "fingerprint ASC",
        "merchant_rules" to "provider ASC, merchant_key ASC, type ASC",
        "link_actions" to "id ASC",
        "recognition_diagnostics" to "channel ASC",
    )
    private val deletionOrder = listOf("link_actions", "event_aliases", "source_events", "merchant_rules",
        "recognition_diagnostics", "seen_events", "entries")

    fun exportSnapshot(db: SQLiteDatabase, settings: LedgerSettings): ByteArray {
        requireAvailable(db)
        try {
            db.beginTransaction()
            try {
                val tables = JSONObject()
                BackupDocumentValidator.tableColumns.forEach { (table, allowedColumns) ->
                    val columns = allowedColumns.toTypedArray()
                    val rows = JSONArray()
                    db.query(table, columns, null, null, null, null, orderBy.getValue(table)).use { cursor ->
                        if (cursor.count > 100_000) throw BackupStorageException("账本记录超过当前备份上限，请保留原账本。")
                        while (cursor.moveToNext()) {
                            val row = JSONObject()
                            columns.forEachIndexed { index, column ->
                                val value: Any = when (cursor.getType(index)) {
                                    Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                    Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                                    // Never truncate SQLite REAL values into monetary integers.
                                    else -> throw BackupStorageException("数据库包含不支持的字段类型，请保留原账本。")
                                }
                                row.put(column, value)
                            }
                            rows.put(row)
                        }
                    }
                    tables.put(table, rows)
                }
                val document = JSONObject().put("formatVersion", 2).put("schemaVersion", 3)
                    .put("createdAt", System.currentTimeMillis()).put("tables", tables)
                    .put("settings", JSONObject().put("autoEnabled", settings.autoEnabled)
                        .put("wechatEnabled", settings.wechatEnabled).put("alipayEnabled", settings.alipayEnabled)
                        .put("monthlyBudgetCents", settings.monthlyBudgetCents).put("screenEnabled", settings.screenEnabled)
                        .put("screenOcrEnabled", settings.screenOcrEnabled))
                val payload = document.toString().toByteArray(Charsets.UTF_8)
                try {
                    BackupDocumentValidator.validate(payload)
                } catch (failure: Exception) {
                    payload.fill(0)
                    throw failure
                }
                db.setTransactionSuccessful()
                return payload
            } finally {
                db.endTransaction()
            }
        } catch (failure: BackupValidationException) {
            throw failure
        } catch (failure: BackupStorageException) {
            throw failure
        } catch (_: Exception) {
            // Database exceptions may contain SQL details; expose a fixed message only.
            throw BackupStorageException("未能生成完整备份，请保留原账本后重试。")
        }
    }

    fun restoreSnapshot(db: SQLiteDatabase, validated: ValidatedBackup) {
        requireAvailable(db)
        // JSONObject is mutable. Revalidate a private copy before starting any destructive SQL.
        val verificationBytes = validated.document.toString().toByteArray(Charsets.UTF_8)
        val snapshot = try {
            BackupDocumentValidator.validate(verificationBytes)
        } finally {
            verificationBytes.fill(0)
        }
        val tables = snapshot.document.getJSONObject("tables")
        try {
            db.beginTransaction()
            try {
                deletionOrder.forEach { table -> db.delete(table, null, null) }
                val entries = tables.getJSONArray("entries")
                for (index in 0 until entries.length()) {
                    val row = entries.getJSONObject(index)
                    val values = values("entries", row).apply { putNull("related_entry_id") }
                    db.insertOrThrow("entries", null, values)
                }
                // All IDs now exist, so self-references work irrespective of array order.
                for (index in 0 until entries.length()) {
                    val row = entries.getJSONObject(index)
                    if (!row.isNull("related_entry_id")) {
                        val changed = db.update("entries", ContentValues().apply {
                            put("related_entry_id", row.getLong("related_entry_id"))
                        }, "id = ?", arrayOf(row.getLong("id").toString()))
                        if (changed != 1) throw BackupStorageException("账单关联恢复未完成。")
                    }
                }
                BackupDocumentValidator.tableColumns.keys.filter { it != "entries" }.forEach { table ->
                    val rows = tables.getJSONArray(table)
                    for (index in 0 until rows.length()) db.insertOrThrow(table, null, values(table, rows.getJSONObject(index)))
                }
                db.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                    if (cursor.moveToFirst()) throw BackupStorageException("备份关联完整性检查失败。")
                }
                db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                    if (!cursor.moveToFirst() || cursor.getString(0) != "ok" || cursor.moveToNext()) {
                        throw BackupStorageException("备份数据库完整性检查失败。")
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (_: Exception) {
            throw BackupStorageException("恢复未完成，本次数据库替换已取消。")
        }
    }

    private fun values(table: String, row: JSONObject): ContentValues {
        val columns = BackupDocumentValidator.tableColumns.getValue(table)
        if (row.keys().asSequence().toSet() != columns) throw BackupStorageException("备份字段发生变化，已取消恢复。")
        return ContentValues().apply {
            columns.forEach { column ->
                when (val value = row.get(column)) {
                    JSONObject.NULL -> putNull(column)
                    is String -> put(column, value)
                    is Byte -> put(column, value.toLong())
                    is Short -> put(column, value.toLong())
                    is Int -> put(column, value.toLong())
                    is Long -> put(column, value)
                    else -> throw BackupStorageException("备份字段类型不正确，已取消恢复。")
                }
            }
        }
    }

    private fun requireAvailable(db: SQLiteDatabase) {
        if (!db.isOpen || db.inTransaction()) throw BackupStorageException("数据库当前不可用或正在处理另一事务，请稍后重试。")
    }
}
