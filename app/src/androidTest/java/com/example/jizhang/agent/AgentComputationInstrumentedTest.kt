package com.example.jizhang.agent

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.jizhang.agent.deepseek.DeepSeekCompletion
import com.example.jizhang.agent.deepseek.DeepSeekModels
import com.example.jizhang.agent.deepseek.DeepSeekToolCall
import com.example.jizhang.agent.deepseek.DeepSeekTransport
import com.example.jizhang.domain.EntryType
import com.example.jizhang.domain.LedgerEntry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises a main-thread session with in-memory completions; no credentials or network. */
@RunWith(AndroidJUnit4::class)
class AgentComputationInstrumentedTest {
    @Test fun summaryTraversesLedgerAwayFromMainThread() = runBlocking {
        val visited = AtomicBoolean(false)
        val entries = object : AbstractList<LedgerEntry>() {
            override val size: Int = 1
            override fun get(index: Int): LedgerEntry {
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                visited.set(true)
                return syntheticEntry()
            }
        }
        val reply = withContext(Dispatchers.Main) {
            LedgerAgentSession(summaryTransport()).run("synthetic-not-a-key", DeepSeekModels.DEFAULT,
                "本月收支", AgentLedgerSnapshot(entries), shareSummary = false)
        }
        assertTrue(visited.get())
        assertTrue(reply.message.contains("本地核算"))
        assertTrue(reply.message.contains("12.34"))
    }

    @Test fun cancelFromMainDoesNotWaitForCalculationOrPublishItsLateReport() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val replied = AtomicBoolean(false)
        val entries = object : AbstractList<LedgerEntry>() {
            override val size: Int = 1
            override fun get(index: Int): LedgerEntry {
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return syntheticEntry()
            }
        }
        var request: Job? = null
        try {
            request = launch(Dispatchers.Main) {
                LedgerAgentSession(summaryTransport()).run("synthetic-not-a-key", DeepSeekModels.DEFAULT,
                    "本月收支", AgentLedgerSnapshot(entries), shareSummary = false)
                replied.set(true)
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            // If traversal were on main, this dispatch could not complete while it holds release.
            withTimeout(5_000) { withContext(Dispatchers.Main) { request!!.cancel() } }
            assertFalse(replied.get())
        } finally {
            release.countDown()
            withTimeout(10_000) { request?.join() }
        }
        assertFalse(replied.get())
    }

    private fun syntheticEntry() = LedgerEntry(id = 1, amountCents = 1_234,
        type = EntryType.EXPENSE, category = "其他", occurredAt = System.currentTimeMillis())

    private fun summaryTransport() = object : DeepSeekTransport {
        override suspend fun complete(apiKey: String, model: String, messages: JSONArray, tools: JSONArray) =
            DeepSeekCompletion(null, listOf(DeepSeekToolCall("synthetic-summary", "query_summary",
                "{\"period\":\"THIS_MONTH\"}")), "tool_calls")
    }
}
