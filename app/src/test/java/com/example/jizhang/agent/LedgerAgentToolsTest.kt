package com.example.jizhang.agent

import com.example.jizhang.domain.BusinessType
import com.example.jizhang.domain.EntrySource
import com.example.jizhang.domain.EntryType
import com.example.jizhang.domain.LedgerEntry
import com.example.jizhang.domain.Money
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class LedgerAgentToolsTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private val now = instant("2026-10-15 12:34:56")
    private val expense = AgentToolRequest.PrepareEntry("12.30", EntryType.EXPENSE,
        BusinessType.EXPENSE, "餐饮")

    @Test fun createsOnlyUnsavedManualDraftForHumanEditor() {
        val result = execute(expense.copy(merchant = " 小店 ", note = " 午餐 ")) as AgentToolResult.Draft
        val entry = result.entry
        assertEquals(1230L, entry.amountCents)
        assertEquals(0L, entry.id)
        assertEquals("小店", entry.merchant)
        assertEquals("午餐", entry.note)
        assertEquals(now, entry.occurredAt)
        assertEquals(EntrySource.MANUAL, entry.source)
        assertEquals(BusinessType.EXPENSE, entry.businessType)
        assertNull(entry.fingerprint)
        assertNull(entry.relatedEntryId)
        assertFalse(entry.needsReview)
        assertFalse(entry.editedByUser)
    }

    @Test fun moneyMustBePositivePlainDecimalWithAtMostTwoDecimals() {
        listOf("0", "0.00", "-12", "+12", "12.300", "1e2", "1,234", "￥12", "NaN", "",
            "1000000000.00").forEach { amount ->
            assertEquals(amount, AgentRejectionCode.INVALID_AMOUNT,
                (execute(expense.copy(amount = amount)) as AgentToolResult.Rejected).code)
        }
        assertEquals(Money.MAX_CENTS,
            (execute(expense.copy(amount = "999999999.99")) as AgentToolResult.Draft).entry.amountCents)
        assertEquals(1L, (execute(expense.copy(amount = ".01")) as AgentToolResult.Draft).entry.amountCents)
    }

    @Test fun inconsistentDirectionsCannotBePrepared() {
        val requests = listOf(expense.copy(direction = EntryType.INCOME),
            expense.copy(businessType = BusinessType.INCOME),
            expense.copy(businessType = BusinessType.REPAYMENT, category = "其他", direction = EntryType.INCOME))
        requests.forEach { assertEquals(AgentRejectionCode.INVALID_DIRECTION,
            (execute(it) as AgentToolResult.Rejected).code) }
    }

    @Test fun transferKeepsExplicitDirectionAndNeverGuessesItIsConsumption() {
        EntryType.entries.forEach { direction ->
            val entry = (execute(expense.copy(direction = direction,
                businessType = BusinessType.TRANSFER, category = "其他")) as AgentToolResult.Draft).entry
            assertEquals(direction, entry.type)
            assertEquals(BusinessType.TRANSFER, entry.businessType)
            assertEquals("其他", entry.category)
        }
    }

    @Test fun refundRequiresExistingOriginalAndUnknownPurposeIsNotGuessed() {
        listOf(BusinessType.REFUND, BusinessType.UNKNOWN).forEach { business ->
            assertEquals(AgentRejectionCode.UNSUPPORTED_BUSINESS,
                (execute(expense.copy(businessType = business)) as AgentToolResult.Rejected).code)
        }
    }

    @Test fun categoriesMustUseExistingApplicableWhitelist() {
        listOf(expense.copy(category = "我的隐私分类"),
            expense.copy(businessType = BusinessType.INCOME, direction = EntryType.INCOME, category = "退款"),
            expense.copy(businessType = BusinessType.TRANSFER, category = "餐饮"),
            expense.copy(businessType = BusinessType.REPAYMENT, category = "交通"),
            expense.copy(category = "餐饮" + " ".repeat(80))).forEach { request ->
            assertEquals(AgentRejectionCode.INVALID_CATEGORY,
                (execute(request) as AgentToolResult.Rejected).code)
        }
        assertEquals("工资", (execute(expense.copy(businessType = BusinessType.INCOME,
            direction = EntryType.INCOME, category = "工资")) as AgentToolResult.Draft).entry.category)
    }

    @Test fun textBoundariesAreEnforcedWithoutSilentlyTruncatingFacts() {
        val exact = execute(expense.copy(merchant = "店".repeat(200), note = "记".repeat(2000)))
            as AgentToolResult.Draft
        assertEquals(200, exact.entry.merchant.length)
        assertEquals(2000, exact.entry.note.length)
        listOf(expense.copy(merchant = "店".repeat(201)), expense.copy(note = "记".repeat(2001)),
            expense.copy(merchant = "A\nB"), expense.copy(note = "A\u0000B")).forEach { request ->
            assertEquals(AgentRejectionCode.INVALID_TEXT, (execute(request) as AgentToolResult.Rejected).code)
        }
        assertEquals("第一行\n第二行", (execute(expense.copy(note = "第一行\n第二行"))
            as AgentToolResult.Draft).entry.note)
    }

    @Test fun datesAreStrictCalendarDatesAndFutureDatesAreRejected() {
        listOf("2026-02-29", "2026-04-31", "2026-10-16", "2026-1-1", "26-10-15", "2026/10/15",
            "2026-10-15 trailing", " 2026-10-15", "0000-01-01", "1969-12-31").forEach { date ->
            assertEquals(date, AgentRejectionCode.INVALID_DATE,
                (execute(expense.copy(date = date)) as AgentToolResult.Rejected).code)
        }
        assertEquals(instant("2024-02-29 12:00:00"),
            (execute(expense.copy(date = "2024-02-29")) as AgentToolResult.Draft).entry.occurredAt)
    }

    @Test fun explicitLocalDateUsesRequestedTimeZoneRatherThanUtcDay() {
        val losAngeles = TimeZone.getTimeZone("America/Los_Angeles")
        val time = instant("2026-10-15 02:00:00", TimeZone.getTimeZone("UTC"))
        val result = LedgerAgentTools.execute(expense.copy(date = "2026-10-14"), nowMillis = time,
            timeZone = losAngeles) as AgentToolResult.Draft
        assertEquals(instant("2026-10-14 12:00:00", losAngeles), result.entry.occurredAt)
        assertEquals(AgentRejectionCode.INVALID_DATE, (LedgerAgentTools.execute(
            expense.copy(date = "2026-10-15"), nowMillis = time, timeZone = losAngeles)
            as AgentToolResult.Rejected).code)
    }

    @Test fun validDateWithSkippedDstMidnightCanStillPrepareDraft() {
        val saoPaulo = TimeZone.getTimeZone("America/Sao_Paulo")
        val result = LedgerAgentTools.execute(expense.copy(date = "2018-11-04"), nowMillis = now,
            timeZone = saoPaulo) as AgentToolResult.Draft
        assertEquals(instant("2018-11-04 12:00:00", saoPaulo), result.entry.occurredAt)
    }

    @Test fun monthSummaryUsesCrossMonthRefundContextAndExcludesTransferRepaymentReview() {
        val original = entry(1, 10_000, "2026-09-01 12:00:00", category = "购物")
        val previousRefund = entry(2, 8_000, "2026-09-02 12:00:00", type = EntryType.INCOME,
            business = BusinessType.REFUND).copy(relatedEntryId = 1)
        val currentRefund = previousRefund.copy(id = 3, amountCents = 4_000,
            occurredAt = instant("2026-10-02 12:00:00"))
        val purchase = entry(4, 3_000, "2026-10-03 12:00:00", category = "餐饮")
        val income = entry(5, 20_000, "2026-10-03 12:00:00", type = EntryType.INCOME,
            business = BusinessType.INCOME, category = "工资")
        val entries = listOf(original, previousRefund, currentRefund, purchase, income,
            purchase.copy(id = 6, businessType = BusinessType.TRANSFER),
            purchase.copy(id = 7, businessType = BusinessType.REPAYMENT),
            purchase.copy(id = 8, needsReview = true))
        val result = execute(AgentToolRequest.QuerySummary(AgentPeriod.THIS_MONTH),
            AgentLedgerSnapshot(entries)) as AgentToolResult.Summary
        assertEquals("2026-10-01", result.startDate)
        assertEquals("2026-11-01", result.endDateExclusive)
        assertEquals(20_000L, result.incomeCents)
        assertEquals(3_000L, result.grossExpenseCents)
        assertEquals(2_000L, result.refundCents)
        assertEquals(1_000L, result.netExpenseCents)
        assertEquals(19_000L, result.balanceCents)
        // Entry count includes formal transfer/repayment cash movements, not pending records.
        assertEquals(5, result.entryCount)
        assertEquals(mapOf("购物" to -2_000L, "餐饮" to 3_000L), result.expenseByCategory)
    }

    @Test fun todayUsesLocalHalfOpenIntervalAtUtcDateBoundary() {
        val utc = TimeZone.getTimeZone("UTC")
        val entries = listOf(
            entry(1, 100, "2026-10-14 15:59:59", timeZone = utc),
            entry(2, 200, "2026-10-14 16:00:00", timeZone = utc),
            entry(3, 300, "2026-10-15 15:59:59", timeZone = utc),
            entry(4, 400, "2026-10-15 16:00:00", timeZone = utc))
        val result = execute(AgentToolRequest.QuerySummary(AgentPeriod.TODAY), AgentLedgerSnapshot(entries))
            as AgentToolResult.Summary
        assertEquals("2026-10-15", result.startDate)
        assertEquals("2026-10-16", result.endDateExclusive)
        assertEquals(500L, result.netExpenseCents)
        assertEquals(2, result.entryCount)
    }

    @Test fun previousMonthHandlesYearBoundaryWithoutThirtyDayApproximation() {
        val time = instant("2026-01-31 12:00:00")
        val entries = listOf(entry(1, 100, "2025-11-30 23:59:59"),
            entry(2, 200, "2025-12-01 00:00:00"), entry(3, 300, "2025-12-31 23:59:59"),
            entry(4, 400, "2026-01-01 00:00:00"))
        val result = LedgerAgentTools.execute(AgentToolRequest.QuerySummary(AgentPeriod.LAST_MONTH),
            AgentLedgerSnapshot(entries), time, zone) as AgentToolResult.Summary
        assertEquals("2025-12-01", result.startDate)
        assertEquals("2026-01-01", result.endDateExclusive)
        assertEquals(500L, result.netExpenseCents)
    }

    @Test fun skippedMidnightTodayDoesNotIncludeFirstHourOfNextDay() {
        val saoPaulo = TimeZone.getTimeZone("America/Sao_Paulo")
        val time = instant("2018-11-04 12:00:00", saoPaulo)
        val entries = listOf(entry(1, 100, "2018-11-03 23:59:59", timeZone = saoPaulo),
            entry(2, 200, "2018-11-04 01:00:00", timeZone = saoPaulo),
            entry(3, 300, "2018-11-04 23:59:59", timeZone = saoPaulo),
            entry(4, 400, "2018-11-05 00:30:00", timeZone = saoPaulo))
        val result = LedgerAgentTools.execute(AgentToolRequest.QuerySummary(AgentPeriod.TODAY),
            AgentLedgerSnapshot(entries), time, saoPaulo) as AgentToolResult.Summary
        assertEquals(500L, result.netExpenseCents)
        assertEquals(2, result.entryCount)
    }

    @Test fun summariesNeverExposeMerchantNotesCustomCategoryOrIndividualIds() {
        val entries = listOf(entry(123456, 100, "2026-10-15 12:00:00", category = "隐私分类")
            .copy(merchant = "敏感商户", note = "私人备注"),
            entry(2, 200, "2026-10-15 12:00:00", category = "其他"))
        val result = execute(AgentToolRequest.QuerySummary(AgentPeriod.THIS_MONTH),
            AgentLedgerSnapshot(entries)) as AgentToolResult.Summary
        assertEquals(mapOf("其他" to 300L), result.expenseByCategory)
        listOf("123456", "敏感商户", "私人备注", "隐私分类").forEach { assertFalse(result.toString().contains(it)) }
    }

    @Test fun pendingSummaryContainsOnlyCountsAndFixedReasonsWithoutConfirmingAnything() {
        val reasons = mapOf(AgentPendingReason.DUPLICATE to 2, AgentPendingReason.IMAGE_REVIEW to 2,
            AgentPendingReason.AMOUNT to 0)
        val result = execute(AgentToolRequest.ExplainPending,
            AgentLedgerSnapshot(pendingCount = 2, pendingReasonCounts = reasons)) as AgentToolResult.PendingSummary
        assertEquals(2, result.count)
        assertEquals(mapOf(AgentPendingReason.DUPLICATE to 2, AgentPendingReason.IMAGE_REVIEW to 2), result.reasonCounts)
        assertTrue(result.message.contains("尚未计入正式账本"))
        assertTrue(result.message.contains("不能代为确认"))
    }

    @Test fun malformedPendingCountsAreNotInventedOrClamped() {
        listOf(AgentLedgerSnapshot(pendingCount = -1),
            AgentLedgerSnapshot(pendingCount = 1, pendingReasonCounts = mapOf(AgentPendingReason.TIME to 2)),
            AgentLedgerSnapshot(pendingCount = 1, pendingReasonCounts = mapOf(AgentPendingReason.TIME to -1)))
            .forEach { assertEquals(AgentRejectionCode.INVALID_SNAPSHOT,
                (execute(AgentToolRequest.ExplainPending, it) as AgentToolResult.Rejected).code) }
    }

    @Test fun authorizedWaitingConnectionReportDoesNotPretendServiceIsConnected() {
        val state = AgentConnectionSnapshot(notificationAuthorized = true, notificationConnected = true, screenAuthorized = true,
            screenConnected = false, autoEnabled = true, screenEnabled = true, ocrEnabled = false)
        val result = execute(AgentToolRequest.CheckConnection, AgentLedgerSnapshot(connection = state))
            as AgentToolResult.Connection
        assertEquals(state, result.status)
        assertTrue(result.messages.any { it.contains("已授权但尚未连接") })
        assertTrue(result.messages.any { it.contains("运行连接排查") })
        assertTrue(result.messages.any { it.contains("本地截图识别开关已关闭") })
        assertFalse(result.messages.any { it.startsWith("屏幕记账已授权且服务已连接") })
    }

    @Test fun notificationGrantAndConnectionAreDistinctAndItsSwitchDoesNotDisableScreen() {
        val state = AgentConnectionSnapshot(notificationAuthorized = true, notificationConnected = false,
            screenAuthorized = true, screenConnected = true, autoEnabled = false, screenEnabled = true)
        val result = execute(AgentToolRequest.CheckConnection, AgentLedgerSnapshot(connection = state))
            as AgentToolResult.Connection
        assertTrue(result.messages.any { it.contains("通知读取已授权但尚未确认连接") })
        assertTrue(result.messages.any { it.startsWith("屏幕记账已授权且服务已连接") })
        assertTrue(result.messages.any { it.startsWith("通知自动记账开关已关闭") })
        assertFalse(result.messages.any { it.contains("总开关") })
    }

    @Test fun oldConnectionCallbackCannotOverrideRevokedOrUnknownAuthorization() {
        listOf(false, null).forEach { granted ->
            val state = AgentConnectionSnapshot(notificationAuthorized = granted, notificationConnected = true,
                screenAuthorized = granted, screenConnected = true)
            val result = execute(AgentToolRequest.CheckConnection, AgentLedgerSnapshot(connection = state))
                as AgentToolResult.Connection
            assertEquals(2, result.messages.count { it.contains("当前系统授权未确认") })
            assertFalse(result.messages.any { it.contains("已授权且服务已连接") })
        }
    }

    @Test fun unknownConnectionStateIsNotReportedAsDenied() {
        val result = execute(AgentToolRequest.CheckConnection) as AgentToolResult.Connection
        assertTrue(result.messages.any { it == "尚未读取通知授权状态。" })
        assertTrue(result.messages.any { it == "尚未读取屏幕记账连接状态。" })
        assertFalse(result.messages.any { it.contains("未授权") })
    }

    @Test fun unrecognizedOperationIsRejectedInsteadOfBecomingAnArbitraryCommand() {
        assertEquals(AgentRejectionCode.UNSUPPORTED_TOOL,
            (execute(AgentToolRequest.Unsupported) as AgentToolResult.Rejected).code)
    }

    @Test fun executingEveryToolNeverMutatesSnapshotOrAddsDraftToLedger() {
        val entries = mutableListOf(entry(1, 100, "2026-10-15 12:00:00"))
        val original = entries.toList()
        val reasons = mutableMapOf(AgentPendingReason.BUSINESS_TYPE to 1)
        val snapshot = AgentLedgerSnapshot(entries, 1, reasons)
        listOf(expense, AgentToolRequest.QuerySummary(AgentPeriod.THIS_MONTH),
            AgentToolRequest.ExplainPending, AgentToolRequest.CheckConnection, AgentToolRequest.Unsupported)
            .forEach { execute(it, snapshot) }
        assertEquals(original, entries)
        assertEquals(mapOf(AgentPendingReason.BUSINESS_TYPE to 1), reasons)
        assertEquals("Asia/Shanghai", zone.id)
    }

    @Test fun invalidSnapshotIsRejectedAndUnconfirmedRowsCannotAffectReportedAmounts() {
        val invalid = execute(
            AgentToolRequest.QuerySummary(AgentPeriod.TODAY), AgentLedgerSnapshot(
                listOf(entry(1, -1, "2026-10-15 12:00:00")))) as AgentToolResult.Rejected
        assertEquals(AgentRejectionCode.INVALID_SNAPSHOT, invalid.code)
        val result = execute(AgentToolRequest.QuerySummary(AgentPeriod.TODAY), AgentLedgerSnapshot(
            listOf(entry(1, 100, "2026-10-15 12:00:00").copy(needsReview = true))))
            as AgentToolResult.Summary
        assertEquals(0, result.entryCount)
        assertEquals(0L, result.netExpenseCents)
        assertEquals(emptyMap<String, Long>(), result.expenseByCategory)
    }

    private fun execute(request: AgentToolRequest, snapshot: AgentLedgerSnapshot = AgentLedgerSnapshot()) =
        LedgerAgentTools.execute(request, snapshot, now, zone)

    private fun entry(
        id: Long,
        amount: Long,
        date: String,
        type: EntryType = EntryType.EXPENSE,
        business: BusinessType = if (type == EntryType.EXPENSE) BusinessType.EXPENSE else BusinessType.INCOME,
        category: String = "其他",
        timeZone: TimeZone = zone,
    ) = LedgerEntry(id = id, amountCents = amount, type = type, businessType = business,
        category = category, occurredAt = instant(date, timeZone))

    private fun instant(date: String, timeZone: TimeZone = zone): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
            this.timeZone = timeZone
            isLenient = false
        }.parse(date)!!.time
}
