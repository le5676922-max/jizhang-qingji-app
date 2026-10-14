package com.example.jizhang.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.example.jizhang.domain.*
import com.example.jizhang.recognition.*
import org.json.JSONArray
import java.security.MessageDigest

internal fun LedgerEntry.databaseValues(includeFingerprint: Boolean = true) = ContentValues().apply {
    put("amount_cents", amountCents); put("type", type.name); put("category", category.trim())
    put("merchant", merchant.trim()); put("note", note.trim()); put("occurred_at", occurredAt)
    put("source", source.name); put("needs_review", if (needsReview) 1 else 0)
    put("business_type", businessType.name); put("edited_by_user", if (editedByUser) 1 else 0)
    if (relatedEntryId == null) putNull("related_entry_id") else put("related_entry_id", relatedEntryId)
    if (includeFingerprint) { if (fingerprint == null) putNull("fingerprint") else put("fingerprint", fingerprint) }
}

internal fun Cursor.nullableLong(column: String) = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }
internal fun Cursor.nullableString(column: String) = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
internal fun Cursor.text(column: String): String = getString(getColumnIndexOrThrow(column))
internal fun Cursor.number(column: String): Long = getLong(getColumnIndexOrThrow(column))

internal fun Cursor.ledgerRecord() = LedgerEntry(
    id = number("id"), amountCents = number("amount_cents"), type = EntryType.valueOf(text("type")),
    category = text("category"), merchant = text("merchant"), note = text("note"), occurredAt = number("occurred_at"),
    source = EntrySource.valueOf(text("source")), fingerprint = nullableString("fingerprint"),
    needsReview = number("needs_review") == 1L, businessType = BusinessType.valueOf(text("business_type")),
    relatedEntryId = nullableLong("related_entry_id"), editedByUser = number("edited_by_user") == 1L,
)

internal fun identityHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun providerOrderHash(provider: EntrySource, namespace: String?, order: String?): String? =
    if (namespace.isNullOrBlank() || order.isNullOrBlank()) null
    else identityHash("provider-order|${provider.name}|$namespace|$order")

internal fun encodeStrings(values: List<String>): String = JSONArray(values).toString()
internal fun decodeStrings(value: String): List<String> = JSONArray(value).let { array ->
    List(array.length()) { array.getString(it) }
}

internal fun readRecognitionEvents(db: SQLiteDatabase): List<RecognitionEvent> = db.rawQuery(
    "SELECT s.*, e.deleted_at AS linked_deleted_at, (SELECT a.action FROM link_actions a WHERE a.event_id=s.id ORDER BY a.id DESC LIMIT 1) AS last_user_action FROM source_events s LEFT JOIN entries e ON s.linked_entry_id=e.id ORDER BY s.observed_at DESC,s.id DESC", null,
).use { cursor -> buildList {
    while (cursor.moveToNext()) {
        val options = JSONArray(cursor.text("amount_options"))
        val reasons = decodeStrings(cursor.text("reasons"))
        val candidate = TransactionCandidate(
            provider = EntrySource.valueOf(cursor.text("provider")), eventFingerprint = cursor.text("event_fingerprint"),
            amountCents = cursor.number("amount_cents"), direction = FundsDirection.valueOf(cursor.text("direction")),
            businessType = BusinessType.valueOf(cursor.text("business_type")), merchant = cursor.text("merchant"),
            occurredAt = cursor.number("occurred_at"), observedAt = cursor.number("observed_at"),
            categorySuggestion = cursor.text("category_suggestion"), completion = CompletionState.valueOf(cursor.text("completion")),
            evidence = EvidenceType.valueOf(cursor.text("evidence")), hasStableIdentity = cursor.number("has_stable_identity") == 1L,
            userInitiated = cursor.number("user_initiated") == 1L, ruleVersion = cursor.text("rule_version"), reasons = reasons,
            amountOptions = List(options.length()) { options.getLong(it) },
            orderNamespace = cursor.nullableString("order_namespace"), originalOrderNamespace = cursor.nullableString("original_order_namespace"),
        )
        add(RecognitionEvent(cursor.number("id"), candidate, RecognitionDecision.valueOf(cursor.text("decision")), reasons,
            cursor.nullableLong("linked_entry_id"), cursor.nullableLong("suggested_entry_id"), cursor.nullableLong("related_entry_id"),
            cursor.text("association"), cursor.nullableLong("linked_deleted_at") != null,
            cursor.nullableString("order_hash"), cursor.nullableString("original_order_hash"),
            cursor.nullableString("last_user_action") == "IGNORE"))
    }
} }

internal fun TransactionCandidate.eventValues(result: DecisionResult, orderHash: String?, originalHash: String?,
                                             linkedId: Long? = null, suggestedId: Long? = null, relatedId: Long? = null,
                                             association: String = "NONE") = ContentValues().apply {
    put("event_fingerprint", eventFingerprint); put("provider", provider.name); put("evidence", evidence.name)
    put("amount_cents", amountCents); put("direction", direction.name); put("business_type", businessType.name)
    put("merchant", merchant); put("occurred_at", occurredAt); put("observed_at", observedAt)
    put("category_suggestion", categorySuggestion); put("completion", completion.name)
    put("has_stable_identity", if (hasStableIdentity) 1 else 0); put("user_initiated", if (userInitiated) 1 else 0)
    put("rule_version", ruleVersion); put("amount_options", JSONArray(amountOptions).toString())
    put("order_hash", orderHash); put("order_namespace", orderNamespace)
    put("original_order_hash", originalHash); put("original_order_namespace", originalOrderNamespace)
    put("decision", result.decision.name); put("reasons", encodeStrings(result.reasons))
    if (linkedId == null) putNull("linked_entry_id") else put("linked_entry_id", linkedId)
    if (suggestedId == null) putNull("suggested_entry_id") else put("suggested_entry_id", suggestedId)
    if (relatedId == null) putNull("related_entry_id") else put("related_entry_id", relatedId)
    put("association", association)
}
