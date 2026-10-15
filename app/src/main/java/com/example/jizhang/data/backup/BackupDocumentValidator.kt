package com.example.jizhang.data.backup

import com.example.jizhang.data.LedgerSettings
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class ValidatedBackup(
    val document: JSONObject,
    val settings: LedgerSettings,
    val entryCount: Int,
    val eventCount: Int,
)

/** The error path contains schema identifiers only, never values or source text. */
class BackupValidationException(val field: String) : Exception("备份内容校验失败：$field。")

/** Validates an entire decrypted snapshot before the repository mutates any persistent data. */
object BackupDocumentValidator {
    const val MAX_IDENTIFIER_ID = 9_007_199_254_740_991L // 2^53 - 1, far below SQLite's allocator ceiling.
    private const val MAX_MONEY = 99_999_999_999L
    private const val MAX_ROWS_PER_TABLE = 100_000
    private const val MAX_TOTAL_ROWS = 200_000
    private const val MAX_JSON_DEPTH = 8
    private val HASH = Regex("[a-f0-9]{64}")
    private val INTEGER_TOKEN = Regex("-?(?:0|[1-9][0-9]*)")
    private val NAMESPACE = Regex("[A-Za-z0-9_./:-]{1,80}")
    private val PROVIDERS = setOf("MANUAL", "WECHAT", "ALIPAY")
    private val ENTRY_TYPES = setOf("EXPENSE", "INCOME")
    private val BUSINESS_TYPES = setOf("EXPENSE", "INCOME", "TRANSFER", "REFUND", "REPAYMENT", "UNKNOWN")
    private val EVIDENCE = setOf("NOTIFICATION", "ACCESSIBILITY", "OCR", "IMPORTED_IMAGE", "SHARED_IMAGE", "LEGACY")
    private val DIRECTIONS = setOf("OUTGOING", "INCOMING", "INTERNAL", "UNKNOWN")
    private val COMPLETIONS = setOf("COMPLETED", "UNCONFIRMED", "FAILED", "PENDING", "CANCELLED")
    private val ASSOCIATIONS = setOf("NONE", "LEGACY", "CREATED", "ORDER", "MANUAL", "CONFIRMED", "DETACHED")
    private val ACTIONS = setOf("AUTO_LINK", "MANUAL_LINK", "UNLINK", "CONFIRM", "IGNORE", "UNDO_IGNORE")
    private val SETTINGS = setOf("autoEnabled", "wechatEnabled", "alipayEnabled", "monthlyBudgetCents", "screenEnabled", "screenOcrEnabled")

    /** Repository restore must also use this fixed order and whitelist, never SQL from the file. */
    val tableColumns: Map<String, Set<String>> = linkedMapOf(
        "entries" to setOf("id", "amount_cents", "type", "category", "merchant", "note", "occurred_at", "source",
            "fingerprint", "needs_review", "capture_kind", "business_type", "related_entry_id", "edited_by_user", "deleted_at"),
        "seen_events" to setOf("fingerprint", "observed_at"),
        "source_events" to setOf("id", "event_fingerprint", "provider", "evidence", "amount_cents", "direction", "business_type",
            "merchant", "occurred_at", "observed_at", "category_suggestion", "completion", "has_stable_identity", "user_initiated",
            "rule_version", "amount_options", "order_hash", "order_namespace", "original_order_hash", "original_order_namespace",
            "decision", "reasons", "linked_entry_id", "suggested_entry_id", "related_entry_id", "association"),
        "event_aliases" to setOf("fingerprint", "event_id"),
        "merchant_rules" to setOf("provider", "merchant_key", "type", "category", "updated_at"),
        "link_actions" to setOf("id", "event_id", "from_entry_id", "to_entry_id", "action", "occurred_at"),
        "recognition_diagnostics" to setOf("channel", "last_success_at", "last_failure_at", "last_failure_reason", "last_duration_ms"),
    )

    fun validate(payload: ByteArray): ValidatedBackup {
        if (payload.isEmpty() || payload.size > EncryptedBackupCodec.MAX_PAYLOAD_BYTES) invalid("文件大小")
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload)).toString()
            guardJson(text, "JSON结构")
            val document = parseJson(text, "JSON结构") as? JSONObject ?: invalid("JSON根对象")
            exactKeys(document, setOf("formatVersion", "schemaVersion", "createdAt", "tables", "settings"), "文件结构")
            if (integer(document, "formatVersion") != 2L || integer(document, "schemaVersion") != 3L) invalid("备份版本")
            positive(document, "createdAt")
            val tables = document.get("tables") as? JSONObject ?: invalid("tables")
            exactKeys(tables, tableColumns.keys, "tables")
            val rows = linkedMapOf<String, List<JSONObject>>()
            var totalRows = 0
            tableColumns.forEach { (table, columns) ->
                val array = tables.get(table) as? JSONArray ?: invalid(table)
                if (array.length() > MAX_ROWS_PER_TABLE) invalid("$table.行数")
                totalRows += array.length()
                if (totalRows > MAX_TOTAL_ROWS) invalid("总行数")
                rows[table] = List(array.length()) { index ->
                    val row = array.get(index) as? JSONObject ?: invalid("$table[$index]")
                    exactKeys(row, columns, "$table[$index].列")
                    row
                }
            }
            val settings = validateSettings(document.get("settings") as? JSONObject ?: invalid("settings"))
            validateRows(rows)
            return ValidatedBackup(document, settings, rows.getValue("entries").size, rows.getValue("source_events").size)
        } catch (failure: BackupValidationException) {
            throw failure
        } catch (_: Exception) {
            // org.json exceptions can contain input excerpts; never propagate those messages.
            invalid("文件结构")
        }
    }

    private fun validateSettings(row: JSONObject): LedgerSettings {
        exactKeys(row, SETTINGS, "settings")
        val budget = integer(row, "monthlyBudgetCents")
        if (budget !in 0..MAX_MONEY) invalid("monthlyBudgetCents")
        return LedgerSettings(boolean(row, "autoEnabled"), boolean(row, "wechatEnabled"), boolean(row, "alipayEnabled"),
            budget, boolean(row, "screenEnabled"), boolean(row, "screenOcrEnabled"))
    }

    private fun validateRows(rows: Map<String, List<JSONObject>>) {
        val entries = linkedMapOf<Long, JSONObject>()
        val entryFingerprints = mutableSetOf<String>()
        rows.getValue("entries").forEach { row ->
            val id = positive(row, "id")
            if (entries.put(id, row) != null) invalid("entries.id重复")
            money(row, "amount_cents")
            val type = enum(row, "type", ENTRY_TYPES)
            val business = enum(row, "business_type", BUSINESS_TYPES)
            enum(row, "source", PROVIDERS)
            enum(row, "capture_kind", setOf("MANUAL", "NOTIFICATION", "SCREEN", "LEGACY"))
            string(row, "category", 80, false); string(row, "merchant", 200); string(row, "note", 2_000)
            positive(row, "occurred_at"); flag(row, "needs_review"); flag(row, "edited_by_user")
            nullablePositive(row, "deleted_at"); nullablePositive(row, "related_entry_id")
            nullableHash(row, "fingerprint")?.let { if (!entryFingerprints.add(it)) invalid("entries.fingerprint重复") }
            if ((business == "EXPENSE" && type != "EXPENSE") ||
                (business in setOf("INCOME", "REFUND") && type != "INCOME")) invalid("entries.业务方向")
        }
        val seen = mutableSetOf<String>()
        rows.getValue("seen_events").forEach { row ->
            if (!seen.add(hash(row, "fingerprint"))) invalid("seen_events.fingerprint重复")
            positive(row, "observed_at")
        }
        if (!seen.containsAll(entryFingerprints)) invalid("entries.已见事件关系")

        val events = linkedMapOf<Long, JSONObject>()
        val eventFingerprints = mutableSetOf<String>()
        rows.getValue("source_events").forEach { row ->
            val id = positive(row, "id")
            if (events.put(id, row) != null) invalid("source_events.id重复")
            val fingerprint = hash(row, "event_fingerprint")
            if (!eventFingerprints.add(fingerprint) || fingerprint !in seen) invalid("source_events.事件身份")
            enum(row, "provider", PROVIDERS); enum(row, "evidence", EVIDENCE)
            money(row, "amount_cents"); enum(row, "direction", DIRECTIONS); enum(row, "business_type", BUSINESS_TYPES)
            string(row, "merchant", 200); string(row, "category_suggestion", 80, false)
            positive(row, "occurred_at"); positive(row, "observed_at")
            enum(row, "completion", COMPLETIONS); enum(row, "decision", setOf("AUTO", "REVIEW", "IGNORE"))
            flag(row, "has_stable_identity"); flag(row, "user_initiated")
            string(row, "rule_version", 80, false); enum(row, "association", ASSOCIATIONS)
            pairedOrder(row, "order_hash", "order_namespace")
            pairedOrder(row, "original_order_hash", "original_order_namespace")
            arrayText(row, "amount_options", 512, 16).let { options ->
                for (index in 0 until options.length()) moneyValue(options.get(index), "amount_options")
            }
            arrayText(row, "reasons", 12_000, 32).let { reasons ->
                for (index in 0 until reasons.length()) boundedString(reasons.get(index), "reasons", 300, false)
            }
            listOf("linked_entry_id", "suggested_entry_id", "related_entry_id").forEach { field ->
                reference(row, field, entries.keys)
            }
            nullablePositive(row, "related_entry_id")?.let { original ->
                val linked = nullablePositive(row, "linked_entry_id")?.let(entries::getValue)
                // Manual confirmation changes the canonical record without rewriting raw evidence.
                val confirmedRefund = linked != null && linked.getString("business_type") == "REFUND" &&
                    nullablePositive(linked, "related_entry_id") == original
                if (!isExpense(entries.getValue(original)) ||
                    row.getString("business_type") != "REFUND" && !confirmedRefund) invalid("source_events.退款关联")
            }
        }
        val aliases = mutableMapOf<String, Long>()
        rows.getValue("event_aliases").forEach { row ->
            val fingerprint = hash(row, "fingerprint")
            val eventId = positive(row, "event_id")
            if (aliases.put(fingerprint, eventId) != null || fingerprint !in seen || eventId !in events) invalid("event_aliases.关系")
        }
        events.forEach { (id, row) ->
            if (aliases[row.getString("event_fingerprint")] != id) invalid("source_events.主身份别名")
        }
        val rules = mutableSetOf<Triple<String, String, String>>()
        rows.getValue("merchant_rules").forEach { row ->
            val key = Triple(enum(row, "provider", PROVIDERS), string(row, "merchant_key", 200, false), enum(row, "type", ENTRY_TYPES))
            if (!rules.add(key)) invalid("merchant_rules.主键重复")
            string(row, "category", 80, false); positive(row, "updated_at")
        }
        val actionIds = mutableSetOf<Long>()
        rows.getValue("link_actions").forEach { row ->
            if (!actionIds.add(positive(row, "id"))) invalid("link_actions.id重复")
            if (positive(row, "event_id") !in events) invalid("link_actions.event_id")
            reference(row, "from_entry_id", entries.keys); reference(row, "to_entry_id", entries.keys)
            enum(row, "action", ACTIONS); positive(row, "occurred_at")
        }
        val channels = mutableSetOf<String>()
        rows.getValue("recognition_diagnostics").forEach { row ->
            if (!channels.add(enum(row, "channel", EVIDENCE))) invalid("recognition_diagnostics.channel重复")
            nonnegative(row, "last_success_at"); nonnegative(row, "last_failure_at"); nonnegative(row, "last_duration_ms")
            string(row, "last_failure_reason", 300)
        }
        validateRefunds(entries)
    }

    private fun validateRefunds(entries: Map<Long, JSONObject>) {
        val totals = mutableMapOf<Long, Long>()
        entries.forEach { (id, row) ->
            val originalId = nullablePositive(row, "related_entry_id")
            val refund = row.getString("business_type") == "REFUND"
            val activeConfirmed = row.isNull("deleted_at") && integer(row, "needs_review") == 0L
            if (originalId != null) {
                val original = entries[originalId] ?: invalid("entries.related_entry_id")
                if (!refund || originalId == id || !isExpense(original)) invalid("entries.退款原始支出")
                if (activeConfirmed && !original.isNull("deleted_at")) invalid("entries.原支出已删除")
            }
            if (!refund || !activeConfirmed) return@forEach
            val confirmedOriginalId = originalId ?: invalid("entries.正式退款缺少原支出")
            val original = entries[confirmedOriginalId] ?: invalid("entries.正式退款缺少原支出")
            if (integer(original, "needs_review") != 0L) invalid("entries.原支出未确认")
            val previous = totals[confirmedOriginalId] ?: 0L
            val amount = money(row, "amount_cents")
            val originalAmount = money(original, "amount_cents")
            if (amount > originalAmount || previous > originalAmount - amount) invalid("entries.累计退款金额")
            totals[confirmedOriginalId] = previous + amount
        }
    }

    private fun isExpense(row: JSONObject): Boolean = row.getString("type") == "EXPENSE" && row.getString("business_type") == "EXPENSE"
    private fun reference(row: JSONObject, key: String, ids: Set<Long>) {
        nullablePositive(row, key)?.let { if (it !in ids) invalid(key) }
    }
    private fun pairedOrder(row: JSONObject, hashKey: String, namespaceKey: String) {
        val value = nullableHash(row, hashKey)
        val namespace = if (row.isNull(namespaceKey)) null else string(row, namespaceKey, 80, false)
        // A parser may know the identifier kind while the actual order ID is absent.
        // A hash, however, must always retain its namespace to prevent false strong links.
        if (value != null && namespace == null || namespace != null && !NAMESPACE.matches(namespace)) invalid(namespaceKey)
    }
    private fun arrayText(row: JSONObject, key: String, maxLength: Int, maxItems: Int): JSONArray {
        val text = string(row, key, maxLength, false)
        guardJson(text, key)
        val result = parseJson(text, key) as? JSONArray ?: invalid(key)
        if (result.length() > maxItems) invalid(key)
        return result
    }
    private fun exactKeys(row: JSONObject, expected: Set<String>, field: String) {
        if (row.keys().asSequence().toSet() != expected) invalid(field)
    }
    private fun integer(row: JSONObject, key: String): Long = integerValue(row.get(key), key)
    private fun integerValue(value: Any, field: String): Long = when (value) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> invalid(field)
    }
    private fun positive(row: JSONObject, key: String): Long = integer(row, key).also {
        if (it <= 0 || (key == "id" || key.endsWith("_id")) && it > MAX_IDENTIFIER_ID) invalid(key)
    }
    private fun nonnegative(row: JSONObject, key: String): Long = integer(row, key).also { if (it < 0) invalid(key) }
    private fun nullablePositive(row: JSONObject, key: String): Long? = if (row.isNull(key)) null else positive(row, key)
    private fun flag(row: JSONObject, key: String): Long = integer(row, key).also { if (it !in 0..1) invalid(key) }
    private fun money(row: JSONObject, key: String): Long = moneyValue(row.get(key), key)
    private fun moneyValue(value: Any, field: String): Long = integerValue(value, field).also { if (it !in 1..MAX_MONEY) invalid(field) }
    private fun boolean(row: JSONObject, key: String): Boolean = row.get(key) as? Boolean ?: invalid(key)
    private fun string(row: JSONObject, key: String, max: Int, blank: Boolean = true): String = boundedString(row.get(key), key, max, blank)
    private fun boundedString(value: Any, field: String, max: Int, blank: Boolean): String =
        (value as? String ?: invalid(field)).also { if (it.length > max || !blank && it.isBlank()) invalid(field) }
    private fun enum(row: JSONObject, key: String, values: Set<String>): String = string(row, key, 80, false).also { if (it !in values) invalid(key) }
    private fun hash(row: JSONObject, key: String): String = string(row, key, 64, false).also { if (!HASH.matches(it)) invalid(key) }
    private fun nullableHash(row: JSONObject, key: String): String? = if (row.isNull(key)) null else hash(row, key)

    private fun parseJson(text: String, field: String): Any {
        val parser = JSONTokener(text)
        val result = parser.nextValue()
        if (parser.nextClean() != '\u0000') invalid(field)
        return result
    }

    /** Rejects deep input and org.json's permissive non-JSON number syntaxes before parsing. */
    private fun guardJson(text: String, field: String) {
        var index = 0
        val nesting = ArrayDeque<Char>()
        while (index < text.length) {
            when (val character = text[index]) {
                ' ', '\t', '\r', '\n', ':', ',' -> index++
                '{', '[' -> {
                    nesting.addLast(character)
                    if (nesting.size > MAX_JSON_DEPTH) invalid(field)
                    index++
                }
                '}', ']' -> {
                    val opening = nesting.removeLastOrNull() ?: invalid(field)
                    if (opening != if (character == '}') '{' else '[') invalid(field)
                    index++
                }
                '"' -> {
                    index++
                    var closed = false
                    while (index < text.length) {
                        val value = text[index++]
                        if (value == '"') { closed = true; break }
                        if (value < ' ') invalid(field)
                        if (value == '\\') {
                            if (index >= text.length) invalid(field)
                            val escape = text[index++]
                            if (escape == 'u') {
                                if (index + 4 > text.length || text.substring(index, index + 4).any { it.digitToIntOrNull(16) == null }) invalid(field)
                                index += 4
                            } else if (escape !in "\"\\/bfnrt") invalid(field)
                        }
                    }
                    if (!closed) invalid(field)
                }
                else -> {
                    val start = index
                    while (index < text.length && text[index] !in " \t\r\n,:{}[]") index++
                    val token = text.substring(start, index)
                    if (token !in setOf("true", "false", "null") && !INTEGER_TOKEN.matches(token)) invalid(field)
                }
            }
        }
        if (nesting.isNotEmpty()) invalid(field)
    }
    private fun invalid(field: String): Nothing = throw BackupValidationException(field)
}
