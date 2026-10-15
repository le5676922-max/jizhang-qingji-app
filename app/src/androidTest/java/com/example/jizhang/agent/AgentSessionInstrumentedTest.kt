package com.example.jizhang.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.jizhang.agent.deepseek.DeepSeekCompletion
import com.example.jizhang.agent.deepseek.DeepSeekModels
import com.example.jizhang.agent.deepseek.DeepSeekToolCall
import com.example.jizhang.agent.deepseek.DeepSeekTransport
import com.example.jizhang.domain.BusinessType
import com.example.jizhang.domain.EntrySource
import com.example.jizhang.domain.EntryType
import com.example.jizhang.domain.LedgerEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar
import java.util.Locale

/** Pure in-memory provider replies; no API key, network request or repository write is used. */
@RunWith(AndroidJUnit4::class)
class AgentSessionInstrumentedTest {
    private val now = Calendar.getInstance(Locale.ROOT).apply {
        clear()
        set(2026, Calendar.OCTOBER, 15, 12, 0, 0)
    }.timeInMillis

    @Test fun onePreparedEntryIsOnlyAnUnsavedManualDraft() = runBlocking {
        val original = snapshot()
        val unchanged = original.entries.toList()
        val transport = FakeTransport(listOf(completion(call("prepare_entry", entryArguments("28.56")))))
        val reply = session(transport, original, prompt = "今天午餐花了 28.56 元")
        val draft = reply.draft!!
        assertEquals(2_856L, draft.amountCents)
        assertEquals(EntrySource.MANUAL, draft.source)
        assertEquals(0L, draft.id)
        assertNull(draft.fingerprint)
        assertNull(draft.relatedEntryId)
        assertFalse(draft.needsReview)
        assertTrue(reply.message.contains("尚未入账"))
        assertEquals(listOf("本地校验记账草稿"), reply.toolSteps)
        assertEquals(unchanged, original.entries)
        assertEquals(1, transport.requests.size)
    }

    @Test fun invalidAmountsCannotBecomeDrafts() = runBlocking {
        listOf("0", "-1", "1.001", "1e3", "NaN", "9999999999999999999999999", "付款 30").forEach { amount ->
            val transport = FakeTransport(listOf(completion(call("prepare_entry", entryArguments(amount)))))
            val reply = session(transport)
            assertNull("Rejected amount: $amount", reply.draft)
            assertEquals(1, transport.requests.size)
            assertTrue(reply.message.contains("金额"))
        }
    }

    @Test fun unknownToolAndRefundCannotModifyOrPrepareFinancialRows() = runBlocking {
        val unknown = FakeTransport(listOf(completion(call("delete_all_entries", "{}"))))
        val unknownReply = session(unknown)
        assertNull(unknownReply.draft)
        assertTrue(unknownReply.message.contains("不支持"))
        assertEquals(listOf("拒绝不受支持的工具"), unknownReply.toolSteps)
        val refundArguments = JSONObject(entryArguments("2.34"))
            .put("business_type", "REFUND").put("direction", "INCOME").toString()
        val refund = FakeTransport(listOf(completion(call("prepare_entry", refundArguments))))
        val refundReply = session(refund)
        assertNull(refundReply.draft)
        assertTrue(refundReply.message.contains("关联"))
        assertEquals(1, refund.requests.size)
    }

    @Test fun decoderRejectsNumbersDuplicateKeysExtraKeysAndTrailingData() {
        val numeric = JSONObject(entryArguments("12.34")).put("amount", 12.34).toString()
        val duplicate = entryArguments("12.34").dropLast(1) + ",\"amount\":\"99.00\"}"
        val escapedDuplicate = entryArguments("12.34").dropLast(1) + ",\"\\u0061mount\":\"99.00\"}"
        val extra = JSONObject(entryArguments("12.34")).put("execute_sql", "DROP TABLE entries").toString()
        listOf(numeric, duplicate, escapedDuplicate, extra, "${entryArguments("12.34")} {}", "[]", "null").forEach { raw ->
            assertEquals(AgentToolRequest.Unsupported, decodeTool("prepare_entry", raw))
        }
        assertEquals(AgentToolRequest.Unsupported, decodeTool("query_summary", "{\"period\":4}"))
        assertEquals(AgentToolRequest.Unsupported, decodeTool("query_summary", "{\"period\":\"THIS_MONTH\",\"period\":\"TODAY\"}"))
        assertEquals(AgentToolRequest.Unsupported, decodeTool("check_connection", "{\"reason\":\"all\"}"))
    }

    @Test fun decoderValidatesEnumsRequiredFieldsAndFlatStringsWithoutCoercion() {
        val valid = decodeTool("prepare_entry", entryArguments("12.34")) as AgentToolRequest.PrepareEntry
        assertEquals("12.34", valid.amount)
        assertEquals(EntryType.EXPENSE, valid.direction)
        assertEquals(BusinessType.EXPENSE, valid.businessType)
        assertEquals("", valid.merchant)
        assertEquals("", valid.date)
        assertEquals(AgentToolRequest.QuerySummary(AgentPeriod.THIS_MONTH), decodeTool("query_summary", "{\"period\":\"THIS_MONTH\"}"))
        val invalid = listOf(
            JSONObject(entryArguments()).put("direction", "OUTGOING"),
            JSONObject(entryArguments()).put("business_type", "DELETE"),
            JSONObject(entryArguments()).put("note", JSONObject().put("nested", true)),
            JSONObject(entryArguments()).put("note", JSONObject.NULL),
            JSONObject(entryArguments()).apply { remove("category") },
        )
        invalid.forEach { assertEquals(AgentToolRequest.Unsupported, decodeTool("prepare_entry", it.toString())) }
        assertEquals(AgentToolRequest.Unsupported, decodeTool("query_summary", "{\"period\":\"ALL_HISTORY\"}"))
        assertEquals(AgentToolRequest.Unsupported, decodeTool("open_url", "{}"))
    }

    @Test fun defaultRequestNeverUploadsLedgerRowsAndStopsBeforeSharingToolResult() = runBlocking {
        val transport = FakeTransport(listOf(completion(call("query_summary", "{\"period\":\"THIS_MONTH\"}"))))
        val reply = session(transport, snapshot(), share = false)
        assertNull(reply.draft)
        assertTrue(reply.message.contains("本地核算"))
        assertTrue(reply.message.contains("66.78"))
        assertEquals(1, transport.requests.size)
        assertNull(reply.localReport)
        val initial = transport.requests.single().toString()
        assertFalse(initial.contains(MERCHANT_SECRET))
        assertFalse(initial.contains(NOTE_SECRET))
        assertFalse(initial.contains(CATEGORY_SECRET))
        assertFalse(initial.contains(FINGERPRINT_SECRET))
        assertFalse(initial.contains("66.78"))
        assertFalse(initial.contains(RECORD_ID.toString()))
        assertEquals(listOf("system", "user"), roles(transport.requests.single()))
    }

    @Test fun summaryOptInSharesOnlyBoundedAggregateAndFixedCategoriesForNextRound() = runBlocking {
        val transport = FakeTransport(listOf(
            completion(call("query_summary", "{\"period\":\"THIS_MONTH\"}")),
            DeepSeekCompletion("请参考本地核算结果。", emptyList(), "stop"),
        ))
        val reply = session(transport, snapshot(), share = true)
        assertEquals(2, transport.requests.size)
        assertTrue(reply.usedCloud)
        assertTrue(reply.localReport.orEmpty().contains("66.78"))
        val toolResult = transport.requests[1].getJSONObject(3)
        assertEquals("tool", toolResult.getString("role"))
        val aggregate = JSONObject(toolResult.getString("content"))
        assertEquals(setOf("period", "start_date", "end_date_exclusive", "income_yuan", "gross_expense_yuan",
            "refund_yuan", "net_expense_yuan", "balance_yuan", "confirmed_record_count", "categories_yuan"), aggregate.keys().asSequence().toSet())
        assertEquals("100.00", aggregate.getString("income_yuan"))
        assertEquals("69.12", aggregate.getString("gross_expense_yuan"))
        assertEquals("2.34", aggregate.getString("refund_yuan"))
        assertEquals("66.78", aggregate.getString("net_expense_yuan"))
        assertEquals("33.22", aggregate.getString("balance_yuan"))
        assertEquals(4, aggregate.getInt("confirmed_record_count"))
        val categories = aggregate.getJSONObject("categories_yuan")
        assertEquals(setOf("其他", "餐饮"), categories.keys().asSequence().toSet())
        val sent = transport.requests[1].toString()
        listOf(MERCHANT_SECRET, NOTE_SECRET, CATEGORY_SECRET, FINGERPRINT_SECRET, RECORD_ID.toString()).forEach {
            assertFalse("Private field leaked: $it", sent.contains(it))
        }
    }

    @Test fun pendingOptInUsesOnlyFixedReasonsAndNeverSharesConnectionSnapshot() = runBlocking {
        val pending = FakeTransport(listOf(
            completion(call("explain_pending", "{}")), DeepSeekCompletion("请逐笔核对。", emptyList(), "stop"),
        ))
        session(pending, snapshot(), share = true)
        val aggregate = JSONObject(pending.requests[1].getJSONObject(3).getString("content"))
        assertEquals(setOf("pending_count", "reason_counts"), aggregate.keys().asSequence().toSet())
        assertEquals(2, aggregate.getInt("pending_count"))
        assertEquals(setOf(AgentPendingReason.AMOUNT.label), aggregate.getJSONObject("reason_counts").keys().asSequence().toSet())
        val connection = FakeTransport(listOf(completion(call("check_connection", "{}"))))
        val reply = session(connection, snapshot(), share = true)
        assertEquals(1, connection.requests.size)
        assertTrue(reply.message.contains("尚未连接"))
        assertFalse(connection.requests.single().toString().contains("screenConnected"))
    }

    @Test fun multipleEntriesOrCombinedDraftAndQueryAreRejectedWithoutDraft() = runBlocking {
        val combinations = listOf(
            listOf(call("prepare_entry", entryArguments(), "draft_1"), call("prepare_entry", entryArguments(), "draft_2")),
            listOf(call("query_summary", "{\"period\":\"TODAY\"}", "query"), call("prepare_entry", entryArguments(), "draft")),
        )
        combinations.forEach { calls ->
            val transport = FakeTransport(listOf(DeepSeekCompletion(null, calls, "tool_calls")))
            val reply = session(transport, share = true)
            assertNull(reply.draft)
            assertTrue(reply.message.contains("一笔"))
            assertTrue(reply.toolSteps.isEmpty())
            assertEquals(1, transport.requests.size)
        }
    }

    @Test fun totalToolBudgetAndPerCompletionBudgetStopBeforeExtraExecution() = runBlocking {
        fun queries(count: Int, prefix: String) = DeepSeekCompletion(null, (0 until count).map {
            call("query_summary", "{\"period\":\"TODAY\"}", "$prefix$it")
        }, "tool_calls")
        val first = FakeTransport(listOf(queries(5, "excess")))
        val firstReply = session(first, share = true)
        assertTrue(firstReply.message.contains("上限"))
        assertTrue(firstReply.toolSteps.isEmpty())
        val total = FakeTransport(listOf(queries(2, "first"), queries(3, "second")))
        val totalReply = session(total, share = true)
        assertEquals(2, total.requests.size)
        assertEquals(2, totalReply.toolSteps.size)
        assertTrue(totalReply.message.contains("上限"))
        assertNotNull(totalReply.localReport)
    }

    @Test fun modelRoundBudgetTerminatesRepeatedReadOnlyQueries() = runBlocking {
        val transport = FakeTransport((0 until 3).map { round ->
            completion(call("query_summary", "{\"period\":\"THIS_MONTH\"}", "query_$round"))
        })
        val reply = session(transport, snapshot(), share = true)
        assertEquals(3, transport.requests.size)
        assertEquals(3, reply.toolSteps.size)
        assertNull(reply.draft)
        assertTrue(reply.message.contains("轮数上限"))
        assertTrue(reply.localReport.orEmpty().contains("本地核算"))
    }

    @Test fun cancellationPropagatesRatherThanBecomingSuccessfulReply() = runBlocking {
        var requests = 0
        val transport = object : DeepSeekTransport {
            override suspend fun complete(apiKey: String, model: String, messages: JSONArray, tools: JSONArray): DeepSeekCompletion {
                requests++
                throw CancellationException("synthetic cancellation")
            }
        }
        try {
            LedgerAgentSession(transport).run(KEY, DeepSeekModels.DEFAULT, "午餐 28 元", snapshot(), false, now)
            fail("Cancellation must reach the request owner")
        } catch (_: CancellationException) { /* expected */ }
        assertEquals(1, requests)
    }

    @Test fun unverifiableModelClaimsOfSavingPayingDeletingOrGrantingAreBlocked() = runBlocking {
        listOf("已保存这笔账单。", "已经记账了。", "已经帮你保存。", "已成功记账。", "已\n保存。",
            "已删除记录。", "已支付成功。", "已开启服务。", "已重连。").forEach { text ->
            val transport = FakeTransport(listOf(DeepSeekCompletion(text, emptyList(), "stop")))
            val reply = session(transport)
            assertNull(reply.draft)
            assertTrue(reply.message.contains("没有保存或修改"))
            assertFalse(reply.message == text)
            assertTrue(reply.message.contains("以上为模型建议"))
        }
    }

    @Test fun freeTextAlwaysHasLocalAccountingAndNoAutomaticWriteBoundary() = runBlocking {
        val text = "建议每周核对一次账单。"
        val transport = FakeTransport(listOf(DeepSeekCompletion(text, emptyList(), "stop")))
        val reply = session(transport)
        assertTrue(reply.message.startsWith(text))
        assertTrue(reply.message.contains("账本数字以本地核算为准"))
        assertTrue(reply.message.endsWith("助手没有自动保存或修改账本。"))
        assertTrue(reply.toolSteps.isEmpty())
        assertNull(reply.draft)
    }

    private suspend fun session(transport: FakeTransport, snapshot: AgentLedgerSnapshot = snapshot(),
                                share: Boolean = false, prompt: String = "本月收支"): AgentReply =
        LedgerAgentSession(transport).run(KEY, DeepSeekModels.DEFAULT, prompt, snapshot, share, now)

    private fun snapshot() = AgentLedgerSnapshot(
        entries = listOf(
            LedgerEntry(RECORD_ID, 1_234, EntryType.EXPENSE, CATEGORY_SECRET, MERCHANT_SECRET, NOTE_SECRET,
                now, EntrySource.WECHAT, FINGERPRINT_SECRET),
            LedgerEntry(RECORD_ID + 1, 5_678, EntryType.EXPENSE, "餐饮", occurredAt = now),
            LedgerEntry(RECORD_ID + 2, 10_000, EntryType.INCOME, "工资", occurredAt = now),
            LedgerEntry(RECORD_ID + 3, 234, EntryType.INCOME, "退款", occurredAt = now,
                businessType = BusinessType.REFUND, relatedEntryId = RECORD_ID),
            LedgerEntry(RECORD_ID + 4, 88_900, EntryType.EXPENSE, "购物", occurredAt = now, needsReview = true),
        ),
        pendingCount = 2,
        pendingReasonCounts = mapOf(AgentPendingReason.AMOUNT to 2),
        connection = AgentConnectionSnapshot(screenAuthorized = true, screenConnected = false),
    )

    private fun roles(messages: JSONArray) = (0 until messages.length()).map { messages.getJSONObject(it).getString("role") }
    private fun entryArguments(amount: String = "12.34") = JSONObject().put("amount", amount)
        .put("direction", "EXPENSE").put("business_type", "EXPENSE").put("category", "餐饮").toString()
    private fun call(name: String, arguments: String, id: String = "call_1") = DeepSeekToolCall(id, name, arguments)
    private fun completion(call: DeepSeekToolCall) = DeepSeekCompletion(null, listOf(call), "tool_calls")

    private class FakeTransport(private val completions: List<DeepSeekCompletion>) : DeepSeekTransport {
        val requests = mutableListOf<JSONArray>()
        override suspend fun complete(apiKey: String, model: String, messages: JSONArray, tools: JSONArray): DeepSeekCompletion {
            // Deep copy each round; the agent owns and continues to append to its working array.
            requests.add(JSONArray(messages.toString()))
            assertEquals(KEY, apiKey)
            assertEquals(DeepSeekModels.DEFAULT, model)
            val names = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }
            assertEquals(setOf("prepare_entry", "query_summary", "explain_pending", "check_connection"), names.toSet())
            if (requests.size > completions.size) throw AssertionError("Unexpected extra model request")
            return completions[requests.size - 1]
        }
    }

    companion object {
        private const val KEY = "synthetic-session-key-never-sent"
        private const val MERCHANT_SECRET = "PRIVATE_MERCHANT_7fa"
        private const val NOTE_SECRET = "PRIVATE_NOTE_81d"
        private const val CATEGORY_SECRET = "PRIVATE_CUSTOM_CATEGORY_5ac"
        private const val FINGERPRINT_SECRET = "PRIVATE_FINGERPRINT_c31"
        private const val RECORD_ID = 8_765_432_109_876L
    }
}
