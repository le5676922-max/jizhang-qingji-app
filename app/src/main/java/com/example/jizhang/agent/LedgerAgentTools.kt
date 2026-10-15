package com.example.jizhang.agent

import com.example.jizhang.domain.BusinessType
import com.example.jizhang.domain.EntrySource
import com.example.jizhang.domain.EntryType
import com.example.jizhang.domain.LedgerCategories
import com.example.jizhang.domain.LedgerEntry
import com.example.jizhang.domain.LedgerStatistics
import com.example.jizhang.domain.Money
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A deliberately small, pure local tool boundary. It has no repository, Android Context, network,
 * write callback, permission API or service reference. An LLM cannot use this executor to save,
 * confirm, delete, link a refund, change settings or bypass capture/connection requirements.
 */
object LedgerAgentTools {
    fun execute(
        request: AgentToolRequest,
        snapshot: AgentLedgerSnapshot = AgentLedgerSnapshot(),
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): AgentToolResult = when (request) {
        is AgentToolRequest.PrepareEntry -> prepare(request, nowMillis, timeZone)
        is AgentToolRequest.QuerySummary -> summary(request.period, snapshot, nowMillis, timeZone)
        AgentToolRequest.ExplainPending -> pending(snapshot)
        AgentToolRequest.CheckConnection -> connection(snapshot.connection)
        AgentToolRequest.Unsupported -> reject(AgentRejectionCode.UNSUPPORTED_TOOL, "助手不支持这个操作。")
    }

    private fun prepare(
        request: AgentToolRequest.PrepareEntry,
        nowMillis: Long,
        timeZone: TimeZone,
    ): AgentToolResult {
        val cents = Money.parseCents(request.amount)
            ?: return reject(AgentRejectionCode.INVALID_AMOUNT, "请输入大于零且最多两位小数的金额。")
        val business = request.businessType
        if (business == BusinessType.REFUND) return reject(AgentRejectionCode.UNSUPPORTED_BUSINESS,
            "退款需要在识别中心关联并核对原消费，助手不能代为关联或入账。")
        if (business == BusinessType.UNKNOWN) return reject(AgentRejectionCode.UNSUPPORTED_BUSINESS,
            "交易用途尚不明确，请先说明是消费、收入、转账还是还款。")
        if ((business == BusinessType.EXPENSE || business == BusinessType.REPAYMENT) && request.direction != EntryType.EXPENSE ||
            business == BusinessType.INCOME && request.direction != EntryType.INCOME) {
            return reject(AgentRejectionCode.INVALID_DIRECTION, "资金方向与交易用途不一致，请核对。")
        }
        val allowed = when (business) {
            BusinessType.EXPENSE -> LedgerCategories.expense
            BusinessType.INCOME -> LedgerCategories.income.filterNot { it == "退款" }
            BusinessType.TRANSFER, BusinessType.REPAYMENT -> listOf("其他")
            else -> emptyList()
        }
        val category = request.category.trim()
        if (request.category.length > 80 || category !in allowed) {
            return reject(AgentRejectionCode.INVALID_CATEGORY, "分类不受支持，请选择现有分类；转账和还款使用其他分类。")
        }
        if (!validText(request.merchant, 200, allowLines = false) ||
            !validText(request.note, 2_000, allowLines = true)) {
            return reject(AgentRejectionCode.INVALID_TEXT, "商家最多 200 字，备注最多 2000 字，且不能包含无效控制字符。")
        }
        val occurredAt = parseDate(request.date, nowMillis, timeZone)
            ?: return reject(AgentRejectionCode.INVALID_DATE, "日期须为有效的 yyyy-MM-dd，不能晚于今天或早于 1970 年。")
        return AgentToolResult.Draft(LedgerEntry(
            amountCents = cents,
            type = request.direction,
            businessType = business,
            category = category,
            merchant = request.merchant.trim(),
            note = request.note.trim(),
            occurredAt = occurredAt,
            source = EntrySource.MANUAL,
            fingerprint = null,
            needsReview = false,
        ))
    }

    private fun summary(
        period: AgentPeriod,
        snapshot: AgentLedgerSnapshot,
        nowMillis: Long,
        timeZone: TimeZone,
    ): AgentToolResult {
        // Copy the supplied active snapshot. The source list is never mutated and pending legacy
        // rows cannot affect formal totals, count or reporting categories.
        val active = snapshot.entries.toList().filterNot { it.needsReview }
        if (active.any { it.amountCents !in 1..Money.MAX_CENTS || it.occurredAt <= 0 }) {
            return reject(AgentRejectionCode.INVALID_SNAPSHOT, "本地账本中存在异常金额或时间，请先在账单中核对。")
        }
        // Build both boundaries from noon before normalizing either midnight. In zones that
        // skip midnight, carrying a normalized 01:00 into tomorrow would include an extra hour.
        val anchor = calendar(nowMillis, timeZone).apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (period != AgentPeriod.TODAY) set(Calendar.DAY_OF_MONTH, 1)
            if (period == AgentPeriod.LAST_MONTH) add(Calendar.MONTH, -1)
        }
        val end = (anchor.clone() as Calendar).apply {
            add(if (period == AgentPeriod.TODAY) Calendar.DAY_OF_MONTH else Calendar.MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
        }
        val start = anchor.apply { set(Calendar.HOUR_OF_DAY, 0) }
        val periodEntries = active.filter { it.occurredAt >= start.timeInMillis && it.occurredAt < end.timeInMillis }
        // Even a pathological snapshot must not silently wrap financial totals. The repository's
        // per-entry bound alone does not bound a sum over an arbitrarily long history.
        val safeLimit = Long.MAX_VALUE / Money.MAX_CENTS
        if (active.size.toLong() > safeLimit) {
            return reject(AgentRejectionCode.INVALID_SNAPSHOT, "账本数据过大，暂时无法安全汇总。")
        }
        val totals = LedgerStatistics.calculate(periodEntries, active)
        val categories = LedgerStatistics.expenseByCategory(periodEntries, active)
        // User-created category labels can contain personal text, so export only fixed categories.
        val safeCategories = linkedMapOf<String, Long>()
        categories.forEach { (name, value) ->
            val safeName = if (name in LedgerCategories.expense) name else "其他"
            safeCategories[safeName] = (safeCategories[safeName] ?: 0) + value
        }
        return AgentToolResult.Summary(period,
            dateText(start.timeInMillis, timeZone), dateText(end.timeInMillis, timeZone),
            totals.incomeCents, totals.grossExpenseCents, totals.refundCents, totals.expenseCents,
            totals.balanceCents, periodEntries.size, safeCategories.toMap())
    }

    private fun pending(snapshot: AgentLedgerSnapshot): AgentToolResult {
        if (snapshot.pendingCount < 0 || snapshot.pendingReasonCounts.values.any { it !in 0..snapshot.pendingCount }) {
            return reject(AgentRejectionCode.INVALID_SNAPSHOT, "待核对统计尚未准备好，请重新打开识别中心。")
        }
        return AgentToolResult.PendingSummary(snapshot.pendingCount,
            snapshot.pendingReasonCounts.filterValues { it > 0 }.toMap(),
            "待核对记录尚未计入正式账本；请到识别中心逐笔核对金额、用途和关联。助手不能代为确认。")
    }

    private fun connection(status: AgentConnectionSnapshot): AgentToolResult.Connection {
        val messages = buildList {
            add(when {
                status.notificationAuthorized == true && status.notificationConnected == true ->
                    "通知读取已授权且服务已连接；仍需付款应用实际发出含金额的付款通知。"
                status.notificationAuthorized == true -> "通知读取已授权但尚未确认连接，请在应用中重新检查连接。"
                status.notificationConnected == true -> "通知服务收到过连接回调，当前系统授权未确认，请重新检查连接。"
                status.notificationAuthorized == false -> "通知读取未授权，请在系统通知使用权设置中授权。"
                else -> "尚未读取通知授权状态。"
            })
            add(when {
                status.screenAuthorized == true && status.screenConnected == true ->
                    "屏幕记账已授权且服务已连接；仅处理支持的交易详情页面。"
                status.screenAuthorized == true -> "屏幕记账已授权但尚未连接，请在应用中运行连接排查。"
                status.screenConnected == true -> "屏幕记账本进程收到过连接回调，当前系统授权未确认，请重新检查连接。"
                status.screenAuthorized == false -> "屏幕记账未授权，请在系统辅助功能中授权。"
                else -> "尚未读取屏幕记账连接状态。"
            })
            if (status.autoEnabled == false) add("通知自动记账开关已关闭，助手不会代为开启。")
            if (status.screenEnabled == false) add("屏幕记账开关已关闭。")
            if (status.ocrEnabled == false) add("本地截图识别开关已关闭，部分无页面文字的交易无法识别。")
            add("连接报告仅解释当前状态，不会修改系统授权或在付款应用内操作。")
        }
        return AgentToolResult.Connection(status, messages)
    }

    private fun validText(value: String, maximum: Int, allowLines: Boolean): Boolean =
        value.length <= maximum && value.none { char ->
            char.isISOControl() && !(allowLines && char in setOf('\n', '\r', '\t'))
        }

    private fun parseDate(value: String, nowMillis: Long, timeZone: TimeZone): Long? {
        if (nowMillis <= 0) return null
        if (value.isEmpty()) return nowMillis
        if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value) || value < "1970-01-01" ||
            value > dateText(nowMillis, timeZone)) return null
        // Noon remains a valid local time on dates whose time zone skips midnight during DST.
        val parser = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply {
            isLenient = false
            this.timeZone = timeZone
        }
        val text = "$value 12:00"
        val position = ParsePosition(0)
        val parsed = parser.parse(text, position) ?: return null
        return parsed.time.takeIf { position.index == text.length && it > 0 }
    }

    private fun calendar(time: Long, timeZone: TimeZone): Calendar = Calendar.getInstance(timeZone, Locale.ROOT).apply {
        timeInMillis = time
    }

    private fun dateText(time: Long, timeZone: TimeZone): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
        this.timeZone = timeZone
    }.format(Date(time))

    private fun reject(code: AgentRejectionCode, message: String) = AgentToolResult.Rejected(code, message)
}
