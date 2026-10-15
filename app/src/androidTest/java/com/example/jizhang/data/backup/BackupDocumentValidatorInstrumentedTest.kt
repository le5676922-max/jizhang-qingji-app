package com.example.jizhang.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory JSON fixtures only: validation never opens, replaces or clears a database. */
@RunWith(AndroidJUnit4::class)
class BackupDocumentValidatorInstrumentedTest {
    @Test
    fun completeSnapshotPreservesSettingsAliasesAndUnattachedLegacyTombstones() {
        val result = BackupDocumentValidator.validate(bytes(fixture()))
        assertEquals(1, result.entryCount)
        assertEquals(1, result.eventCount)
        assertEquals(420_000L, result.settings.monthlyBudgetCents)
        assertTrue(result.settings.autoEnabled)
        assertTrue(result.settings.screenEnabled)
        assertFalse(result.settings.screenOcrEnabled)
        assertEquals(2, result.document.getJSONObject("tables").getJSONArray("seen_events").length())
    }

    @Test
    fun oldPendingEntriesSoftDeletedRowsAndUnclassifiedOldRefundIncomeAreRetained() {
        val document = fixture()
        table(document, "entries").put(entry(2, 2_000).put("needs_review", 1))
            .put(entry(3, 3_000).put("deleted_at", NOW))
            .put(entry(4, 4_000, "INCOME", "INCOME").put("category", "退款"))
        val result = BackupDocumentValidator.validate(bytes(document))
        assertEquals(4, result.entryCount)
        assertEquals("INCOME", table(result.document, "entries").getJSONObject(3).getString("business_type"))
    }

    @Test
    fun severalSourceEventsMayLinkToOneActualLedgerEntry() {
        val document = fixture()
        val second = copy(table(document, "source_events").getJSONObject(0))
            .put("id", 2).put("event_fingerprint", HASH_C).put("evidence", "ACCESSIBILITY")
        table(document, "source_events").put(second)
        table(document, "seen_events").put(JSONObject().put("fingerprint", HASH_C).put("observed_at", NOW))
        table(document, "event_aliases").put(JSONObject().put("fingerprint", HASH_C).put("event_id", 2))
        assertEquals(2, BackupDocumentValidator.validate(bytes(document)).eventCount)
    }

    @Test
    fun tableColumnAndSettingsWhitelistsRejectUnknownOrMissingFields() {
        expectInvalid(fixture().apply { getJSONObject("tables").put("execute_sql", JSONArray()) })
        expectInvalid(fixture().apply { getJSONObject("tables").remove("seen_events") })
        expectInvalid(fixture().apply { table(this, "entries").getJSONObject(0).put("raw_screen", "private") })
        expectInvalid(fixture().apply { table(this, "entries").getJSONObject(0).remove("deleted_at") })
        expectInvalid(fixture().apply { getJSONObject("settings").put("permission_granted", true) })
        expectInvalid(fixture().apply { getJSONObject("settings").remove("screenEnabled") })
    }

    @Test
    fun fractionalStringExponentialAndNonJsonAmountsCannotBeTruncatedToCents() {
        val serialized = fixture().toString()
        listOf("12.5", "10000.0", "1e4", "+10000", "010000", "\"10000\"", "9223372036854775808")
            .forEach { replacement ->
                val invalid = serialized.replace("\"amount_cents\":10000", "\"amount_cents\":$replacement")
                assertTrue("The fixture must contain the amount token", invalid != serialized)
                expectInvalidBytes(invalid.toByteArray(Charsets.UTF_8))
            }
        expectInvalid(fixture().apply { table(this, "entries").getJSONObject(0).put("amount_cents", 0) })
        expectInvalid(fixture().apply { table(this, "entries").getJSONObject(0).put("amount_cents", 100_000_000_000L) })
        expectInvalid(fixture().apply { table(this, "source_events").getJSONObject(0).put("amount_options", "[12.5]") })
    }

    @Test
    fun flagsEnumsHashesTimesAndStringsAreStrictlyBounded() {
        listOf("needs_review", "edited_by_user").forEach { field ->
            expectInvalid(fixture().apply { table(this, "entries").getJSONObject(0).put(field, 2) })
        }
        expectInvalid(fixture().apply { getJSONObject("settings").put("autoEnabled", "true") })
        expectInvalid(fixture().apply { getJSONObject("settings").put("monthlyBudgetCents", -1) })
        expectInvalid(fixture().apply { table(this, "entries").getJSONObject(0).put("occurred_at", 0) })
        expectInvalid(fixture().apply { table(this, "entries").getJSONObject(0).put("business_type", "ALL") })
        expectInvalid(fixture().apply { table(this, "source_events").getJSONObject(0).put("event_fingerprint", "A".repeat(64)) })
        expectInvalid(fixture().apply { table(this, "entries").getJSONObject(0).put("merchant", "x".repeat(201)) })
        expectInvalid(fixture().apply { table(this, "source_events").getJSONObject(0).put("reasons", JSONArray().put("x".repeat(301)).toString()) })
        expectInvalid(fixture().apply { table(this, "source_events").getJSONObject(0).put("order_hash", HASH_C) })
    }

    @Test
    fun everyTableRejectsDuplicatePrimaryKeys() {
        BackupDocumentValidator.tableColumns.keys.forEach { name ->
            val document = fixture()
            val rows = table(document, name)
            rows.put(copy(rows.getJSONObject(0)))
            expectInvalid(document)
        }
    }

    @Test
    fun missingForeignKeysAndSeenOrAliasRelationsAreRejected() {
        listOf("linked_entry_id", "suggested_entry_id", "related_entry_id").forEach { field ->
            expectInvalid(fixture().apply { table(this, "source_events").getJSONObject(0).put(field, 999) })
        }
        listOf("from_entry_id", "to_entry_id").forEach { field ->
            expectInvalid(fixture().apply { table(this, "link_actions").getJSONObject(0).put(field, 999) })
        }
        expectInvalid(fixture().apply { table(this, "event_aliases").getJSONObject(0).put("event_id", 999) })
        expectInvalid(fixture().apply { table(this, "link_actions").getJSONObject(0).put("event_id", 999) })
        expectInvalid(fixture().apply { table(this, "seen_events").remove(0) })
        expectInvalid(fixture().apply { table(this, "event_aliases").remove(0) })
        expectInvalid(fixture().apply { table(this, "entries").getJSONObject(0).put("related_entry_id", 999) })
    }

    @Test
    fun partialRefundsMayExactlyReachTheOriginalExpenseButCannotExceedIt() {
        val document = fixture()
        table(document, "entries").put(refund(2, 4_000, 1)).put(refund(3, 6_000, 1))
        assertEquals(3, BackupDocumentValidator.validate(bytes(document)).entryCount)
        table(document, "entries").getJSONObject(2).put("amount_cents", 6_001)
        expectInvalid(document)
    }

    @Test
    fun confirmedRefundsRequireAnActiveConfirmedOriginalExpense() {
        expectInvalid(fixture().apply { table(this, "entries").put(refund(2, 1_000, null)) })
        expectInvalid(fixture().apply { table(this, "entries").put(refund(2, 1_000, 2)) })
        expectInvalid(fixture().apply {
            table(this, "entries").getJSONObject(0).put("business_type", "TRANSFER")
            table(this, "entries").put(refund(2, 1_000, 1))
        })
        expectInvalid(fixture().apply {
            table(this, "entries").getJSONObject(0).put("needs_review", 1)
            table(this, "entries").put(refund(2, 1_000, 1))
        })
        val deletedOriginal = fixture().apply {
            table(this, "entries").getJSONObject(0).put("deleted_at", NOW)
            table(this, "entries").put(refund(2, 1_000, 1))
        }
        expectInvalid(deletedOriginal)
        table(deletedOriginal, "entries").getJSONObject(1).put("needs_review", 1)
        assertEquals(2, BackupDocumentValidator.validate(bytes(deletedOriginal)).entryCount)
    }

    @Test
    fun unresolvedOrDeletedRefundsMayRemainUnlinkedWithoutBeingPosted() {
        val document = fixture()
        table(document, "entries").put(refund(2, 1_000, null).put("needs_review", 1))
            .put(refund(3, 2_000, null).put("deleted_at", NOW))
        assertEquals(3, BackupDocumentValidator.validate(bytes(document)).entryCount)
    }

    @Test
    fun manuallyConfirmedRefundKeepsUnknownRawEvidenceAndMatchingOriginalAssociation() {
        val document = fixture()
        table(document, "entries").put(refund(2, 1_000, 1))
        table(document, "source_events").getJSONObject(0).put("business_type", "UNKNOWN")
            .put("direction", "UNKNOWN").put("amount_cents", 1_000).put("linked_entry_id", 2)
            .put("related_entry_id", 1).put("association", "CONFIRMED")
        val result = BackupDocumentValidator.validate(bytes(document))
        assertEquals(2, result.entryCount)
        assertEquals("UNKNOWN", table(result.document, "source_events").getJSONObject(0).getString("business_type"))
        assertEquals("REFUND", table(result.document, "entries").getJSONObject(1).getString("business_type"))
    }

    @Test
    fun unknownRawEvidenceCannotInventAnOriginalDifferentFromItsCanonicalRefund() {
        val mismatched = fixture()
        table(mismatched, "entries").put(refund(2, 1_000, 1)).put(entry(3, 10_000))
        table(mismatched, "source_events").getJSONObject(0).put("business_type", "UNKNOWN")
            .put("linked_entry_id", 2).put("related_entry_id", 3)
        expectInvalid(mismatched)
        expectInvalid(fixture().apply {
            table(this, "source_events").getJSONObject(0).put("business_type", "UNKNOWN").put("related_entry_id", 1)
        })
    }

    @Test
    fun consistentIdentifiersAtTheBoundAreAllowedButAllocatorCeilingIdsAreRejected() {
        val boundary = fixture()
        table(boundary, "entries").getJSONObject(0).put("id", BackupDocumentValidator.MAX_IDENTIFIER_ID)
        table(boundary, "source_events").getJSONObject(0).put("linked_entry_id", BackupDocumentValidator.MAX_IDENTIFIER_ID)
        table(boundary, "link_actions").getJSONObject(0).put("to_entry_id", BackupDocumentValidator.MAX_IDENTIFIER_ID)
        assertEquals(1, BackupDocumentValidator.validate(bytes(boundary)).entryCount)
        val oversized = BackupDocumentValidator.MAX_IDENTIFIER_ID + 1
        listOf(oversized, Long.MAX_VALUE).forEach { invalidId ->
            val document = copy(boundary)
            table(document, "entries").getJSONObject(0).put("id", invalidId)
            table(document, "source_events").getJSONObject(0).put("linked_entry_id", invalidId)
            table(document, "link_actions").getJSONObject(0).put("to_entry_id", invalidId)
            expectInvalid(document)
        }
        val oversizedEvent = fixture()
        table(oversizedEvent, "source_events").getJSONObject(0).put("id", oversized)
        table(oversizedEvent, "event_aliases").getJSONObject(0).put("event_id", oversized)
        table(oversizedEvent, "link_actions").getJSONObject(0).put("event_id", oversized)
        expectInvalid(oversizedEvent)
        expectInvalid(fixture().apply { table(this, "link_actions").getJSONObject(0).put("id", oversized) })
        expectInvalid(fixture().apply { table(this, "link_actions").getJSONObject(0).put("to_entry_id", 0) })
    }

    @Test
    fun invalidEncodingDeepInputOversizeAndTrailingDocumentsFailWithoutLeakingContent() {
        expectInvalidBytes(byteArrayOf(0xC3.toByte(), 0x28))
        expectInvalidBytes(("[".repeat(64) + "0" + "]".repeat(64)).toByteArray())
        expectInvalidBytes(ByteArray(EncryptedBackupCodec.MAX_PAYLOAD_BYTES + 1))
        expectInvalidBytes(bytes(fixture()) + " {}".toByteArray())
        try {
            BackupDocumentValidator.validate("{\"secret\":\"个人账单秘密".toByteArray(Charsets.UTF_8))
            fail("Expected validation failure")
        } catch (failure: BackupValidationException) {
            assertFalse(failure.message.orEmpty().contains("个人账单秘密"))
            assertEquals(null, failure.cause)
        }
    }

    private fun fixture(): JSONObject {
        val tables = JSONObject()
        BackupDocumentValidator.tableColumns.keys.forEach { tables.put(it, JSONArray()) }
        tables.getJSONArray("entries").put(entry(1, 10_000).put("fingerprint", HASH_A)
            .put("source", "WECHAT").put("capture_kind", "NOTIFICATION"))
        tables.getJSONArray("seen_events").put(JSONObject().put("fingerprint", HASH_A).put("observed_at", NOW))
            .put(JSONObject().put("fingerprint", HASH_B).put("observed_at", NOW))
        tables.getJSONArray("source_events").put(JSONObject()
            .put("id", 1).put("event_fingerprint", HASH_A).put("provider", "WECHAT").put("evidence", "NOTIFICATION")
            .put("amount_cents", 10_000).put("direction", "OUTGOING").put("business_type", "EXPENSE")
            .put("merchant", "张三").put("occurred_at", NOW).put("observed_at", NOW).put("category_suggestion", "其他")
            .put("completion", "COMPLETED").put("has_stable_identity", 1).put("user_initiated", 0)
            .put("rule_version", "notification/2").put("amount_options", "[]")
            .put("order_hash", JSONObject.NULL).put("order_namespace", JSONObject.NULL)
            .put("original_order_hash", JSONObject.NULL).put("original_order_namespace", JSONObject.NULL)
            .put("decision", "AUTO").put("reasons", "[]").put("linked_entry_id", 1)
            .put("suggested_entry_id", JSONObject.NULL).put("related_entry_id", JSONObject.NULL).put("association", "CREATED"))
        tables.getJSONArray("event_aliases").put(JSONObject().put("fingerprint", HASH_A).put("event_id", 1))
        tables.getJSONArray("merchant_rules").put(JSONObject().put("provider", "WECHAT").put("merchant_key", "张三")
            .put("type", "EXPENSE").put("category", "其他").put("updated_at", NOW))
        tables.getJSONArray("link_actions").put(JSONObject().put("id", 1).put("event_id", 1)
            .put("from_entry_id", JSONObject.NULL).put("to_entry_id", 1).put("action", "CONFIRM").put("occurred_at", NOW))
        tables.getJSONArray("recognition_diagnostics").put(JSONObject().put("channel", "NOTIFICATION")
            .put("last_success_at", NOW).put("last_failure_at", 0).put("last_failure_reason", "").put("last_duration_ms", 12))
        return JSONObject().put("formatVersion", 2).put("schemaVersion", 3).put("createdAt", NOW + 5_000)
            .put("tables", tables).put("settings", JSONObject().put("autoEnabled", true).put("wechatEnabled", true)
                .put("alipayEnabled", true).put("monthlyBudgetCents", 420_000).put("screenEnabled", true).put("screenOcrEnabled", false))
    }

    private fun entry(id: Long, amount: Long, type: String = "EXPENSE", business: String = "EXPENSE") = JSONObject()
        .put("id", id).put("amount_cents", amount).put("type", type).put("category", "其他")
        .put("merchant", "张三").put("note", "fixture").put("occurred_at", NOW).put("source", "MANUAL")
        .put("fingerprint", JSONObject.NULL).put("needs_review", 0).put("capture_kind", "MANUAL")
        .put("business_type", business).put("related_entry_id", JSONObject.NULL).put("edited_by_user", 0).put("deleted_at", JSONObject.NULL)

    private fun refund(id: Long, amount: Long, original: Long?) = entry(id, amount, "INCOME", "REFUND")
        .put("related_entry_id", original ?: JSONObject.NULL)
    private fun table(document: JSONObject, name: String): JSONArray = document.getJSONObject("tables").getJSONArray(name)
    private fun copy(row: JSONObject) = JSONObject(row.toString())
    private fun bytes(document: JSONObject) = document.toString().toByteArray(Charsets.UTF_8)
    private fun expectInvalid(document: JSONObject) = expectInvalidBytes(bytes(document))
    private fun expectInvalidBytes(payload: ByteArray) {
        try {
            BackupDocumentValidator.validate(payload)
            fail("Expected invalid snapshot")
        } catch (_: BackupValidationException) { }
    }

    companion object {
        private const val NOW = 1_760_000_000_000L
        private val HASH_A = "a".repeat(64)
        private val HASH_B = "b".repeat(64)
        private val HASH_C = "c".repeat(64)
    }
}
