package com.example.jizhang.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.jizhang.data.LedgerRepository
import com.example.jizhang.domain.EntrySource
import com.example.jizhang.domain.EntryType
import com.example.jizhang.domain.BusinessType
import com.example.jizhang.domain.LedgerEntry
import com.example.jizhang.domain.ScreenPaymentParser
import com.example.jizhang.recognition.CandidateFactory
import com.example.jizhang.recognition.EvidenceType
import com.example.jizhang.recognition.RecognitionDecision
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
        runBlocking { repository.retryPendingRestore() }
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
        runBlocking { repository.ingest(CandidateFactory.fromScreen(candidate, System.currentTimeMillis(), EvidenceType.IMPORTED_IMAGE, userInitiated = true)) }
        composeRule.waitUntil(5_000) { repository.recognitionEvents.value.size == 1 }
        composeRule.runOnIdle {
            assertTrue(repository.entries.value.isEmpty())
            assertEquals(RecognitionDecision.REVIEW, repository.recognitionEvents.value.single().decision)
        }
        composeRule.onNodeWithTag("monthly-expense").assertTextEquals("¥ 0.00")
        composeRule.onNodeWithText("自动记账").performClick()
        composeRule.onNodeWithText("查看待确认账单").performScrollTo().performClick()
        composeRule.onNodeWithText("核对并确认").performScrollTo().performClick()
        composeRule.onNodeWithTag("recognition-business-EXPENSE").performScrollTo().performClick()
        composeRule.onNodeWithTag("recognition-confirm").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { repository.entries.value.singleOrNull()?.businessType == BusinessType.EXPENSE }
        composeRule.runOnIdle { assertFalse(repository.entries.value.single().needsReview) }
        composeRule.onNodeWithText("概览").performClick()
        composeRule.onNodeWithTag("monthly-expense").assertTextEquals("¥ 12.34")
    }

    @Test
    fun transferIsExcludedFromConsumptionAndEditingDirectionChangesIncomeBusiness() {
        openEntryEditor()
        composeRule.onNodeWithTag("entry-business-TRANSFER").performScrollTo().performClick()
        enterText("金额（元）", "86.00")
        enterText("商户 / 对方（选填）", "往来测试")
        saveEntry()
        composeRule.waitUntil(5_000) { repository.entries.value.size == 1 }
        composeRule.onNodeWithTag("monthly-expense").assertTextEquals("¥ 0.00")
        val id = repository.entries.value.single().id
        composeRule.onNodeWithText("账单").performClick()
        composeRule.onNodeWithTag("ledger-entry-$id").performScrollTo().performClick()
        composeRule.onNodeWithTag("entry-business-EXPENSE").performClick()
        composeRule.onNodeWithTag("entry-direction-INCOME").performClick()
        saveEntry()
        composeRule.waitUntil(5_000) { repository.entries.value.single().businessType == BusinessType.INCOME }
        composeRule.runOnIdle { assertEquals(EntryType.INCOME, repository.entries.value.single().type) }
        composeRule.onNodeWithText("概览").performClick()
        composeRule.onNodeWithTag("monthly-expense").assertTextEquals("¥ 0.00")
        composeRule.onNodeWithTag("monthly-income").assertTextEquals("¥ 86.00").assertIsDisplayed()
    }

    @Test
    fun partialRefundRequiresOriginalConsumptionAndReducesConsumption() {
        runBlocking { repository.add(LedgerEntry(amountCents = 10_000, type = EntryType.EXPENSE, category = "购物", merchant = "原消费测试")) }
        composeRule.waitUntil(5_000) { repository.entries.value.size == 1 }
        val originalId = repository.entries.value.single().id
        openEntryEditor()
        composeRule.onNodeWithTag("entry-business-REFUND").performScrollTo().performClick()
        enterText("金额（元）", "30.00")
        closeSoftKeyboard()
        composeRule.onNodeWithTag("entry-refund-original-$originalId").performScrollTo().performClick()
        saveEntry()
        composeRule.waitUntil(5_000) { repository.entries.value.size == 2 }
        composeRule.runOnIdle {
            val refund = repository.entries.value.single { it.businessType == BusinessType.REFUND }
            assertEquals(originalId, refund.relatedEntryId)
            assertEquals(EntryType.INCOME, refund.type)
            assertEquals("购物", refund.category)
        }
        composeRule.onNodeWithTag("monthly-expense").assertTextEquals("¥ 70.00")
        composeRule.onNodeWithTag("monthly-income").assertTextEquals("¥ 0.00").assertIsDisplayed()
    }

    @Test
    fun deletedEntryCanBeRestoredFromRecycleBin() {
        runBlocking { repository.add(LedgerEntry(amountCents = 750, type = EntryType.EXPENSE, category = "餐饮", merchant = "回收测试", note = "保持备注")) }
        composeRule.waitUntil(5_000) { repository.entries.value.size == 1 }
        val id = repository.entries.value.single().id
        composeRule.onNodeWithText("账单").performClick()
        composeRule.onNodeWithTag("ledger-entry-$id").performScrollTo().performClick()
        composeRule.onNodeWithText("删除这笔账单").performScrollTo().performClick()
        composeRule.onNodeWithText("删除", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { repository.entries.value.isEmpty() && repository.deletedEntries.value.size == 1 }
        dismissFeedback("账单已移入回收站")
        composeRule.onNodeWithText("自动记账").performClick()
        composeRule.onNodeWithTag("open-recycle-bin").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.onNodeWithTag("recycle-bin").assertIsDisplayed()
        composeRule.onNodeWithTag("restore-entry-$id").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { repository.entries.value.singleOrNull()?.id == id }
        composeRule.runOnIdle {
            assertEquals("保持备注", repository.entries.value.single().note)
            assertTrue(repository.deletedEntries.value.isEmpty())
        }
        composeRule.onNodeWithText("关闭").performClick()
        composeRule.onNodeWithText("概览").performClick()
        composeRule.onNodeWithTag("monthly-expense").assertTextEquals("¥ 7.50")
    }

    @Test
    fun rememberedMerchantCategoryCanBeRemovedWithoutChangingItsBill() {
        openEntryEditor()
        enterText("金额（元）", "18.50")
        enterText("商户 / 对方（选填）", "分类规则测试")
        closeSoftKeyboard()
        composeRule.onNodeWithTag("entry-remember-category").performScrollTo().performClick()
        saveEntry()
        composeRule.waitUntil(5_000) { repository.merchantRules.value.size == 1 }
        val key = repository.merchantRules.value.single().merchantKey
        dismissFeedback("账单已保存")
        composeRule.onNodeWithText("自动记账").performClick()
        composeRule.onNodeWithTag("open-category-rules").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.onNodeWithTag("category-rules").assertIsDisplayed()
        composeRule.onNodeWithTag("delete-rule-$key").performScrollTo().performClick()
        composeRule.onNodeWithTag("confirm-delete-rule").performClick()
        composeRule.waitUntil(5_000) { repository.merchantRules.value.isEmpty() }
        composeRule.runOnIdle {
            assertEquals("餐饮", repository.entries.value.single().category)
            assertEquals(1850L, repository.entries.value.single().amountCents)
        }
    }

    private fun enterText(label: String, value: String) {
        composeRule.onNode(hasSetTextAction() and hasText(label))
            .performScrollTo().performTextReplacement(value)
    }

    private fun dismissFeedback(message: String) {
        // A bottom snackbar can cover a management button after performScrollTo positions it
        // at the viewport edge. Assert the actual feedback, then dismiss it through the host's
        // accessibility action before continuing. Its automatic timeout uses the Compose test
        // clock and can also be extended by system accessibility settings, so wall time is not
        // a reliable timeout here. Dismiss does not perform the snackbar's "撤销" action.
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText(message)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(message).assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss) and hasAnyDescendant(hasText(message)),
            useUnmergedTree = true,
        ).performSemanticsAction(SemanticsActions.Dismiss) { dismiss ->
            assertTrue("Snackbar dismissal was not handled", dismiss())
        }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText(message)).fetchSemanticsNodes().isEmpty() }
    }

    private fun saveEntry() {
        closeSoftKeyboard()
        composeRule.onNodeWithText("保存账单").performScrollTo().performClick()
    }
}
