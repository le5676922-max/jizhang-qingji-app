package com.example.jizhang.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.jizhang.domain.EntrySource
import com.example.jizhang.domain.BusinessType
import com.example.jizhang.domain.EntryType
import com.example.jizhang.domain.LedgerEntry
import com.example.jizhang.domain.Money
import com.example.jizhang.domain.ScreenPayment
import com.example.jizhang.recognition.*
import com.example.jizhang.data.backup.LedgerBackupCoordinator
import com.example.jizhang.data.backup.PreparedRestore
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The only persisted notification data is a hash and the extracted ledger fields. */
class LedgerRepository private constructor(
    context: Context,
    databaseName: String = "ledger.db",
    preferencesName: String = "ledger_settings",
) {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val database = LedgerDatabase(context, databaseName)
    private val mutex = Mutex()
    private val settingsLock = Any()
    private val errorLock = Any()
    @Volatile private var configurationGeneration = 0L
    @Volatile private var captureBlocked = true
    @Volatile private var activationSince = preferences.getLong("automation_enabled_since", Long.MAX_VALUE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableEntries = MutableStateFlow<List<LedgerEntry>>(emptyList())
    private val mutableSettings = MutableStateFlow(readSettings())
    private val mutableStorageError = MutableStateFlow<String?>(null)
    private val mutableCaptureError = MutableStateFlow(preferences.getString("capture_error", null))
    private val mutableRecognitionEvents = MutableStateFlow<List<RecognitionEvent>>(emptyList())
    private val mutableDeletedEntries = MutableStateFlow<List<LedgerEntry>>(emptyList())
    private val mutableMerchantRules = MutableStateFlow<List<MerchantCategoryRule>>(emptyList())
    private val mutableDiagnostics = MutableStateFlow<List<RecognitionDiagnostic>>(emptyList())
    private val diagnosticLock = Any()
    private val diagnosticThrottle = mutableMapOf<EvidenceType, Pair<String,Long>>()
    private val mutableRestoreBlocked = MutableStateFlow(true)
    private val backup = LedgerBackupCoordinator(context.applicationContext, databaseName,
        database = { database.writableDatabase }, preferences = preferences, settingsLock = settingsLock,
        readSettings = { readSettings() }, onSettingsApplied = { restored ->
            mutableSettings.value = restored
            activationSince = preferences.getLong("automation_enabled_since", Long.MAX_VALUE)
            configurationGeneration++
        }, onCaptureBlocked = { blocked ->
            captureBlocked = blocked
            mutableRestoreBlocked.value = blocked
            configurationGeneration++
        }, refresh = { refreshEntries() })
    private val initialization = scope.launch {
        runCatching { mutex.withLock { backup.retryPendingRestore() } }
            .onFailure { mutableStorageError.value = "账本恢复尚未完成，请重试恢复后再进行记账。" }
    }

    val entries: StateFlow<List<LedgerEntry>> = mutableEntries.asStateFlow()
    val settings: StateFlow<LedgerSettings> = mutableSettings.asStateFlow()
    val storageError: StateFlow<String?> = mutableStorageError.asStateFlow()
    val captureError: StateFlow<String?> = mutableCaptureError.asStateFlow()
    val recognitionEvents: StateFlow<List<RecognitionEvent>> = mutableRecognitionEvents.asStateFlow()
    val deletedEntries: StateFlow<List<LedgerEntry>> = mutableDeletedEntries.asStateFlow()
    val merchantRules: StateFlow<List<MerchantCategoryRule>> = mutableMerchantRules.asStateFlow()
    val diagnostics: StateFlow<List<RecognitionDiagnostic>> = mutableDiagnostics.asStateFlow()
    val restoreBlocked: StateFlow<Boolean> = mutableRestoreBlocked.asStateFlow()

    /** Used to exclude notifications that predate explicit opt-in. */
    fun automationEnabledSince(): Long = if (captureBlocked || !mutableSettings.value.autoEnabled) Long.MAX_VALUE else activationSince

    fun updateSettings(settings: LedgerSettings) {
        require(settings.monthlyBudgetCents in 0..MAX_AMOUNT_CENTS) { "预算金额超出范围" }
        synchronized(settingsLock) {
            require(!captureBlocked) { "账本恢复尚未完成，请先重试恢复" }
            val previous=mutableSettings.value
            if(previous.autoEnabled!=settings.autoEnabled || previous.screenEnabled!=settings.screenEnabled ||
                previous.screenOcrEnabled!=settings.screenOcrEnabled || previous.wechatEnabled!=settings.wechatEnabled ||
                previous.alipayEnabled!=settings.alipayEnabled) configurationGeneration++
            val editor = preferences.edit()
                .putBoolean("auto_enabled", settings.autoEnabled)
                .putBoolean("wechat_enabled", settings.wechatEnabled)
                .putBoolean("alipay_enabled", settings.alipayEnabled)
                .putLong("monthly_budget_cents", settings.monthlyBudgetCents)
                .putBoolean("screen_enabled", settings.screenEnabled)
                .putBoolean("screen_ocr_enabled", settings.screenOcrEnabled)
            if (settings.autoEnabled && !mutableSettings.value.autoEnabled) {
                activationSince = System.currentTimeMillis()
                editor.putLong("automation_enabled_since", activationSince)
            }
            if (!settings.autoEnabled) { editor.remove("automation_enabled_since"); activationSince = Long.MAX_VALUE }
            editor.apply()
            mutableSettings.value = settings
        }
    }

    fun recognitionConfigurationGeneration():Long = configurationGeneration

    /** A lost-notification warning stays visible across successful captures and app restarts. */
    fun reportCaptureError(message: String) {
        require(message.isNotBlank() && message.length <= 300)
        synchronized(errorLock) {
            preferences.edit().putString("capture_error", message).apply()
            mutableCaptureError.value = message
        }
    }

    /** Called only after the user has checked the ledger and explicitly dismissed the warning. */
    fun acknowledgeCaptureError() {
        synchronized(errorLock) {
            preferences.edit().remove("capture_error").apply()
            mutableCaptureError.value = null
        }
    }

    /** Fixed, non-sensitive outcomes only; repeated failures are throttled to reduce writes. */
    fun reportRecognitionDiagnostic(channel:EvidenceType, message:String, success:Boolean, durationMs:Long=0) {
        require(message.length<=250 && durationMs in 0..600_000)
        val now=System.currentTimeMillis()
        synchronized(diagnosticLock) {
            val prior=diagnosticThrottle[channel]
            if(!success && prior?.first==message && now-prior.second<30_000) return
            diagnosticThrottle[channel]=message to now
        }
        scope.launch {
            initialization.join()
            runCatching { mutex.withLock {
                if (captureBlocked) return@withLock
                val db=database.writableDatabase
                db.execSQL("INSERT OR IGNORE INTO recognition_diagnostics(channel) VALUES (?)",arrayOf(channel.name))
                val values=ContentValues().apply {
                    put("last_duration_ms",durationMs)
                    if(success) put("last_success_at",now) else { put("last_failure_at",now);put("last_failure_reason",message) }
                }
                db.update("recognition_diagnostics",values,"channel=?",arrayOf(channel.name))
                refreshEntries()
            } }.onFailure { mutableStorageError.value="识别状态暂时无法保存，请检查设备存储空间。" }
        }
    }

    /** Manual insertion is atomic, including optional category memory. */
    suspend fun add(entry: LedgerEntry, rememberCategory: Boolean = false): Boolean = writeTransaction { db ->
        validate(entry)
        validateFinancialRecord(db, entry)
        val hash = entry.fingerprint
        if (hash != null && seen(db, hash)) false else {
            val entryId = db.insertOrThrow("entries", null, entry.databaseValues().apply { put("capture_kind", if (hash == null) "MANUAL" else "LEGACY") })
            if (hash != null) db.execSQL("INSERT OR IGNORE INTO seen_events(fingerprint,observed_at) VALUES (?,?)", arrayOf<Any>(hash, entry.occurredAt))
            if (rememberCategory) rememberMerchant(db, entry)
            refreshReviewSuggestionsForEntry(db, entryId)
            true
        }
    }

    /** Compatibility adapter; automated evidence has no separate ledger write path. */
    internal suspend fun addNotification(entry: LedgerEntry, alternateFingerprints: List<String>): Boolean {
        validate(entry)
        require(entry.source != EntrySource.MANUAL && entry.fingerprint != null) { "通知来源或身份无效" }
        val result = ingest(TransactionCandidate(provider = entry.source, eventFingerprint = entry.fingerprint!!,
            alternateFingerprints = alternateFingerprints, amountCents = entry.amountCents,
            direction = if (entry.type == EntryType.INCOME) FundsDirection.INCOMING else FundsDirection.OUTGOING,
            businessType = entry.businessType, merchant = entry.merchant, occurredAt = entry.occurredAt,
            observedAt = entry.occurredAt, categorySuggestion = entry.category, completion = CompletionState.COMPLETED,
            evidence = EvidenceType.NOTIFICATION, hasStableIdentity = true, ruleVersion = "legacy-notification-adapter-v2",
            reasons = if (entry.needsReview) listOf("通知信息需要人工核对") else emptyList()))
        return !result.disabled && !result.duplicate && result.eventId != null
    }

    suspend fun addScreenPayment(screen: ScreenPayment, capturedAt: Long, userInitiated: Boolean = false): ScreenSaveResult {
        val evidence = when {
            userInitiated -> EvidenceType.IMPORTED_IMAGE
            screen.payment.needsReview && screen.hasStableIdentity -> EvidenceType.OCR
            else -> EvidenceType.ACCESSIBILITY
        }
        val result = ingest(CandidateFactory.fromScreen(screen, capturedAt, evidence, userInitiated))
        return when {
            result.disabled -> ScreenSaveResult.SOURCE_DISABLED
            result.duplicate -> ScreenSaveResult.DUPLICATE
            result.decision == RecognitionDecision.AUTO -> ScreenSaveResult.INSERTED
            else -> ScreenSaveResult.NEEDS_REVIEW
        }
    }

    /** Facts, decisions, aliases and financial records commit under one mutex/transaction. */
    suspend fun ingest(candidate: TransactionCandidate, stillValid: () -> Boolean = { true }): RecognitionSaveResult = withContext(Dispatchers.IO) {
        validateCandidate(candidate)
        initialization.join()
        mutex.withLock {
            if (!candidateAllowed(candidate) || !stillValid()) return@withLock disabledCapture()
            val db = database.writableDatabase
            var result: RecognitionSaveResult
            db.beginTransaction()
            try {
                val aliases = (listOf(candidate.eventFingerprint) + candidate.alternateFingerprints).distinct()
                val priorEvents = readRecognitionEvents(db)
                val exactIds = aliases.mapNotNull { hash -> db.query("event_aliases", arrayOf("event_id"),
                    "fingerprint=?", arrayOf(hash), null, null, null).use { if (it.moveToFirst()) it.number("event_id") else null } }.distinct()
                require(exactIds.size <= 1) { "来源事件身份冲突，请人工核对" }
                val exact = exactIds.singleOrNull()?.let { id -> priorEvents.first { it.id == id } }
                if (exact != null && (exact.userIgnored ||
                    !EventStateMachine.canTransition(exact.candidate.completion, candidate.completion, exact.userIgnored))) {
                    rememberAliases(db, exact.id, aliases, candidate.observedAt)
                    result = existingResult(exact)
                } else if (exact == null && (aliases.any { seen(db, it) } || candidate.legacyFingerprints.any { hash -> legacySeen(db, hash) })) {
                    aliases.forEach { hash -> db.execSQL("INSERT OR IGNORE INTO seen_events(fingerprint,observed_at) VALUES (?,?)", arrayOf<Any>(hash, candidate.observedAt)) }
                    result = RecognitionSaveResult(RecognitionDecision.IGNORE, duplicate = true,
                        reasons = listOf("旧版已处理或删除的事件不会重新创建账单"))
                } else {
                    val records = readAllEntries(db)
                    val actual = categorySuggestion(db, candidate.copy(merchant = safeCapturedMerchant(candidate.merchant)))
                    val orderHash = providerOrderHash(actual.provider, actual.orderNamespace, actual.orderId) ?: exact?.orderHash
                    val originalHash = providerOrderHash(actual.provider, actual.originalOrderNamespace, actual.originalOrderId) ?: exact?.originalOrderHash
                    val merged = if (exact == null) actual else actual.copy(eventFingerprint = exact.candidate.eventFingerprint,
                        orderNamespace = actual.orderNamespace ?: exact.candidate.orderNamespace,
                        originalOrderNamespace = actual.originalOrderNamespace ?: exact.candidate.originalOrderNamespace)
                    val otherEvents = priorEvents.filter { it.id != exact?.id }
                    val match = TransactionMatcher.match(merged, orderHash, otherEvents.map { it.matcherSnapshot() }, records, originalHash)
                    val deletedSuggestion = deletedIdentityUpgradeSuggestion(merged, orderHash, otherEvents, records)
                    val previousLinked = exact?.linkedEntryId?.let { id -> records.firstOrNull { it.entry.id == id } }
                    val exactIdentityConflict = exact != null &&
                        (exact.orderHash != null && orderHash != null && exact.orderHash != orderHash ||
                         merged.businessType == BusinessType.REFUND && exact.originalOrderHash != null && originalHash != null &&
                            (exact.originalOrderHash != originalHash || exact.candidate.originalOrderNamespace != merged.originalOrderNamespace))
                    val exactFinancialConflict = exactIdentityConflict || exact != null && previousLinked != null &&
                        (!sameCandidateFacts(exact.candidate, merged) || !canonicalFactsAgree(merged, previousLinked.entry))
                    val refundOriginal = if (merged.businessType == BusinessType.REFUND) resolveRefundOriginal(merged, originalHash, otherEvents, records) else null
                    val refundCanonical = previousLinked?.entry ?: match.strongEntryId?.let { id -> records.firstOrNull { it.entry.id == id }?.entry }
                    val linkedRefundConflict = merged.businessType == BusinessType.REFUND && refundOriginal != null &&
                        refundCanonical?.relatedEntryId != null && refundCanonical.relatedEntryId != refundOriginal
                    val refundValid = refundOriginal != null && refundCanFit(db, refundOriginal, merged.amountCents, previousLinked?.entry?.id ?: 0L)
                    var decision = DecisionEngine.evaluate(merged, duplicateConflict = match.identityConflict ||
                        match.suggestedEntryIds.isNotEmpty() || deletedSuggestion != null || exactFinancialConflict || linkedRefundConflict, refundLinked = refundValid)
                    var linkedId: Long? = null
                    var suggestedId: Long? = match.suggestedEntryIds.firstOrNull() ?: deletedSuggestion ?: match.strongEntryId.takeIf { linkedRefundConflict }
                    var association = "NONE"
                    if (exact?.association == "DETACHED") {
                        decision = DecisionResult(RecognitionDecision.REVIEW, listOf("已人工撤销关联，需要重新核对"))
                        suggestedId = exact.suggestedEntryId ?: suggestedId
                        association = "DETACHED"
                    } else if (exact != null && previousLinked != null) {
                        linkedId = previousLinked.entry.id
                        association = exact.association
                        decision = when {
                            previousLinked.deleted -> DecisionResult(RecognitionDecision.IGNORE, listOf("账单已删除，不会由新凭据恢复"))
                            exactFinancialConflict || linkedRefundConflict -> DecisionResult(RecognitionDecision.REVIEW, listOf("更新凭据与已关联账单存在冲突，请修改账单或核对来源"))
                            else -> DecisionResult(exact.decision, exact.reasons)
                        }
                    } else if (match.deletedOrderSuppressed || match.userIgnoredOrderSuppressed) {
                        decision = DecisionResult(RecognitionDecision.IGNORE, match.reasons)
                        if (match.deletedOrderSuppressed) {
                            linkedId = otherEvents.firstOrNull { it.orderHash == orderHash && it.candidate.provider == merged.provider &&
                                it.linkedEntryDeleted && it.candidate.businessType == merged.businessType }?.linkedEntryId
                            association = if (linkedId == null) "NONE" else "ORDER"
                        }
                    } else if (match.strongEntryId != null && merged.completion == CompletionState.COMPLETED &&
                        !linkedRefundConflict && merged.reasons.none { it == "订单标识存在冲突" } && decision.decision != RecognitionDecision.IGNORE) {
                        linkedId = match.strongEntryId
                        association = "ORDER"
                        decision = DecisionResult(RecognitionDecision.AUTO, match.reasons)
                    } else if (decision.decision == RecognitionDecision.AUTO) {
                        val entry = candidateEntry(merged, refundOriginal).let { record ->
                            if (record.businessType == BusinessType.REFUND) record.copy(category = entrySnapshot(db, refundOriginal!!)!!.entry.category) else record
                        }
                        validateFinancialRecord(db, entry)
                        linkedId = db.insertOrThrow("entries", null, entry.databaseValues().apply { put("capture_kind", captureKind(merged.evidence)) })
                        association = "CREATED"
                    }
                    val values = merged.eventValues(decision, orderHash, originalHash, linkedId, suggestedId,
                        relatedId = refundOriginal ?: exact?.relatedEntryId, association = association)
                    val eventId = if (exact == null) db.insertOrThrow("source_events", null, values) else {
                        db.update("source_events", values, "id=?", arrayOf(exact.id.toString())); exact.id
                    }
                    rememberAliases(db, eventId, aliases, candidate.observedAt)
                    if (association == "ORDER" && linkedId != null && exact?.linkedEntryId != linkedId) recordAction(db, eventId, exact?.linkedEntryId, linkedId, "AUTO_LINK")
                    if (association == "CREATED" && linkedId != null) refreshReviewSuggestionsForEntry(db, linkedId)
                    result = RecognitionSaveResult(decision.decision, eventId, linkedId,
                        duplicate = exact != null || association == "ORDER", reasons = decision.reasons)
                }
                if (!candidateAllowed(candidate) || !stillValid()) return@withLock disabledCapture()
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            refreshAfterCommit()
            result
        }
    }

    suspend fun confirmEvent(eventId: Long, entry: LedgerEntry, rememberCategory: Boolean = false,
                             recordSeparately: Boolean = false, confirmCompleted: Boolean = false,
                             expectedFingerprint: String? = null) = writeTransaction { db ->
        val event = checkedEvent(db, eventId, expectedFingerprint)
        require(!event.userIgnored && event.decision == RecognitionDecision.REVIEW) { "来源已处理，请刷新后重试" }
        require(event.candidate.completion == CompletionState.COMPLETED ||
            event.candidate.completion == CompletionState.UNCONFIRMED && confirmCompleted) { "请核对交易确已完成" }
        val linked = event.linkedEntryId?.let { entrySnapshot(db, it) }
        require(linked == null || !linked.deleted) { "关联账单已删除，请先恢复账单" }
        val placeholder = linked ?: ignoredLegacyPlaceholder(db, event)
        val record = entry.copy(id = placeholder?.entry?.id ?: 0L, source = placeholder?.entry?.source ?: event.candidate.provider,
            fingerprint = placeholder?.entry?.fingerprint ?: event.candidate.eventFingerprint, needsReview = false, editedByUser = true)
        validate(record)
        val match = TransactionMatcher.match(event.candidate, event.orderHash,
            readRecognitionEvents(db).filter { it.id != eventId }.map { it.matcherSnapshot() }, readAllEntries(db), event.originalOrderHash)
        val currentId = placeholder?.entry?.id
        val otherSuggestions = match.suggestedEntryIds.any { it != currentId }
        val duplicateRisk = event.suggestedEntryId != null && event.suggestedEntryId != currentId ||
            match.strongEntryId != null && match.strongEntryId != currentId || otherSuggestions ||
            match.identityConflict && currentId == null || match.deletedOrderSuppressed && currentId == null ||
            match.userIgnoredOrderSuppressed || event.association == "DETACHED" && currentId == null
        require(!duplicateRisk || recordSeparately) { "可能是重复交易，请关联已有账单或明确确认独立记录" }
        validateFinancialRecord(db, record)
        val entryId = if (placeholder == null) db.insertOrThrow("entries", null, record.databaseValues().apply { put("capture_kind", captureKind(event.candidate.evidence)) }) else {
            db.update("entries", record.databaseValues(includeFingerprint = false).apply { remove("source"); putNull("deleted_at") }, "id=?", arrayOf(record.id.toString())); record.id
        }
        db.update("source_events", ContentValues().apply {
            put("decision", "AUTO"); put("linked_entry_id", entryId); putNull("suggested_entry_id")
            put("association", "CONFIRMED"); put("reasons", encodeStrings(listOf("用户已核对并确认账单")))
            if (record.relatedEntryId == null) putNull("related_entry_id") else put("related_entry_id", record.relatedEntryId)
        }, "id=?", arrayOf(eventId.toString()))
        recordAction(db, eventId, event.linkedEntryId, entryId, "CONFIRM")
        if (rememberCategory) rememberMerchant(db, record)
        refreshReviewSuggestionsForEntry(db, entryId)
        Unit
    }

    suspend fun linkEvent(eventId: Long, entryId: Long, expectedFingerprint: String? = null) = writeTransaction { db ->
        val event = checkedEvent(db, eventId, expectedFingerprint)
        val target = entrySnapshot(db, entryId)
        val previous = event.linkedEntryId?.let { entrySnapshot(db, it) }
        val legacyPending = previous != null && isLegacyPending(event, previous)
        require(!event.userIgnored && (event.linkedEntryId == null || legacyPending) && event.decision == RecognitionDecision.REVIEW) { "来源已处理，请刷新后重试" }
        require(target != null && !target.deleted && !target.entry.needsReview) { "关联账单已不存在或未确认" }
        require(event.candidate.completion == CompletionState.COMPLETED) { "请先确认交易已完成" }
        require(event.candidate.amountOptions.distinct().size <= 1 && event.candidate.amountCents == target.entry.amountCents) { "金额不一致，请先核对金额" }
        require(event.candidate.direction == FundsDirection.UNKNOWN || event.candidate.direction == entryDirection(target.entry)) { "资金方向与账单不一致" }
        require(event.candidate.businessType == BusinessType.UNKNOWN || event.candidate.businessType == target.entry.businessType) { "业务类型与账单不一致" }
        require(target.entry.businessType != BusinessType.UNKNOWN) { "请先核对账单业务类型" }
        if (legacyPending) db.update("entries", ContentValues().apply { put("deleted_at", System.currentTimeMillis()) }, "id=?", arrayOf(previous!!.entry.id.toString()))
        db.update("source_events", ContentValues().apply { put("linked_entry_id", entryId); putNull("suggested_entry_id")
            put("decision", "AUTO"); put("association", "MANUAL"); put("reasons", encodeStrings(listOf("用户已确认该来源对应已有账单")))
            if (target.entry.relatedEntryId == null) putNull("related_entry_id") else put("related_entry_id", target.entry.relatedEntryId)
        }, "id=?", arrayOf(eventId.toString()))
        recordAction(db, eventId, event.linkedEntryId, entryId, "MANUAL_LINK")
        Unit
    }

    suspend fun ignoreEvent(eventId: Long, expectedFingerprint: String? = null) = writeTransaction { db ->
        val event = checkedEvent(db, eventId, expectedFingerprint)
        val linked = event.linkedEntryId?.let { entrySnapshot(db, it) }
        require(linked == null || linked.entry.needsReview) { "已入账来源请修改或删除账单" }
        require(!event.userIgnored) { "来源已被忽略" }
        val legacyPending = linked != null && isLegacyPending(event, linked)
        if (legacyPending) db.update("entries", ContentValues().apply { put("deleted_at", System.currentTimeMillis()) }, "id=?", arrayOf(linked!!.entry.id.toString()))
        db.update("source_events", ContentValues().apply { put("decision", "IGNORE"); put("reasons", encodeStrings(listOf("用户已忽略此来源")))
            if (legacyPending) { putNull("linked_entry_id"); put("association", "NONE") }
        }, "id=?", arrayOf(eventId.toString()))
        recordAction(db, eventId, event.linkedEntryId, if (legacyPending) null else event.linkedEntryId, "IGNORE")
        Unit
    }

    suspend fun undoIgnoreEvent(eventId: Long, expectedFingerprint: String? = null) = writeTransaction { db ->
        val event = checkedEvent(db, eventId, expectedFingerprint)
        require(event.userIgnored) { "这条来源没有人工忽略记录" }
        db.update("source_events", ContentValues().apply { put("decision", "REVIEW"); put("reasons", encodeStrings(listOf("用户恢复来源，请重新核对"))) }, "id=?", arrayOf(eventId.toString()))
        recordAction(db, eventId, event.linkedEntryId, event.linkedEntryId, "UNDO_IGNORE")
        Unit
    }

    suspend fun unlinkEvent(eventId: Long, expectedFingerprint: String? = null) = writeTransaction { db ->
        val event = checkedEvent(db, eventId, expectedFingerprint)
        require(event.association in setOf("ORDER", "MANUAL") && event.linkedEntryId != null) { "此来源不能单独撤销，请修改账单" }
        db.update("source_events", ContentValues().apply { putNull("linked_entry_id"); put("suggested_entry_id", event.linkedEntryId)
            put("decision", "REVIEW"); put("association", "DETACHED"); put("reasons", encodeStrings(listOf("已人工撤销关联，需要重新核对")))
        }, "id=?", arrayOf(eventId.toString()))
        recordAction(db, eventId, event.linkedEntryId, null, "UNLINK")
        Unit
    }

    suspend fun update(entry: LedgerEntry, rememberCategory: Boolean = false) = writeTransaction { db ->
        validate(entry)
        val existing = entrySnapshot(db, entry.id)
        require(existing != null && !existing.deleted) { "账单已不存在" }
        val record = entry.copy(source = existing.entry.source, fingerprint = existing.entry.fingerprint, editedByUser = true)
        validateFinancialRecord(db, record)
        db.update("entries", record.databaseValues(includeFingerprint = false).apply { remove("source") }, "id=?", arrayOf(entry.id.toString()))
        if (rememberCategory) rememberMerchant(db, record)
        refreshReviewSuggestionsForEntry(db, record.id)
        Unit
    }

    suspend fun delete(id: Long) = writeTransaction { db ->
        val existing = entrySnapshot(db, id)
        require(existing != null && !existing.deleted) { "账单已不存在" }
        db.update("entries", ContentValues().apply { put("deleted_at", System.currentTimeMillis()) }, "id=?", arrayOf(id.toString()))
        suspendRefunds(db, id)
        Unit
    }

    suspend fun restoreDeleted(id: Long) = writeTransaction { db ->
        val existing = entrySnapshot(db, id)
        require(existing != null && existing.deleted) { "已删除账单不存在" }
        // Refunds require a live original; expense restoration also rechecks its aggregate.
        validateFinancialRecord(db, existing.entry)
        db.update("entries", ContentValues().apply { putNull("deleted_at") }, "id=?", arrayOf(id.toString()))
        readRecognitionEvents(db).filter { it.linkedEntryId == id && !it.userIgnored && it.decision == RecognitionDecision.IGNORE }.forEach { event ->
            val agrees = !existing.entry.needsReview && event.candidate.completion == CompletionState.COMPLETED &&
                canonicalFactsAgree(event.candidate, existing.entry)
            db.update("source_events", ContentValues().apply {
                put("decision", if (agrees) "AUTO" else "REVIEW")
                put("reasons", encodeStrings(listOf(if (agrees) "用户已恢复账单，来源关联保留" else "账单已恢复，来源事实仍需核对")))
            }, "id=?", arrayOf(event.id.toString()))
        }
        if (existing.entry.businessType == BusinessType.EXPENSE) restoreSuspendedRefunds(db, id)
        refreshReviewSuggestionsForEntry(db, id)
        Unit
    }

    suspend fun deleteMerchantRule(source: EntrySource, merchantKey: String, type: EntryType) = writeTransaction { db ->
        db.delete("merchant_rules", "provider=? AND merchant_key=? AND type=?", arrayOf(source.name, normalizeMerchant(merchantKey), type.name))
        Unit
    }

    private fun suspendRefunds(db: SQLiteDatabase, originalId: Long) {
        val events = readRecognitionEvents(db)
        readAllEntries(db).filter { !it.deleted && !it.entry.needsReview && it.entry.businessType == BusinessType.REFUND &&
            it.entry.relatedEntryId == originalId }.forEach { refund ->
            db.update("entries", ContentValues().apply { put("needs_review", 1) }, "id=?", arrayOf(refund.entry.id.toString()))
            val linkedEvents = events.filter { it.linkedEntryId == refund.entry.id }
            if (linkedEvents.isEmpty()) {
                // Manual refunds also need a visible review item; the note is never a machine marker.
                val record = refund.entry
                val candidate = TransactionCandidate(provider = record.source, eventFingerprint = identityHash("refund-original-review|${record.id}"),
                    amountCents = record.amountCents, direction = FundsDirection.INCOMING, businessType = BusinessType.REFUND,
                    merchant = record.merchant, occurredAt = record.occurredAt, categorySuggestion = record.category,
                    completion = CompletionState.COMPLETED, evidence = EvidenceType.LEGACY, ruleVersion = "refund-original-review-v2")
                val decision = DecisionResult(RecognitionDecision.REVIEW, listOf("原消费已删除，退款需要重新核对"))
                val eventId = db.insertOrThrow("source_events", null, candidate.eventValues(decision, null, null,
                    linkedId = record.id, relatedId = originalId, association = "LEGACY"))
                rememberAliases(db, eventId, listOf(candidate.eventFingerprint), candidate.observedAt)
            } else linkedEvents.filterNot { it.userIgnored }.forEach { event ->
                db.update("source_events", ContentValues().apply { put("decision", "REVIEW");
                    put("reasons", encodeStrings(listOf("原消费已删除，退款需要重新核对"))) }, "id=?", arrayOf(event.id.toString()))
            }
        }
    }

    private fun restoreSuspendedRefunds(db: SQLiteDatabase, originalId: Long) {
        readRecognitionEvents(db).filter { it.relatedEntryId == originalId &&
            it.reasons == listOf("原消费已删除，退款需要重新核对") && !it.userIgnored }.forEach { event ->
            val refund = event.linkedEntryId?.let { entrySnapshot(db, it) }
            if (refund != null && !refund.deleted && refund.entry.businessType == BusinessType.REFUND &&
                refund.entry.relatedEntryId == originalId && refundCanFit(db, originalId, refund.entry.amountCents, refund.entry.id)) {
                db.update("entries", ContentValues().apply { put("needs_review", 0) }, "id=?", arrayOf(refund.entry.id.toString()))
                db.update("source_events", ContentValues().apply { put("decision", "AUTO");
                    put("reasons", encodeStrings(listOf("原消费已恢复，原已确认退款恢复统计"))) }, "id=?", arrayOf(event.id.toString()))
            }
        }
    }

    private fun validateFinancialRecord(db: SQLiteDatabase, entry: LedgerEntry) {
        validate(entry)
        require(entry.businessType != BusinessType.UNKNOWN || entry.needsReview) { "请确认账单业务类型" }
        require(when (entry.businessType) {
            BusinessType.EXPENSE -> entry.type == EntryType.EXPENSE
            BusinessType.INCOME, BusinessType.REFUND -> entry.type == EntryType.INCOME
            else -> true
        }) { "资金方向与业务类型不一致" }
        require(entry.businessType == BusinessType.REFUND || entry.relatedEntryId == null) { "仅退款可以关联原消费" }
        if (entry.businessType == BusinessType.REFUND && entry.relatedEntryId != null) {
            val original = entrySnapshot(db, entry.relatedEntryId)
            require(entry.relatedEntryId != entry.id && original != null && original.entry.businessType == BusinessType.EXPENSE &&
                original.entry.type == EntryType.EXPENSE) { "退款关联必须是另一笔原消费" }
        }
        if (entry.businessType == BusinessType.REFUND && !entry.needsReview) {
            val original = entry.relatedEntryId
            require(original != null && original != entry.id && refundCanFit(db, original, entry.amountCents, entry.id)) { "原消费不可用或累计退款超过原消费金额" }
        }
        if (entry.id > 0) {
            val refunded = confirmedRefundSum(db, entry.id, 0)
            if (refunded > 0) require(entry.businessType == BusinessType.EXPENSE && entry.type == EntryType.EXPENSE &&
                !entry.needsReview && entry.amountCents >= refunded) { "原消费变更不能破坏已关联退款" }
        }
    }

    private fun refundCanFit(db: SQLiteDatabase, originalId: Long, amount: Long, excludedRefundId: Long): Boolean {
        val original = entrySnapshot(db, originalId) ?: return false
        if (original.deleted || original.entry.needsReview || original.entry.businessType != BusinessType.EXPENSE ||
            original.entry.type != EntryType.EXPENSE) return false
        return amount > 0 && amount <= original.entry.amountCents - confirmedRefundSum(db, originalId, excludedRefundId)
    }

    private fun confirmedRefundSum(db: SQLiteDatabase, originalId: Long, excludedRefundId: Long): Long =
        db.query("entries", arrayOf("amount_cents"), "related_entry_id=? AND id<>? AND business_type='REFUND' AND type='INCOME' AND needs_review=0 AND deleted_at IS NULL",
            arrayOf(originalId.toString(), excludedRefundId.toString()), null, null, null).use { cursor ->
            var sum = 0L
            while (cursor.moveToNext()) sum = (sum + cursor.number("amount_cents")).coerceAtMost(MAX_AMOUNT_CENTS + 1)
            sum
        }

    private fun resolveRefundOriginal(candidate: TransactionCandidate, originalHash: String?, events: List<RecognitionEvent>,
                                      entries: List<MatcherEntrySnapshot>): Long? {
        if (originalHash == null || candidate.originalOrderNamespace.isNullOrBlank()) return null
        val ids = events.filter { it.candidate.provider == candidate.provider && it.orderHash == originalHash &&
            it.candidate.orderNamespace == candidate.originalOrderNamespace && !it.userIgnored }.mapNotNull { it.linkedEntryId }.distinct()
        return ids.filter { id -> entries.any { !it.deleted && it.entry.id == id && !it.entry.needsReview &&
            it.entry.businessType == BusinessType.EXPENSE && it.entry.type == EntryType.EXPENSE } }.singleOrNull()
    }

    /** A time-only deleted receipt gaining an order number is a review suggestion, never a permanent amount merge. */
    private fun deletedIdentityUpgradeSuggestion(candidate: TransactionCandidate, orderHash: String?, events: List<RecognitionEvent>,
                                                 entries: List<MatcherEntrySnapshot>): Long? {
        if (candidate.completion != CompletionState.COMPLETED) return null
        return entries.filter { it.deleted }.firstOrNull { snapshot ->
            val entry = snapshot.entry
            val knownDifferentOrder = orderHash != null && events.any { event -> event.linkedEntryId == entry.id &&
                event.candidate.provider == candidate.provider && event.orderHash != null && event.orderHash != orderHash &&
                event.candidate.orderNamespace == candidate.orderNamespace && event.candidate.hasStableIdentity &&
                event.candidate.evidence in setOf(EvidenceType.NOTIFICATION, EvidenceType.ACCESSIBILITY) }
            !knownDifferentOrder && entry.source == candidate.provider && entry.amountCents == candidate.amountCents &&
                entryDirection(entry) == candidate.direction && entry.businessType == candidate.businessType &&
                maxOf(entry.occurredAt, candidate.occurredAt) - minOf(entry.occurredAt, candidate.occurredAt) <= 120_000 &&
                (entry.merchant.isBlank() || candidate.merchant.isBlank() || normalizeMerchant(entry.merchant) == normalizeMerchant(candidate.merchant))
        }?.entry?.id
    }

    /** Re-score only review facts related to this changed row; never scan each pending item against the entire ledger. */
    private fun refreshReviewSuggestionsForEntry(db: SQLiteDatabase, entryId: Long) {
        val target = entrySnapshot(db, entryId) ?: return
        if (target.deleted || target.entry.needsReview) return
        val targetEvents = readRecognitionEvents(db, "s.linked_entry_id=?", arrayOf(entryId.toString()))
        val time = target.entry.occurredAt
        val reviews = readRecognitionEvents(db,
            "s.decision='REVIEW' AND s.linked_entry_id IS NULL AND (s.suggested_entry_id=? OR " +
                "(s.amount_cents=? AND s.occurred_at BETWEEN ? AND ?) OR " +
                "s.order_hash IN (SELECT order_hash FROM source_events WHERE linked_entry_id=? AND order_hash IS NOT NULL) OR " +
                "s.original_order_hash IN (SELECT order_hash FROM source_events WHERE linked_entry_id=? AND order_hash IS NOT NULL))",
            arrayOf(entryId.toString(), target.entry.amountCents.toString(), (time - 120_000).coerceAtLeast(1).toString(),
                (time + 120_000).coerceAtLeast(time).toString(), entryId.toString(), entryId.toString()))
        val suggestionReason = "发现可能对应的已有账单，请核对关联或独立记录"
        reviews.filterNot { it.userIgnored }.forEach { event ->
            val match = TransactionMatcher.match(event.candidate, event.orderHash, targetEvents.map { it.matcherSnapshot() },
                listOf(target), event.originalOrderHash)
            val matchesTarget = match.strongEntryId == entryId || entryId in match.suggestedEntryIds
            val suggested = when {
                matchesTarget -> entryId
                event.suggestedEntryId == entryId -> null
                else -> event.suggestedEntryId
            }
            var related = event.relatedEntryId
            if (event.candidate.businessType == BusinessType.REFUND && event.originalOrderHash != null &&
                event.candidate.originalOrderNamespace != null && target.entry.businessType == BusinessType.EXPENSE) {
                val originalEvents = readRecognitionEvents(db, "s.provider=? AND s.order_namespace=? AND s.order_hash=? AND s.linked_entry_id IS NOT NULL",
                    arrayOf(event.candidate.provider.name, event.candidate.originalOrderNamespace, event.originalOrderHash))
                val originals = originalEvents.mapNotNull { it.linkedEntryId }.distinct().mapNotNull { entrySnapshot(db, it) }
                if (resolveRefundOriginal(event.candidate, event.originalOrderHash, originalEvents, originals) == entryId) related = entryId
            }
            val reasons = event.reasons.filterNot { it == suggestionReason ||
                suggested == null && it == "可能与已有交易重复，需要确认关联" }.toMutableList()
            if (suggested != null) reasons.add(suggestionReason)
            if (reasons.isEmpty()) reasons.add("来源信息发生变化，请重新核对")
            db.update("source_events", ContentValues().apply {
                if (suggested == null) putNull("suggested_entry_id") else put("suggested_entry_id", suggested)
                if (related == null) putNull("related_entry_id") else put("related_entry_id", related)
                put("reasons", encodeStrings(reasons.distinct().take(20)))
            }, "id=?", arrayOf(event.id.toString()))
        }
    }

    private fun candidateEntry(candidate: TransactionCandidate, relatedId: Long?) = LedgerEntry(
        amountCents = candidate.amountCents, type = if (candidate.direction == FundsDirection.INCOMING) EntryType.INCOME else EntryType.EXPENSE,
        category = candidate.categorySuggestion, merchant = candidate.merchant, occurredAt = candidate.occurredAt,
        source = candidate.provider, fingerprint = candidate.eventFingerprint, businessType = candidate.businessType,
        relatedEntryId = if (candidate.businessType == BusinessType.REFUND) relatedId else null)
    private fun sameCandidateFacts(first: TransactionCandidate, second: TransactionCandidate) =
        first.amountCents == second.amountCents && first.direction == second.direction && first.businessType == second.businessType
    private fun canonicalFactsAgree(candidate: TransactionCandidate, entry: LedgerEntry) = candidate.amountCents == entry.amountCents &&
        candidate.direction == entryDirection(entry) && candidate.businessType == entry.businessType
    private fun entryDirection(entry: LedgerEntry) = if (entry.type == EntryType.INCOME) FundsDirection.INCOMING else FundsDirection.OUTGOING
    private fun RecognitionEvent.matcherSnapshot() = MatcherEventSnapshot(candidate, orderHash, linkedEntryId, userIgnored, originalOrderHash)
    private fun captureKind(evidence: EvidenceType) = if (evidence == EvidenceType.NOTIFICATION) "NOTIFICATION" else "SCREEN"
    private fun existingResult(event: RecognitionEvent) = RecognitionSaveResult(event.decision, event.id, event.linkedEntryId, duplicate = true,
        reasons = listOf("同一来源事件已经处理，保留原有决定和人工修改"))
    private fun disabledCapture() = RecognitionSaveResult(RecognitionDecision.IGNORE, disabled = true,
        reasons = listOf("来源已关闭或页面已切换，本次结果未保存"))

    private fun checkedEvent(db: SQLiteDatabase, eventId: Long, expectedFingerprint: String?): RecognitionEvent {
        val event = readRecognitionEvents(db).firstOrNull { it.id == eventId }
        require(event != null && (expectedFingerprint == null || expectedFingerprint == event.candidate.eventFingerprint)) { "识别来源已变化，请刷新后重试" }
        return event
    }

    private fun isLegacyPending(event: RecognitionEvent, snapshot: MatcherEntrySnapshot): Boolean =
        !snapshot.deleted && snapshot.entry.needsReview && event.candidate.evidence == EvidenceType.LEGACY &&
            event.candidate.ruleVersion == "legacy/1"

    /** A legacy ignored row still owns its UNIQUE fingerprint; only explicit confirmation reuses it. */
    private fun ignoredLegacyPlaceholder(db: SQLiteDatabase, event: RecognitionEvent): MatcherEntrySnapshot? {
        if (event.candidate.evidence != EvidenceType.LEGACY || event.candidate.ruleVersion != "legacy/1") return null
        return db.query("link_actions", arrayOf("from_entry_id"),
            "event_id=? AND action IN ('IGNORE','MANUAL_LINK') AND from_entry_id IS NOT NULL",
            arrayOf(event.id.toString()), null, null, "id DESC").use { cursor ->
            var placeholder: MatcherEntrySnapshot? = null
            while (cursor.moveToNext() && placeholder == null) {
                placeholder = entrySnapshot(db, cursor.number("from_entry_id"))?.takeIf { it.deleted && it.entry.needsReview &&
                    it.entry.fingerprint == event.candidate.eventFingerprint }
            }
            placeholder
        }
    }

    private fun recordAction(db: SQLiteDatabase, eventId: Long, fromId: Long?, toId: Long?, action: String) {
        db.insertOrThrow("link_actions", null, ContentValues().apply { put("event_id", eventId); put("action", action)
            put("occurred_at", System.currentTimeMillis()); if (fromId == null) putNull("from_entry_id") else put("from_entry_id", fromId)
            if (toId == null) putNull("to_entry_id") else put("to_entry_id", toId) })
    }
    private fun entrySnapshot(db: SQLiteDatabase, id: Long): MatcherEntrySnapshot? = db.query("entries", null, "id=?",
        arrayOf(id.toString()), null, null, null).use { if (it.moveToFirst()) MatcherEntrySnapshot(it.ledgerRecord(), it.nullableLong("deleted_at") != null) else null }
    private fun readAllEntries(db: SQLiteDatabase): List<MatcherEntrySnapshot> = db.query("entries", null, null, null, null, null, "id").use { cursor ->
        buildList { while (cursor.moveToNext()) add(MatcherEntrySnapshot(cursor.ledgerRecord(), cursor.nullableLong("deleted_at") != null)) } }
    private fun seen(db: SQLiteDatabase, hash: String) = db.query("seen_events", arrayOf("fingerprint"), "fingerprint=?", arrayOf(hash), null, null, null, "1").use { it.moveToFirst() }
    private fun hasEventAlias(db: SQLiteDatabase, hash: String) = db.query("event_aliases", arrayOf("fingerprint"), "fingerprint=?", arrayOf(hash), null, null, null, "1").use { it.moveToFirst() }
    private fun legacySeen(db: SQLiteDatabase, hash: String): Boolean {
        if (!seen(db, hash)) return false
        return db.rawQuery("SELECT s.evidence FROM event_aliases a JOIN source_events s ON s.id=a.event_id WHERE a.fingerprint=?",
            arrayOf(hash)).use { !it.moveToFirst() || it.text("evidence") == EvidenceType.LEGACY.name }
    }
    private fun rememberAliases(db: SQLiteDatabase, eventId: Long, fingerprints: List<String>, observedAt: Long) {
        fingerprints.forEach { hash ->
            db.execSQL("INSERT OR IGNORE INTO event_aliases(fingerprint,event_id) VALUES (?,?)", arrayOf<Any>(hash, eventId))
            db.execSQL("INSERT OR IGNORE INTO seen_events(fingerprint,observed_at) VALUES (?,?)", arrayOf<Any>(hash, observedAt))
        }
    }

    private fun normalizeMerchant(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
    private fun safeCapturedMerchant(value: String) = value.trim().take(80).takeUnless { Regex("[0-9]{7,}|密码|验证码|银行卡号").containsMatchIn(it) }.orEmpty()
    private fun rememberMerchant(db: SQLiteDatabase, entry: LedgerEntry) {
        require(entry.businessType in setOf(BusinessType.EXPENSE, BusinessType.INCOME) && entry.merchant.isNotBlank() && !entry.needsReview) { "只能记住已确认消费或收入的商户分类" }
        db.insertWithOnConflict("merchant_rules", null, ContentValues().apply { put("provider", entry.source.name)
            put("merchant_key", normalizeMerchant(entry.merchant)); put("type", entry.type.name); put("category", entry.category.trim())
            put("updated_at", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    private fun categorySuggestion(db: SQLiteDatabase, candidate: TransactionCandidate): TransactionCandidate {
        if (candidate.businessType !in setOf(BusinessType.EXPENSE, BusinessType.INCOME) || candidate.merchant.isBlank()) return candidate
        val type = if (candidate.businessType == BusinessType.EXPENSE) EntryType.EXPENSE else EntryType.INCOME
        val category = db.query("merchant_rules", arrayOf("category"), "provider=? AND merchant_key=? AND type=?",
            arrayOf(candidate.provider.name, normalizeMerchant(candidate.merchant), type.name), null, null, null).use { if (it.moveToFirst()) it.text("category") else null }
        return if (category == null) candidate else candidate.copy(categorySuggestion = category)
    }

    private fun candidateAllowed(candidate: TransactionCandidate): Boolean {
        if (captureBlocked) return false
        if (candidate.userInitiated && candidate.evidence in setOf(EvidenceType.IMPORTED_IMAGE, EvidenceType.SHARED_IMAGE)) return true
        val current = settings.value
        if (candidate.provider == EntrySource.WECHAT && !current.wechatEnabled ||
            candidate.provider == EntrySource.ALIPAY && !current.alipayEnabled || candidate.provider == EntrySource.MANUAL) return false
        return when (candidate.evidence) {
            EvidenceType.NOTIFICATION -> current.autoEnabled && candidate.observedAt >= automationEnabledSince()
            EvidenceType.ACCESSIBILITY -> current.screenEnabled
            EvidenceType.OCR -> current.screenEnabled && current.screenOcrEnabled
            else -> false
        }
    }
    private fun validateCandidate(candidate: TransactionCandidate) {
        require(candidate.amountCents in 1..MAX_AMOUNT_CENTS && candidate.occurredAt > 0 && candidate.observedAt > 0) { "交易金额或时间无效" }
        val digest = Regex("[a-f0-9]{64}")
        require(digest.matches(candidate.eventFingerprint)) { "来源事件编号无效" }
        require(candidate.alternateFingerprints.size <= 2 && candidate.alternateFingerprints.all { digest.matches(it) })
        require(candidate.legacyFingerprints.size <= 2 && candidate.legacyFingerprints.all { digest.matches(it) })
        require(candidate.merchant.length <= 200 && candidate.categorySuggestion.isNotBlank() && candidate.categorySuggestion.length <= 80)
        require(candidate.ruleVersion.isNotBlank() && candidate.ruleVersion.length <= 80)
        require(candidate.reasons.size <= 20 && candidate.reasons.all { it.length <= 250 })
        require(candidate.amountOptions.size <= 8 && candidate.amountOptions.all { it in 1..MAX_AMOUNT_CENTS })
        require(listOfNotNull(candidate.orderId, candidate.originalOrderId).all { it.length in 8..80 && it.matches(Regex("[A-Za-z0-9_-]+")) })
        require(listOfNotNull(candidate.orderNamespace, candidate.originalOrderNamespace).all { it.length <= 60 && it.matches(Regex("[a-z0-9_/-]+")) })
    }
    private suspend fun <T> writeTransaction(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        initialization.join()
        mutex.withLock {
            require(!captureBlocked) { "备份恢复尚未完成，请先重试恢复" }
            val db = database.writableDatabase
            db.beginTransaction()
            val result = try { val result = block(db); db.setTransactionSuccessful(); result } finally { db.endTransaction() }
            refreshAfterCommit()
            result
        }
    }

    suspend fun createEncryptedBackup(password: CharArray): ByteArray = withContext(Dispatchers.IO) {
        initialization.join()
        mutex.withLock { require(!captureBlocked) { "账本恢复或读取尚未完成，请先重试恢复" }; backup.createEncryptedBackup(password) }
    }

    suspend fun prepareRestore(bytes: ByteArray, password: CharArray): PreparedRestore = withContext(Dispatchers.IO) {
        initialization.join()
        mutex.withLock { require(!captureBlocked) { "账本恢复或读取尚未完成，请先重试恢复" }; backup.prepareRestore(bytes, password) }
    }

    suspend fun restorePrepared(prepared: PreparedRestore) = withContext(Dispatchers.IO) {
        initialization.join()
        mutex.withLock { require(!captureBlocked) { "账本恢复或读取尚未完成，请先重试恢复" }; backup.restorePrepared(prepared) }
    }

    suspend fun retryPendingRestore(): Boolean = withContext(Dispatchers.IO) {
        initialization.join()
        mutex.withLock { backup.retryPendingRestore() }
    }

    /** UTF-8 BOM helps spreadsheet programs open Chinese columns correctly. */
    suspend fun exportCsv(): String = withContext(Dispatchers.IO) {
        initialization.join()
        mutex.withLock {
            require(!captureBlocked) { "账本恢复尚未完成，请先重试恢复" }
            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            buildString {
                append('\uFEFF')
                append("日期,类型,金额（元）,分类,商家,备注,来源,待核对,经济类型,关联原消费ID\r\n")
                readEntries().forEach { entry ->
                    val fields = listOf(
                        formatter.format(Date(entry.occurredAt)),
                        if (entry.type == EntryType.EXPENSE) "支出" else "收入",
                        "${entry.amountCents / 100}.${(entry.amountCents % 100).toString().padStart(2, '0')}",
                        entry.category,
                        entry.merchant,
                        entry.note,
                        when (entry.source) {
                            EntrySource.MANUAL -> "手动"
                            EntrySource.WECHAT -> "微信"
                            EntrySource.ALIPAY -> "支付宝"
                        },
                        if (entry.needsReview) "是" else "否",
                        entry.businessType.name,
                        entry.relatedEntryId?.toString().orEmpty(),
                    )
                    append(fields.joinToString(",") { csvCell(it) })
                    append("\r\n")
                }
            }
        }
    }

    private fun readSettings() = LedgerSettings(
        autoEnabled = preferences.getBoolean("auto_enabled", false),
        wechatEnabled = preferences.getBoolean("wechat_enabled", true),
        alipayEnabled = preferences.getBoolean("alipay_enabled", true),
        screenEnabled = preferences.getBoolean("screen_enabled", false),
        screenOcrEnabled = preferences.getBoolean("screen_ocr_enabled", false),
        monthlyBudgetCents = preferences.getLong("monthly_budget_cents", 300_000)
            .coerceIn(0, MAX_AMOUNT_CENTS),
    )

    private fun validate(entry: LedgerEntry) {
        require(entry.amountCents in 1..MAX_AMOUNT_CENTS) { "金额必须大于 0 且不能超出范围" }
        require(entry.occurredAt > 0) { "账单时间无效" }
        require(entry.category.isNotBlank() && entry.category.length <= 80) { "分类无效" }
        require(entry.merchant.length <= 200) { "商家名称过长" }
        require(entry.note.length <= 2_000) { "备注过长" }
        require(entry.fingerprint == null || entry.fingerprint.matches(Regex("[a-f0-9]{64}"))) {
            "自动记账事件编号无效"
        }
    }

    private fun LedgerEntry.values(includeFingerprint: Boolean) = ContentValues().apply {
        put("amount_cents", amountCents)
        put("type", type.name)
        put("category", category.trim())
        put("merchant", merchant.trim())
        put("note", note.trim())
        put("occurred_at", occurredAt)
        put("source", source.name)
        put("needs_review", if (needsReview) 1 else 0)
        put("business_type", businessType.name)
        if (relatedEntryId == null) putNull("related_entry_id") else put("related_entry_id", relatedEntryId)
        put("edited_by_user", if (editedByUser) 1 else 0)
        if (includeFingerprint) {
            if (fingerprint == null) putNull("fingerprint") else put("fingerprint", fingerprint)
        }
    }

    private fun refreshEntries() {
        val db = database.readableDatabase
        val loadedEntries = readEntries()
        val loadedEvents = readRecognitionEvents(db)
        val loadedDeleted = db.query("entries",null,"deleted_at IS NOT NULL",null,null,null,"deleted_at DESC,id DESC")
            .use { cursor -> buildList { while(cursor.moveToNext()) add(cursor.ledgerRecord()) } }
        val loadedRules = db.query("merchant_rules",null,null,null,null,null,"updated_at DESC").use { cursor -> buildList {
            while(cursor.moveToNext()) add(MerchantCategoryRule(EntrySource.valueOf(cursor.text("provider")),cursor.text("merchant_key"),EntryType.valueOf(cursor.text("type")),cursor.text("category"),cursor.number("updated_at")))
        } }
        val loadedDiagnostics = db.query("recognition_diagnostics",null,null,null,null,null,"channel").use { cursor -> buildList {
            while(cursor.moveToNext()) add(RecognitionDiagnostic(cursor.text("channel"),cursor.number("last_success_at"),cursor.number("last_failure_at"),cursor.text("last_failure_reason"),cursor.number("last_duration_ms")))
        } }
        // Read everything before publishing: a failing table cannot expose a partial refresh.
        mutableEntries.value = loadedEntries
        mutableRecognitionEvents.value = loadedEvents
        mutableDeletedEntries.value = loadedDeleted
        mutableMerchantRules.value = loadedRules
        mutableDiagnostics.value = loadedDiagnostics
        mutableStorageError.value = null
    }

    private fun refreshAfterCommit() {
        runCatching { refreshEntries() }.onFailure {
            synchronized(settingsLock) {
                captureBlocked = true
                mutableRestoreBlocked.value = true
                configurationGeneration++
            }
            mutableStorageError.value = "账单已保存，但读取或刷新失败；请先重试读取，避免重复记账。"
        }
    }

    private fun readEntries(): List<LedgerEntry> = database.readableDatabase.query(
        "entries", null, "deleted_at IS NULL", null, null, null, "occurred_at DESC, id DESC",
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.toEntry()) }
    }

    private fun Cursor.toEntry() = LedgerEntry(
        id = getLong(getColumnIndexOrThrow("id")),
        amountCents = getLong(getColumnIndexOrThrow("amount_cents")),
        type = EntryType.valueOf(getString(getColumnIndexOrThrow("type"))),
        category = getString(getColumnIndexOrThrow("category")),
        merchant = getString(getColumnIndexOrThrow("merchant")),
        note = getString(getColumnIndexOrThrow("note")),
        occurredAt = getLong(getColumnIndexOrThrow("occurred_at")),
        source = EntrySource.valueOf(getString(getColumnIndexOrThrow("source"))),
        fingerprint = getColumnIndexOrThrow("fingerprint").let { if (isNull(it)) null else getString(it) },
        needsReview = getInt(getColumnIndexOrThrow("needs_review")) == 1,
        businessType = BusinessType.valueOf(getString(getColumnIndexOrThrow("business_type"))),
        relatedEntryId = getColumnIndexOrThrow("related_entry_id").let { if (isNull(it)) null else getLong(it) },
        editedByUser = getInt(getColumnIndexOrThrow("edited_by_user")) == 1,
    )

    internal suspend fun closeForTesting() {
        scope.cancel()
        withContext(Dispatchers.IO) { mutex.withLock { database.close() } }
    }

    companion object {
        const val MAX_AMOUNT_CENTS = Money.MAX_CENTS
        @Volatile private var instance: LedgerRepository? = null

        fun get(context: Context): LedgerRepository = instance ?: synchronized(this) {
            instance ?: LedgerRepository(context.applicationContext).also { instance = it }
        }

        internal fun isolated(context: Context, databaseName: String, preferencesName: String) =
            LedgerRepository(context.applicationContext, databaseName, preferencesName)

        internal fun csvCell(value: String): String {
            // Quoting alone does not prevent spreadsheet formula execution.
            val firstMeaningful = value.firstOrNull { !it.isWhitespace() && !it.isISOControl() }
            val safe = if (firstMeaningful in listOf('=', '+', '-', '@') ||
                value.startsWith('\t') || value.startsWith('\r') || value.startsWith('\n')
            ) "'$value" else value
            return "\"${safe.replace("\"", "\"\"")}\""
        }
    }
}

private class LedgerDatabase(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 3) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
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
                needs_review INTEGER NOT NULL DEFAULT 0,
                capture_kind TEXT NOT NULL DEFAULT 'LEGACY' CHECK(capture_kind IN ('MANUAL', 'NOTIFICATION', 'SCREEN', 'LEGACY')),
                business_type TEXT NOT NULL CHECK(business_type IN ('EXPENSE','INCOME','TRANSFER','REFUND','REPAYMENT','UNKNOWN')),
                related_entry_id INTEGER REFERENCES entries(id),
                edited_by_user INTEGER NOT NULL DEFAULT 0,
                deleted_at INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX entries_date ON entries(occurred_at DESC, id DESC)")
        db.execSQL("CREATE TABLE seen_events (fingerprint TEXT PRIMARY KEY, observed_at INTEGER NOT NULL)")
        RecognitionSchema.install(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion in 1..2 && newVersion == 3) { "Unsupported ledger database migration: $oldVersion to $newVersion" }
        if (oldVersion == 1) {
            db.execSQL("ALTER TABLE entries ADD COLUMN capture_kind TEXT NOT NULL DEFAULT 'LEGACY' CHECK(capture_kind IN ('MANUAL', 'NOTIFICATION', 'SCREEN', 'LEGACY'))")
            db.execSQL("UPDATE entries SET capture_kind = 'MANUAL' WHERE source = 'MANUAL' AND fingerprint IS NULL")
        }
        db.execSQL("ALTER TABLE entries ADD COLUMN business_type TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK(business_type IN ('EXPENSE','INCOME','TRANSFER','REFUND','REPAYMENT','UNKNOWN'))")
        db.execSQL("UPDATE entries SET business_type = type")
        db.execSQL("ALTER TABLE entries ADD COLUMN related_entry_id INTEGER REFERENCES entries(id)")
        db.execSQL("ALTER TABLE entries ADD COLUMN edited_by_user INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE entries ADD COLUMN deleted_at INTEGER")
        RecognitionSchema.install(db)
        RecognitionSchema.preserveLegacyEvents(db)
    }
}
