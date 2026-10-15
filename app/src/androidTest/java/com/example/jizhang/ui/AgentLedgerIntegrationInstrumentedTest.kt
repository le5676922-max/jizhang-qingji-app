package com.example.jizhang.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.jizhang.agent.*
import com.example.jizhang.data.LedgerRepository
import com.example.jizhang.domain.BusinessType
import com.example.jizhang.domain.EntrySource
import com.example.jizhang.domain.EntryType
import com.example.jizhang.ui.theme.JizhangTheme
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A synthetic model result must go through the real editor and repository, with no API key. */
@RunWith(AndroidJUnit4::class)
class AgentLedgerIntegrationInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var context: Context
    private lateinit var repository: LedgerRepository
    private lateinit var databaseName: String
    private lateinit var preferencesName: String
    @Volatile private var snapshotRowCount = -1
    private val requestCount = AtomicInteger()

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        databaseName = "agent-editor-$suffix.db"
        preferencesName = "agent-editor-$suffix"
        repository = LedgerRepository.isolated(context, databaseName, preferencesName)
        runBlocking { repository.retryPendingRestore() }
        composeRule.setContent {
            JizhangTheme {
                LedgerApp(repositoryOverride = repository, agentRequestOverride = { prompt, snapshot, shared ->
                    assertFalse(shared)
                    snapshotRowCount = snapshot.entries.size
                    requestCount.incrementAndGet()
                    if (prompt == "本月收支") AgentReply(render(LedgerAgentTools.execute(
                        AgentToolRequest.QuerySummary(AgentPeriod.THIS_MONTH), snapshot)))
                    else {
                        val result = LedgerAgentTools.execute(AgentToolRequest.PrepareEntry(
                            "28.50", EntryType.EXPENSE, BusinessType.EXPENSE, "餐饮", "合成午餐")) as AgentToolResult.Draft
                        AgentReply("草稿已准备好，尚未入账。", draft = result.entry, usedCloud = true)
                    }
                })
            }
        }
        composeRule.onNodeWithText("助手").performClick()
    }

    @After fun tearDown() = runBlocking {
        composeRule.waitForIdle()
        repository.closeForTesting()
        context.deleteDatabase(databaseName)
        context.deleteSharedPreferences(preferencesName)
        Unit
    }

    private fun send(text: String) {
        val before = requestCount.get()
        composeRule.onNodeWithTag("agent-input").performScrollTo().performTextReplacement(text)
        closeSoftKeyboard()
        composeRule.onNodeWithTag("agent-send").performScrollTo().assertIsEnabled().performClick()
        composeRule.waitForIdle()
        // Main-thread idleness does not wait for the snapshot's Dispatchers.IO read.
        try {
            composeRule.waitUntil(10_000) { requestCount.get() > before }
        } catch (failure: Exception) {
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(context.getExternalFilesDir(null), "agent-send-timeout.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            throw AssertionError("Request did not start. " + composeRule.onRoot(useUnmergedTree = true).printToString(), failure)
        }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("agent-cancel").fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitForIdle()
    }

    @Test fun draftNeedsEditorSaveAndNextQueryUsesCurrentLedger() {
        send("午餐28.50元")
        composeRule.runOnIdle { assertTrue(repository.entries.value.isEmpty()); assertEquals(0, snapshotRowCount) }
        composeRule.onNodeWithTag("agent-draft-1").performScrollTo().performClick()
        composeRule.onNodeWithText("记一笔").assertIsDisplayed()
        composeRule.onNodeWithText("删除这笔账单").assertDoesNotExist()
        composeRule.runOnIdle { assertTrue(repository.entries.value.isEmpty()) }
        composeRule.onNode(hasText("金额（元）") and hasSetTextAction()).performScrollTo().performTextReplacement("25.30")
        closeSoftKeyboard()
        composeRule.onNodeWithTag("entry-save").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { repository.entries.value.size == 1 }
        composeRule.runOnIdle {
            val saved = repository.entries.value.single()
            assertTrue(saved.id > 0)
            assertEquals(2_530L, saved.amountCents)
            assertEquals(EntrySource.MANUAL, saved.source)
            assertNull(saved.fingerprint)
            assertFalse(saved.needsReview)
            assertTrue(saved.editedByUser)
        }
        // The save Snackbar can cover the scrolled send button. Dismiss its feedback before
        // the second real click; an idle main thread alone does not mean that click was received.
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("账单已保存").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss) and
            hasAnyDescendant(hasText("账单已保存")), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.Dismiss) { assertTrue(it()) }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("账单已保存").fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithTag("agent-draft-1").performScrollTo().assertIsNotEnabled()
        send("本月收支")
        composeRule.runOnIdle {
            assertEquals("Second request must read the saved row", 1, snapshotRowCount)
            assertEquals("Read-only query must preserve the saved row", 1, repository.entries.value.size)
        }
        composeRule.onNodeWithText("净支出 ¥25.30", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("概览").performClick()
        composeRule.onNodeWithTag("monthly-expense").assertTextEquals("¥ 25.30")
    }

    @Test fun closingDraftEditorLeavesLedgerUntouched() {
        send("午餐28.50元")
        composeRule.onNodeWithTag("agent-draft-1").performScrollTo().performClick()
        composeRule.onNodeWithText("关闭").performClick()
        composeRule.runOnIdle { assertTrue(repository.entries.value.isEmpty()) }
        composeRule.onNodeWithTag("agent-draft-1").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("概览").performClick()
        composeRule.onNodeWithTag("monthly-expense").assertTextEquals("¥ 0.00")
    }
}
