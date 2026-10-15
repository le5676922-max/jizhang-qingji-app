package com.example.jizhang.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.jizhang.agent.AgentReply
import com.example.jizhang.domain.EntryType
import com.example.jizhang.domain.LedgerEntry
import com.example.jizhang.ui.theme.JizhangTheme
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic callbacks only. No real API key, provider request, settings or ledger database is used. */
@RunWith(AndroidJUnit4::class)
class AgentPanelInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun configurationLoadingCannotBeMistakenForLocalModeOrSendAnInput() {
        val gate = CountDownLatch(1)
        val settings = FakeSettings().apply { configurationGate = gate }
        val calls = AtomicInteger()
        try {
            panel(settings, onAsk = { _, _ -> calls.incrementAndGet(); AgentReply("不应请求") })
            composeRule.onNodeWithTag("agent-example-0").performScrollTo().performClick()
            composeRule.onNodeWithTag("agent-settings").performScrollTo().assertIsNotEnabled()
            composeRule.onNodeWithTag("agent-send").performScrollTo().assertIsNotEnabled()
                .assertTextContains("设置加载中", substring = true)
            composeRule.onNodeWithText("本地查询").assertDoesNotExist()
            assertEquals(0, calls.get())
        } finally { gate.countDown() }
    }

    @Test fun failedConfigurationCannotBeMistakenForLocalModeOrSendAnInput() {
        val settings = FakeSettings().apply { failRead = true }
        val calls = AtomicInteger()
        panel(settings, onAsk = { _, _ -> calls.incrementAndGet(); AgentReply("不应请求") })
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("DeepSeek 设置读取失败").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("agent-example-0").performScrollTo().performClick()
        composeRule.onNodeWithTag("agent-send").performScrollTo().assertIsNotEnabled()
            .assertTextContains("设置读取失败", substring = true)
        composeRule.onNodeWithText("本地查询").assertDoesNotExist()
        assertEquals(0, calls.get())
        composeRule.onNodeWithTag("agent-settings").performScrollTo().assertIsEnabled().performClick()
        composeRule.onNodeWithTag("agent-key-status").performScrollTo().assertTextContains("设置状态未知", substring = true)
        composeRule.onNodeWithText("尚未保存 API Key").assertDoesNotExist()
        composeRule.onNodeWithTag("agent-enable").performScrollTo().performClick()
        composeRule.onNodeWithTag("agent-settings-save").performScrollTo().performClick()
        composeRule.onNodeWithTag("agent-settings-error").performScrollTo().assertTextContains("填写 API Key", substring = true)
        assertEquals(0, settings.saves.get())
    }

    @Test fun examplesDoNotSendUntilClickedAndDraftOnlyOpensOnceForHumanConfirmation() {
        val settings = FakeSettings()
        val calls = mutableListOf<Pair<String, Boolean>>()
        val opened = mutableListOf<LedgerEntry>()
        val draft = LedgerEntry(amountCents = 2850, type = EntryType.EXPENSE, category = "餐饮", note = "合成午餐")
        panel(settings, onAsk = { prompt, sharing ->
            calls += prompt to sharing
            AgentReply("请核对这笔草稿。", draft, listOf("生成草稿"), true, "合成本地报告")
        }, onDraft = { opened += it })
        composeRule.onNodeWithTag("agent-example-1").performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(calls.isEmpty()); assertTrue(opened.isEmpty()) }
        composeRule.onNodeWithTag("agent-send").performScrollTo().performClick()
        awaitNode("agent-draft-1")
        composeRule.onNodeWithTag("agent-tools-1").performScrollTo().assertTextContains("生成草稿", substring = true)
        composeRule.onNodeWithTag("agent-report-1").performScrollTo().assertTextContains("合成本地报告")
        composeRule.onNodeWithText("待核对草稿 · 尚未入账").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(listOf("昨天午餐28.50元" to false), calls)
            assertTrue(opened.isEmpty())
        }
        composeRule.onNodeWithTag("agent-draft-1").performScrollTo().performClick().assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(listOf(draft), opened) }
    }

    @Test fun summarySharingRequiresAnExplicitChoiceForEachRequest() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        panel(FakeSettings(), onAsk = { prompt, sharing -> calls += prompt to sharing; AgentReply("合成回复") })
        composeRule.onNodeWithTag("agent-share-summary").performScrollTo().assertIsOff().performClick()
        submit("分析本月支出")
        awaitNode("agent-turn-1")
        composeRule.onNodeWithTag("agent-share-summary").performScrollTo().assertIsOff()
        submit("再给一个建议")
        composeRule.runOnIdle { assertEquals(listOf("分析本月支出" to true, "再给一个建议" to false), calls) }
    }

    @Test fun cancellingSuppressesLateReplyAndAllowsTheNextRequest() {
        val pending = CompletableDeferred<AgentReply>()
        val calls = AtomicInteger()
        val ended = AtomicInteger()
        panel(FakeSettings(), onAsk = { _, _ ->
            if (calls.incrementAndGet() == 1) {
                try { withContext(NonCancellable) { pending.await() } }
                finally { ended.incrementAndGet() }
            } else AgentReply("后续请求成功")
        })
        submit("合成慢请求")
        composeRule.onNodeWithTag("agent-send").assertIsNotEnabled()
        composeRule.onNodeWithTag("agent-settings").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("agent-cancel").performScrollTo().performClick()
        submit("合成后续请求")
        composeRule.runOnIdle { pending.complete(AgentReply("不应显示的迟到回复")) }
        composeRule.waitUntil(5_000) { ended.get() == 1 }
        composeRule.onNodeWithText("后续请求成功").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("不应显示的迟到回复").assertDoesNotExist()
        composeRule.onNodeWithText("已取消本次请求。").performScrollTo().assertIsDisplayed()
    }

    @Test fun leavingPanelCancelsWorkWithoutOpeningDraftOrShowingLateReply() {
        val shown = mutableStateOf(true)
        val pending = CompletableDeferred<AgentReply>()
        val entered = AtomicInteger()
        val ended = AtomicInteger()
        val opened = AtomicInteger()
        val settings = FakeSettings()
        composeRule.setContent { JizhangTheme {
            if (shown.value) AgentPanelContent(settings, onAsk = { _, _ ->
                entered.incrementAndGet()
                try { withContext(NonCancellable) { pending.await() } }
                finally { ended.incrementAndGet() }
            }, onDraft = { opened.incrementAndGet() }, onReview = {}, onConnection = {})
        } }
        awaitNode("agent-configuration")
        submit("合成离开页面请求")
        composeRule.waitUntil(5_000) { entered.get() == 1 }
        composeRule.runOnIdle {
            shown.value = false
        }
        composeRule.waitForIdle()
        pending.complete(AgentReply("迟到草稿", LedgerEntry(amountCents = 100, type = EntryType.EXPENSE, category = "其他")))
        composeRule.waitUntil(5_000) { ended.get() == 1 }
        composeRule.onNodeWithText("迟到草稿").assertDoesNotExist()
        assertEquals(0, opened.get())
    }

    @Test fun storageBlockAllowsQueriesButPreventsOpeningWriteEditor() {
        var opened = 0
        panel(FakeSettings(), writingBlocked = true, onAsk = { _, _ ->
            AgentReply("草稿尚未保存", LedgerEntry(amountCents = 100, type = EntryType.EXPENSE, category = "其他"))
        }, onDraft = { opened++ })
        submit("合成草稿请求")
        awaitNode("agent-draft-1")
        composeRule.onNodeWithTag("agent-draft-1").performScrollTo().assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(0, opened) }
    }

    @Test fun unconfiguredAccountClearlyUsesLocalQueryAndLargeFontsKeepActionsReachable() {
        val settings = FakeSettings(AgentUiConfiguration(false, "deepseek-flash", false))
        var calls = 0
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                JizhangTheme {
                    Box(Modifier.width(320.dp).fillMaxHeight().testTag("agent-test-viewport")) {
                        AgentPanelContent(settings, onAsk = { _, sharing ->
                            assertFalse(sharing); calls++; AgentReply("本地收支合成汇总")
                        }, onDraft = {}, onReview = {}, onConnection = {})
                    }
                }
            }
        }
        composeRule.onNodeWithTag("agent-example-0").performScrollTo().performClick()
        val action = composeRule.onNodeWithTag("agent-send").performScrollTo().assertTextContains("本地查询", substring = true)
        val bounds = action.fetchSemanticsNode().boundsInRoot
        val viewport = composeRule.onNodeWithTag("agent-test-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right)
        action.performClick()
        composeRule.onNodeWithText("本地收支合成汇总").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun settingsNeverRevealExistingKeyAndBlankReplacementPreservesIt() {
        val settings = FakeSettings()
        val saved = AtomicInteger()
        settingsDialog(settings, onSaved = { saved.incrementAndGet() })
        composeRule.onNodeWithTag("agent-key-status").assertTextContains("已保存", substring = true)
        assertEquals("", editableKey())
        composeRule.onNodeWithTag("agent-settings-save").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { saved.get() == 1 }
        assertNull(settings.lastSubmittedKey)
        assertTrue(settings.current.hasKey)
    }

    @Test fun enablingWithoutKeyDoesNotSaveUntilKeyIsProvided() {
        val settings = FakeSettings(AgentUiConfiguration(false, "deepseek-flash", false))
        settingsDialog(settings)
        composeRule.onNodeWithTag("agent-enable").performScrollTo().performClick()
        composeRule.onNodeWithTag("agent-settings-save").performScrollTo().performClick()
        composeRule.onNodeWithTag("agent-settings-error").performScrollTo().assertTextContains("填写 API Key", substring = true)
        assertEquals(0, settings.saves.get())
        composeRule.onNodeWithTag("agent-api-key").performScrollTo().performTextReplacement("synthetic-not-a-real-key")
        closeSoftKeyboard()
        composeRule.onNodeWithTag("agent-settings-save").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { settings.saves.get() == 1 }
        assertTrue(settings.current.enabled)
        assertTrue(settings.current.hasKey)
    }

    @Test fun clearingSavedKeyDisablesCloudAndRemovesTheStoredKey() {
        val settings = FakeSettings()
        settingsDialog(settings)
        composeRule.onNodeWithTag("agent-settings-clear").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { settings.clears.get() == 1 }
        composeRule.onNodeWithTag("agent-key-status").performScrollTo().assertTextContains("尚未保存 API Key")
        composeRule.onNodeWithTag("agent-enable").performScrollTo().assertIsOff()
        assertFalse(settings.current.enabled)
        assertFalse(settings.current.hasKey)
    }

    @Test fun settingsFailureErasesTypedKeyAndDoesNotExposeExceptionDetails() {
        val settings = FakeSettings().apply { failSave = true }
        settingsDialog(settings)
        composeRule.onNodeWithTag("agent-api-key").performScrollTo().performTextReplacement("synthetic-not-a-real-key")
        closeSoftKeyboard()
        composeRule.onNodeWithTag("agent-settings-save").performScrollTo().performClick()
        awaitNode("agent-settings-error")
        composeRule.onNodeWithTag("agent-settings-error").performScrollTo().assertTextContains("保存失败", substring = true)
        assertEquals("", editableKey())
        composeRule.onNodeWithText("SYNTHETIC_PRIVATE_EXCEPTION_DETAIL", substring = true).assertDoesNotExist()
    }

    @Test fun conversationDropsOldestTurnAfterTwelveRequests() {
        panel(FakeSettings(), onAsk = { _, _ -> AgentReply("合成回复") })
        repeat(13) { index -> submit("合成请求 ${index + 1}") }
        composeRule.onNodeWithTag("agent-turn-1").assertDoesNotExist()
        composeRule.onNodeWithTag("agent-turn-2").assertExists()
        composeRule.onNodeWithTag("agent-turn-13").assertExists()
    }

    private fun panel(
        settings: FakeSettings,
        writingBlocked: Boolean = false,
        onAsk: suspend (String, Boolean) -> AgentReply,
        onDraft: (LedgerEntry) -> Unit = {},
    ) {
        composeRule.setContent { JizhangTheme {
            AgentPanelContent(settings, writingBlocked, onAsk, onDraft, {}, {})
        } }
        awaitNode("agent-configuration")
        composeRule.waitUntil(5_000) { settings.configurationReads.get() > 0 }
        composeRule.waitForIdle()
    }

    private fun settingsDialog(settings: FakeSettings, onSaved: (AgentUiConfiguration) -> Unit = {}) {
        composeRule.setContent { JizhangTheme { AgentSettingsDialog(settings, settings.current, onSaved, {}) } }
    }

    private fun submit(text: String) {
        composeRule.onNodeWithTag("agent-input").performScrollTo().performTextReplacement(text)
        closeSoftKeyboard()
        composeRule.onNodeWithTag("agent-send").performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    private fun awaitNode(tag: String) {
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun editableKey(): String = composeRule.onNodeWithTag("agent-api-key").fetchSemanticsNode()
        .config[SemanticsProperties.EditableText].text

    private class FakeSettings(
        @Volatile var current: AgentUiConfiguration = AgentUiConfiguration(true, "deepseek-flash", true),
    ) : AgentSettingsAccess {
        val configurationReads = AtomicInteger()
        val saves = AtomicInteger()
        val clears = AtomicInteger()
        @Volatile var lastSubmittedKey: String? = null
        var failSave = false
        var failRead = false
        var configurationGate: CountDownLatch? = null
        override fun configuration(): AgentUiConfiguration {
            configurationReads.incrementAndGet()
            configurationGate?.await(10, TimeUnit.SECONDS)
            if (failRead) error("SYNTHETIC_PRIVATE_EXCEPTION_DETAIL")
            return current
        }
        override fun save(apiKey: String?, model: String, enabled: Boolean) {
            if (failSave) error("SYNTHETIC_PRIVATE_EXCEPTION_DETAIL")
            lastSubmittedKey = apiKey
            current = AgentUiConfiguration(enabled, model, current.hasKey || !apiKey.isNullOrBlank())
            saves.incrementAndGet()
        }
        override fun clear() {
            current = AgentUiConfiguration(false, "deepseek-flash", false)
            clears.incrementAndGet()
        }
    }
}
