package com.example.jizhang.agent

import com.example.jizhang.domain.BusinessType
import com.example.jizhang.domain.EntryType
import com.example.jizhang.domain.LedgerEntry

/** Provider-specific JSON is decoded by the transport, never by the local ledger tools. */
data class AgentToolCall(val id: String, val name: String, val arguments: String)

enum class AgentPeriod { TODAY, THIS_MONTH, LAST_MONTH }

sealed interface AgentToolRequest {
    data class PrepareEntry(
        val amount: String,
        val direction: EntryType,
        val businessType: BusinessType,
        val category: String,
        val merchant: String = "",
        val note: String = "",
        /** Empty means today; an explicit date must use the local yyyy-MM-dd calendar date. */
        val date: String = "",
    ) : AgentToolRequest

    data class QuerySummary(val period: AgentPeriod) : AgentToolRequest
    data object ExplainPending : AgentToolRequest
    data object CheckConnection : AgentToolRequest
    data object Unsupported : AgentToolRequest
}

/** Only fixed reason categories may leave the device, never captured text, IDs or merchant names. */
enum class AgentPendingReason(val label: String) {
    AMOUNT("金额需要核对"),
    DUPLICATE("可能重复或关联冲突"),
    BUSINESS_TYPE("经济含义或资金方向待确认"),
    COMPLETION("交易完成状态待确认"),
    REFUND_LINK("退款需要关联原消费"),
    IMAGE_REVIEW("图片识别需要人工核对"),
    TIME("交易时间需要核对"),
    OTHER("其他需要人工核对的识别依据"),
}

/** Null means unread/unknown. No system logs, screen text or financial data are accepted here. */
data class AgentConnectionSnapshot(
    val notificationAuthorized: Boolean? = null,
    val notificationConnected: Boolean? = null,
    val screenAuthorized: Boolean? = null,
    val screenConnected: Boolean? = null,
    val autoEnabled: Boolean? = null,
    val screenEnabled: Boolean? = null,
    val ocrEnabled: Boolean? = null,
)

/** The caller supplies only its current active rows, never recycle-bin/deleted rows. */
data class AgentLedgerSnapshot(
    val entries: List<LedgerEntry> = emptyList(),
    val pendingCount: Int = 0,
    /** Counts may overlap: one pending event may need more than one kind of review. */
    val pendingReasonCounts: Map<AgentPendingReason, Int> = emptyMap(),
    val connection: AgentConnectionSnapshot = AgentConnectionSnapshot(),
)

enum class AgentRejectionCode {
    INVALID_AMOUNT, INVALID_DIRECTION, UNSUPPORTED_BUSINESS, INVALID_CATEGORY,
    INVALID_DATE, INVALID_TEXT, INVALID_SNAPSHOT, UNSUPPORTED_TOOL,
}

sealed interface AgentToolResult {
    /** Unsaved manual draft. Only the existing entry editor may persist it after user confirmation. */
    data class Draft(val entry: LedgerEntry) : AgentToolResult

    /** Dates form a half-open local-calendar interval. Amounts remain exact integer cents. */
    data class Summary(
        val period: AgentPeriod,
        val startDate: String,
        val endDateExclusive: String,
        val incomeCents: Long,
        val grossExpenseCents: Long,
        val refundCents: Long,
        val netExpenseCents: Long,
        val balanceCents: Long,
        val entryCount: Int,
        val expenseByCategory: Map<String, Long>,
    ) : AgentToolResult

    data class PendingSummary(
        val count: Int,
        val reasonCounts: Map<AgentPendingReason, Int>,
        val message: String,
    ) : AgentToolResult

    data class Connection(
        val status: AgentConnectionSnapshot,
        val messages: List<String>,
    ) : AgentToolResult

    /** Messages are fixed local text, not a reflection of untrusted model arguments. */
    data class Rejected(val code: AgentRejectionCode, val message: String) : AgentToolResult
}
