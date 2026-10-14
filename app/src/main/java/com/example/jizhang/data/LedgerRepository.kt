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
import java.security.MessageDigest
import java.util.UUID
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
    @Volatile private var configurationGeneration = 0L
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

    val entries: StateFlow<List<LedgerEntry>> = mutableEntries.asStateFlow()
    val settings: StateFlow<LedgerSettings> = mutableSettings.asStateFlow()
    val storageError: StateFlow<String?> = mutableStorageError.asStateFlow()
    val captureError: StateFlow<String?> = mutableCaptureError.asStateFlow()
    val recognitionEvents: StateFlow<List<RecognitionEvent>> = mutableRecognitionEvents.asStateFlow()
    val deletedEntries: StateFlow<List<LedgerEntry>> = mutableDeletedEntries.asStateFlow()
    val merchantRules: StateFlow<List<MerchantCategoryRule>> = mutableMerchantRules.asStateFlow()
    val diagnostics: StateFlow<List<RecognitionDiagnostic>> = mutableDiagnostics.asStateFlow()

    init {
        scope.launch {
            runCatching { mutex.withLock { refreshEntries() } }
                .onFailure { mutableStorageError.value = "账本暂时无法读取，请检查设备存储空间后重试。" }
        }
    }

    /** Used to exclude notifications that predate explicit opt-in. */
    fun automationEnabledSince(): Long = synchronized(settingsLock) {
        if (!mutableSettings.value.autoEnabled) Long.MAX_VALUE
        else preferences.getLong("automation_enabled_since", Long.MAX_VALUE)
    }

    fun updateSettings(settings: LedgerSettings) {
        require(settings.monthlyBudgetCents in 0..MAX_AMOUNT_CENTS) { "预算金额超出范围" }
        synchronized(settingsLock) {
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
                editor.putLong("automation_enabled_since", System.currentTimeMillis())
            }
            if (!settings.autoEnabled) editor.remove("automation_enabled_since")
            editor.apply()
            mutableSettings.value = settings
        }
    }

    fun recognitionConfigurationGeneration():Long = configurationGeneration

    /** A lost-notification warning stays visible across successful captures and app restarts. */
    fun reportCaptureError(message: String) {
        require(message.isNotBlank() && message.length <= 300)
        synchronized(settingsLock) {
            preferences.edit().putString("capture_error", message).apply()
            mutableCaptureError.value = message
        }
    }

    /** Called only after the user has checked the ledger and explicitly dismissed the warning. */
    fun acknowledgeCaptureError() {
        synchronized(settingsLock) {
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
            runCatching { mutex.withLock {
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

    /** Returns false for an already observed event, even if its ledger row was deleted. */
    suspend fun add(entry: LedgerEntry): Boolean = insert(entry, emptyList())

    /** Alternate hashes describe one notification instance; none contain its readable text. */
    internal suspend fun addNotification(entry: LedgerEntry, alternateFingerprints: List<String>): Boolean =
        insert(entry, alternateFingerprints, notificationCapture = true)

    /** Single V2 entry point: extracted events and posted ledger entries commit together. */
    suspend fun ingest(candidate: TransactionCandidate, stillValid: () -> Boolean = { true }): RecognitionSaveResult = withContext(Dispatchers.IO) {
        validateCandidate(candidate)
        mutex.withLock {
            if (!candidateAllowed(candidate) || !stillValid()) return@withLock RecognitionSaveResult(
                RecognitionDecision.IGNORE, disabled = true, reasons = listOf("此来源的自动识别已关闭"))
            val db = database.writableDatabase
            var result: RecognitionSaveResult
            db.beginTransaction()
            try {
                val aliases = (listOf(candidate.eventFingerprint) + candidate.alternateFingerprints).distinct()
                val oldEvent = aliases.firstNotNullOfOrNull { hash ->
                    db.rawQuery("SELECT s.id,s.decision,s.linked_entry_id FROM event_aliases a JOIN source_events s ON s.id=a.event_id WHERE a.fingerprint=?", arrayOf(hash))
                        .use { cursor -> if (cursor.moveToFirst()) Triple(cursor.number("id"), RecognitionDecision.valueOf(cursor.text("decision")), cursor.nullableLong("linked_entry_id")) else null }
                }
                if (oldEvent != null) {
                    rememberAliases(db, oldEvent.first, aliases, candidate.observedAt)
                    result = RecognitionSaveResult(oldEvent.second, oldEvent.first, oldEvent.third, duplicate = true,
                        reasons = listOf("同一来源事件已经处理，保留原有决定和人工修改"))
                } else if ((aliases + candidate.legacyFingerprints).any { hash -> db.query("seen_events", arrayOf("fingerprint"), "fingerprint=?", arrayOf(hash), null,null,null,"1").use { it.moveToFirst() } }) {
                    aliases.forEach { hash -> db.execSQL("INSERT OR IGNORE INTO seen_events(fingerprint,observed_at) VALUES (?,?)", arrayOf<Any>(hash,candidate.observedAt)) }
                    result = RecognitionSaveResult(RecognitionDecision.IGNORE, duplicate = true, reasons = listOf("旧版已处理或删除的事件不会重新创建账单"))
                } else {
                    val decision = DecisionEngine.evaluate(candidate)
                    val entryId = if (decision.decision == RecognitionDecision.AUTO) {
                        val type = if (candidate.direction == FundsDirection.INCOMING) EntryType.INCOME else EntryType.EXPENSE
                        val record = LedgerEntry(amountCents=candidate.amountCents, type=type,
                            category=candidate.categorySuggestion, merchant=candidate.merchant,
                            occurredAt=candidate.occurredAt, source=candidate.provider, fingerprint=candidate.eventFingerprint,
                            businessType=candidate.businessType)
                        db.insertOrThrow("entries", null, record.databaseValues().apply { put("capture_kind", if(candidate.evidence==EvidenceType.NOTIFICATION) "NOTIFICATION" else "SCREEN") })
                    } else null
                    val eventId = db.insertOrThrow("source_events", null, candidate.eventValues(decision,
                        providerOrderHash(candidate.provider,candidate.orderNamespace,candidate.orderId),
                        providerOrderHash(candidate.provider,candidate.originalOrderNamespace,candidate.originalOrderId),
                        linkedId=entryId, association=if(entryId==null) "NONE" else "CREATED"))
                    rememberAliases(db,eventId,aliases,candidate.observedAt)
                    result = RecognitionSaveResult(decision.decision,eventId,entryId,reasons=decision.reasons)
                }
                if (!candidateAllowed(candidate) || !stillValid()) return@withLock RecognitionSaveResult(
                    RecognitionDecision.IGNORE, disabled=true, reasons=listOf("来源已关闭或页面已切换，本次结果未保存"))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            refreshEntries()
            result
        }
    }

    private fun candidateAllowed(candidate: TransactionCandidate): Boolean {
        if (candidate.userInitiated && candidate.evidence in setOf(EvidenceType.IMPORTED_IMAGE,EvidenceType.SHARED_IMAGE)) return true
        val current = settings.value
        if (candidate.provider == EntrySource.WECHAT && !current.wechatEnabled ||
            candidate.provider == EntrySource.ALIPAY && !current.alipayEnabled || candidate.provider == EntrySource.MANUAL) return false
        return when(candidate.evidence) {
            EvidenceType.NOTIFICATION -> current.autoEnabled && candidate.observedAt >= automationEnabledSince()
            EvidenceType.ACCESSIBILITY -> current.screenEnabled
            EvidenceType.OCR -> current.screenEnabled && current.screenOcrEnabled
            else -> false
        }
    }

    private fun validateCandidate(candidate: TransactionCandidate) {
        require(candidate.amountCents in 1..MAX_AMOUNT_CENTS && candidate.occurredAt>0 && candidate.observedAt>0)
        require(candidate.eventFingerprint.matches(Regex("[a-f0-9]{64}")))
        require(candidate.alternateFingerprints.size<=2 && candidate.alternateFingerprints.all { it.matches(Regex("[a-f0-9]{64}")) })
        require(candidate.legacyFingerprints.size<=2 && candidate.legacyFingerprints.all { it.matches(Regex("[a-f0-9]{64}")) })
        require(candidate.merchant.length<=200 && candidate.categorySuggestion.isNotBlank() && candidate.categorySuggestion.length<=80)
        require(candidate.ruleVersion.isNotBlank() && candidate.ruleVersion.length<=80)
        require(candidate.reasons.size<=20 && candidate.reasons.all { it.length<=250 })
        require(candidate.amountOptions.size<=8 && candidate.amountOptions.all { it in 1..MAX_AMOUNT_CENTS })
        require(listOfNotNull(candidate.orderId,candidate.originalOrderId).all { it.length in 8..80 && it.matches(Regex("[A-Za-z0-9_-]+")) })
        require(listOfNotNull(candidate.orderNamespace,candidate.originalOrderNamespace).all { it.length<=60 && it.matches(Regex("[a-z0-9_/-]+")) })
    }

    private fun rememberAliases(db: SQLiteDatabase, eventId:Long, fingerprints:List<String>, observedAt:Long) {
        fingerprints.forEach { hash ->
            db.execSQL("INSERT OR IGNORE INTO event_aliases(fingerprint,event_id) VALUES (?,?)", arrayOf<Any>(hash,eventId))
            db.execSQL("INSERT OR IGNORE INTO seen_events(fingerprint,observed_at) VALUES (?,?)", arrayOf<Any>(hash,observedAt))
        }
    }

    /** Screen observations with uncertain identity or OCR are saved for explicit review. */
    suspend fun addScreenPayment(screen: ScreenPayment, capturedAt: Long, userInitiated: Boolean = false): ScreenSaveResult {
        val selected = settings.value
        val payment = screen.payment
        if (!userInitiated && (!selected.screenEnabled ||
            payment.source == EntrySource.WECHAT && !selected.wechatEnabled ||
            payment.source == EntrySource.ALIPAY && !selected.alipayEnabled ||
            payment.source == EntrySource.MANUAL
        )) return ScreenSaveResult.SOURCE_DISABLED
        val eventTime = screen.occurredAt ?: capturedAt
        val hasIdentity = screen.hasStableIdentity
        val historical = capturedAt - eventTime > 15 * 60_000
        val review = payment.needsReview || !hasIdentity || historical
        // Weak observations get a new review identity. The service suppresses redraws while
        // on the same receipt; a second equal payment must not be discarded permanently.
        val identity = if (hasIdentity) "screen|${payment.source}|${screen.receiptKey}"
            else "screen-review|${UUID.randomUUID()}"
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val reasons = buildList {
            if (payment.needsReview) add("识别结果请核对")
            if (!hasIdentity) add("缺少交易单号和时间")
            if (historical) add("历史交易请核对")
        }
        val timeKey = screen.occurredAt?.let { "${payment.source.name}|${payment.type.name}|time|$it|${payment.amountCents}" }
        val timeFingerprint = timeKey?.let { key ->
            MessageDigest.getInstance("SHA-256").digest("screen|${payment.source}|$key".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
        val inserted = insert(LedgerEntry(
            amountCents = payment.amountCents, type = payment.type, category = payment.category,
            merchant = payment.merchant.take(200), source = payment.source, occurredAt = eventTime,
            fingerprint = fingerprint, needsReview = review,
            note = ("屏幕识别 · ${screen.summary}" + if (reasons.isEmpty()) "" else "\n${reasons.joinToString("；")}").take(500),
        ), emptyList(), screenCapture = true, screenRequiresEnabled = !userInitiated,
            screenHasTransactionId = screen.transactionId != null, screenTimeFingerprint = timeFingerprint)
        if (!inserted && !userInitiated && (!settings.value.screenEnabled ||
                payment.source == EntrySource.WECHAT && !settings.value.wechatEnabled ||
                payment.source == EntrySource.ALIPAY && !settings.value.alipayEnabled)) return ScreenSaveResult.SOURCE_DISABLED
        val savedNeedsReview = entries.value.firstOrNull { it.fingerprint == fingerprint }?.needsReview ?: review
        return if (!inserted) ScreenSaveResult.DUPLICATE else if (savedNeedsReview) ScreenSaveResult.NEEDS_REVIEW else ScreenSaveResult.INSERTED
    }

    private suspend fun insert(entry: LedgerEntry, alternateFingerprints: List<String>, notificationCapture: Boolean = false,
                               screenCapture: Boolean = false, screenRequiresEnabled: Boolean = false,
                               screenHasTransactionId: Boolean = false, screenTimeFingerprint: String? = null): Boolean = withContext(Dispatchers.IO) {
        validate(entry)
        require(alternateFingerprints.size <= 2 && alternateFingerprints.all { it.matches(Regex("[a-f0-9]{64}")) })
        require(alternateFingerprints.isEmpty() || entry.fingerprint != null)
        mutex.withLock {
            if (screenRequiresEnabled && (!settings.value.screenEnabled ||
                    entry.source == EntrySource.WECHAT && !settings.value.wechatEnabled ||
                    entry.source == EntrySource.ALIPAY && !settings.value.alipayEnabled)) return@withLock false
            val db = database.writableDatabase
            val savedEntries = if (notificationCapture || screenCapture) readEntries() else emptyList()
            // Capture provenance is immutable database metadata. A user's editable note
            // cannot remove cross-channel duplicate protection.
            val captureKinds = if (savedEntries.isEmpty()) emptyMap() else db.query(
                "entries", arrayOf("id", "capture_kind"), null, null, null, null, null,
            ).use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getLong(0), cursor.getString(1)) } }
            val otherCapture = savedEntries.any { existing ->
                existing.source == entry.source && existing.type == entry.type &&
                    existing.amountCents == entry.amountCents && kotlin.math.abs(existing.occurredAt - entry.occurredAt) < 120_000 &&
                    (captureKinds[existing.id] == "LEGACY" ||
                        captureKinds[existing.id] == (if (notificationCapture) "SCREEN" else "NOTIFICATION"))
            }
            val seenTimeIdentity = screenCapture && screenTimeFingerprint != null && screenTimeFingerprint != entry.fingerprint &&
                db.query("seen_events", arrayOf("fingerprint"), "fingerprint = ?", arrayOf(screenTimeFingerprint),
                    null, null, null, "1").use { it.moveToFirst() }
            val upgradedIdentity = seenTimeIdentity || screenCapture && savedEntries.any { existing ->
                captureKinds[existing.id] == "SCREEN" && existing.fingerprint != entry.fingerprint &&
                    existing.source == entry.source && existing.type == entry.type && existing.amountCents == entry.amountCents &&
                    kotlin.math.abs(existing.occurredAt - entry.occurredAt) < 1_000 &&
                    (!screenHasTransactionId || existing.fingerprint == screenTimeFingerprint || existing.needsReview || entry.note.contains("退款收入"))
            }
            val record = if (otherCapture || upgradedIdentity) entry.copy(needsReview = true,
                note = (entry.note + if (otherCapture) "\n另一识别方式发现同额账单，请核对是否重复" else "\n页面交易信息发生变化，请核对是否重复").trim().take(500)) else entry
            var inserted = false
            db.beginTransaction()
            try {
                val fingerprints = (listOfNotNull(entry.fingerprint) + alternateFingerprints).distinct()
                val alreadySeen = fingerprints.any { fingerprint ->
                    db.query("seen_events", arrayOf("fingerprint"), "fingerprint = ?", arrayOf(fingerprint),
                        null, null, null, "1").use { it.moveToFirst() }
                }
                // Mark newly observed aliases even for an existing event, within this same transaction.
                fingerprints.forEach { fingerprint ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO seen_events (fingerprint, observed_at) VALUES (?, ?)",
                        arrayOf<Any>(fingerprint, System.currentTimeMillis()),
                    )
                }
                if (!alreadySeen) {
                    val values = record.values(includeFingerprint = true).apply {
                        put("capture_kind", when {
                            screenCapture -> "SCREEN"
                            notificationCapture -> "NOTIFICATION"
                            entry.fingerprint != null -> "LEGACY"
                            else -> "MANUAL"
                        })
                    }
                    db.insertOrThrow("entries", null, values)
                    inserted = true
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            if (inserted) refreshEntries()
            inserted
        }
    }

    suspend fun update(entry: LedgerEntry) = withContext(Dispatchers.IO) {
        validate(entry)
        require(entry.id > 0) { "账单编号无效" }
        mutex.withLock {
            // An edit cannot change the event identity or its original source.
            val values = entry.values(includeFingerprint = false).apply { remove("source"); put("edited_by_user", 1) }
            val changed = database.writableDatabase.update(
                "entries", values, "id = ? AND deleted_at IS NULL", arrayOf(entry.id.toString()),
            )
            require(changed == 1) { "账单已不存在" }
            refreshEntries()
        }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        require(id > 0) { "账单编号无效" }
        mutex.withLock {
            database.writableDatabase.update("entries", ContentValues().apply { put("deleted_at", System.currentTimeMillis()) },
                "id = ? AND deleted_at IS NULL", arrayOf(id.toString()))
            // Deliberately retain seen_events so later notification updates cannot restore this row.
            refreshEntries()
        }
    }

    /** UTF-8 BOM helps spreadsheet programs open Chinese columns correctly. */
    suspend fun exportCsv(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            buildString {
                append('\uFEFF')
                append("日期,类型,金额（元）,分类,商家,备注,来源,待核对\r\n")
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
        mutableEntries.value = readEntries()
        val db = database.readableDatabase
        mutableRecognitionEvents.value = readRecognitionEvents(db)
        mutableDeletedEntries.value = db.query("entries",null,"deleted_at IS NOT NULL",null,null,null,"deleted_at DESC,id DESC")
            .use { cursor -> buildList { while(cursor.moveToNext()) add(cursor.ledgerRecord()) } }
        mutableMerchantRules.value = db.query("merchant_rules",null,null,null,null,null,"updated_at DESC").use { cursor -> buildList {
            while(cursor.moveToNext()) add(MerchantCategoryRule(EntrySource.valueOf(cursor.text("provider")),cursor.text("merchant_key"),EntryType.valueOf(cursor.text("type")),cursor.text("category"),cursor.number("updated_at")))
        } }
        mutableDiagnostics.value = db.query("recognition_diagnostics",null,null,null,null,null,"channel").use { cursor -> buildList {
            while(cursor.moveToNext()) add(RecognitionDiagnostic(cursor.text("channel"),cursor.number("last_success_at"),cursor.number("last_failure_at"),cursor.text("last_failure_reason"),cursor.number("last_duration_ms")))
        } }
        mutableStorageError.value = null
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
