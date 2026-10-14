package com.example.jizhang.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.jizhang.data.LedgerRepository
import com.example.jizhang.domain.EntrySource
import com.example.jizhang.domain.EntryType
import com.example.jizhang.domain.ScreenPaymentParser
import com.example.jizhang.ui.theme.JizhangTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises user flows against disposable storage, without opening the user's real ledger. */
@RunWith(AndroidJUnit4::class)
class LedgerUiInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context
    private lateinit var repository: LedgerRepository
    private lateinit var databaseName: String
    private lateinit var preferencesName: String

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        databaseName = "ledger-ui-test-$suffix.db"
        preferencesName = "ledger-ui-test-$suffix"
        repository = LedgerRepository.isolated(context, databaseName, preferencesName)
        composeRule.setContent {
            JizhangTheme { LedgerApp(repositoryOverride = repository) }
        }
    }

    @After
    fun tearDown() = runBlocking {
        composeRule.waitForIdle()
        repository.closeForTesting()
        context.deleteDatabase(databaseName)
        context.deleteSharedPreferences(preferencesName)
        Unit
    }

    @Test
    fun manualExpenseIsSavedWithExactCentsAndAppearsInTotalsAndBills() {
        openEntryEditor()
        enterText("金额（元）", "12.34")
        enterText("商户 / 对方（选填）", "测试早餐")
        enterText("备注（选填）", "豆浆和面包")
        saveEntry()

        composeRule.waitUntil(5_000) { repository.entries.value.size == 1 }
        composeRule.runOnIdle {
            val entry = repository.entries.value.single()
            assertEquals(1_234L, entry.amountCents)
            assertEquals(EntryType.EXPENSE, entry.type)
            assertEquals(EntrySource.MANUAL, entry.source)
            assertEquals("餐饮", entry.category)
            assertEquals("豆浆和面包", entry.note)
            assertFalse(entry.needsReview)
        }
        composeRule.onNodeWithTag("monthly-expense").assertTextEquals("¥ 12.34").assertIsDisplayed()
        composeRule.onNodeWithText("账单").performClick()
        composeRule.onNodeWithText("测试早餐", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("−12.34", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun overPreciseAmountShowsValidationAndDoesNotSaveAnEntry() {
        openEntryEditor()
        enterText("金额（元）", "12.345")
        saveEntry()

        composeRule.onNodeWithText(
            "请输入大于 0 的金额，最多两位小数", useUnmergedTree = true,
        ).performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(repository.entries.value.isEmpty()) }
        composeRule.onNodeWithText("账单已保存").assertDoesNotExist()
    }

    @Test
    fun incomeCategoryIsSavedAndMonthNavigationFiltersItsBill() {
        openEntryEditor()
        composeRule.onNodeWithText("收入").performClick()
        composeRule.onNodeWithText("工资").performClick()
        enterText("金额（元）", "5000.00")
        enterText("商户 / 对方（选填）", "测试工资")
        saveEntry()

        composeRule.waitUntil(5_000) { repository.entries.value.size == 1 }
        composeRule.runOnIdle {
            val entry = repository.entries.value.single()
            assertEquals(EntryType.INCOME, entry.type)
            assertEquals("工资", entry.category)
            assertEquals(500_000L, entry.amountCents)
        }
        val previousMonth = Calendar.getInstance().apply {
            timeInMillis = repository.entries.value.single().occurredAt
            add(Calendar.MONTH, -1)
        }
        val previousMonthLabel = SimpleDateFormat("yyyy年 MM月", Locale.CHINA).format(previousMonth.time)
        composeRule.onNodeWithText("账单").performClick()
        composeRule.onNodeWithText("+5000.00", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("‹").performClick()
        composeRule.onNodeWithText(previousMonthLabel, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("测试工资", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("这个月，还没有账单", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("›").performClick()
        composeRule.onNodeWithText("测试工资", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("+5000.00", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun parserPreviewLeavesTheLedgerEmptyAndAutomationDisabled() {
        composeRule.onNodeWithText("自动记账").performClick()
        composeRule.onAllNodes(isToggleable())[0].assertIsOff()
        composeRule.onNodeWithText("试试通知识别").performScrollTo().performClick()
        enterText("通知内容", "支付成功，金额12.34元，商户：测试早餐")
        closeSoftKeyboard()
        composeRule.onNodeWithText("识别").performClick()

        composeRule.onNodeWithText("支出 ¥ 12.34", substring = true, useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(repository.entries.value.isEmpty())
            assertFalse(repository.settings.value.autoEnabled)
            assertEquals(Long.MAX_VALUE, repository.automationEnabledSince())
        }
    }

    private fun openEntryEditor() {
        composeRule.onNodeWithText("记一笔").performClick()
    }

    @Test
    fun screenConsentCanBeCancelledWithoutReadingOtherApps() {
        composeRule.onNodeWithText("自动记账").performClick()
        composeRule.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        composeRule.onNodeWithText("同意并开启").assertIsDisplayed()
        composeRule.onNodeWithText("取消").performClick()
        composeRule.runOnIdle {
            assertFalse(repository.settings.value.screenEnabled)
            assertFalse(repository.settings.value.screenOcrEnabled)
        }
    }

    @Test
    fun screenshotCandidateIsExcludedUntilUserConfirmsIt() {
        val candidate = ScreenPaymentParser.parse("com.tencent.mm",
            "转账详情\n转账成功\n转账金额：12.34元\n收款人：截图测试\n交易单号：SCREENUITEST20261013", fromOcr = true)!!
        runBlocking { repository.addScreenPayment(candidate, System.currentTimeMillis(), userInitiated = true) }
        composeRule.waitUntil(5_000) { repository.entries.value.size == 1 }
        composeRule.onNodeWithTag("monthly-expense").assertTextEquals("¥ 0.00")
        composeRule.onNodeWithText("自动记账").performClick()
        composeRule.onNodeWithText("查看待确认账单").performScrollTo().performClick()
        composeRule.onNodeWithText("截图测试", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("确认并保存").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { repository.entries.value.singleOrNull()?.needsReview == false }
        composeRule.onNodeWithText("概览").performClick()
        composeRule.onNodeWithTag("monthly-expense").assertTextEquals("¥ 12.34")
    }

    private fun enterText(label: String, value: String) {
        composeRule.onNode(hasSetTextAction() and hasText(label))
            .performScrollTo().performTextReplacement(value)
    }

    private fun saveEntry() {
        closeSoftKeyboard()
        composeRule.onNodeWithText("保存账单").performScrollTo().performClick()
    }
}
