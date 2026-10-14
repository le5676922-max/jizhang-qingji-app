package com.example.jizhang.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.jizhang.data.RecognitionEvent
import com.example.jizhang.domain.BusinessType
import com.example.jizhang.domain.EntrySource
import com.example.jizhang.domain.EntryType
import com.example.jizhang.domain.LedgerEntry
import com.example.jizhang.recognition.CompletionState
import com.example.jizhang.recognition.EvidenceType
import com.example.jizhang.recognition.FundsDirection
import com.example.jizhang.recognition.RecognitionDecision
import com.example.jizhang.recognition.TransactionCandidate
import com.example.jizhang.ui.theme.JizhangTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic component flows; callbacks never open a real ledger or read payment applications. */
@RunWith(AndroidJUnit4::class)
class RecognitionCenterInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val capturedEntries = mutableListOf<LedgerEntry>()
    private val restoredEvents = mutableListOf<Long>()
    private val unlinkedEvents = mutableListOf<Long>()
    private val editedEntries = mutableListOf<Long>()
    private var rememberedCategory = false

    @Test
    fun unknownDirectionAndMultipleAmountsRequireExplicitChoicesAndExactCents() {
        val event = reviewEvent(candidate().copy(businessType = BusinessType.UNKNOWN,
            direction = FundsDirection.UNKNOWN, amountOptions = listOf(1_230L, 9_900L)))
        showCenter(listOf(event))
        composeRule.onNodeWithText("核对并确认").performClick()
        assertCannotSave()

        composeRule.onNodeWithTag("recognition-business-EXPENSE").performScrollTo().performClick()
        assertCannotSave()
        composeRule.onNodeWithTag("recognition-amount-option-1230").performScrollTo().performClick()
        assertCannotSave()
        composeRule.onNodeWithTag("recognition-direction-EXPENSE").performScrollTo().performClick()
        composeRule.onNodeWithTag("recognition-amount").performScrollTo().performTextReplacement("12.345")
        closeSoftKeyboard()
        assertCannotSave()

        composeRule.onNodeWithTag("recognition-amount-option-1230").performScrollTo().performClick()
        composeRule.onNodeWithText("餐饮").performScrollTo().performClick()
        composeRule.onNodeWithText("记住这位商户的收入 / 消费分类").performScrollTo().performClick()
        composeRule.onNodeWithTag("recognition-confirm").performScrollTo().assertIsEnabled().performClick()
        composeRule.runOnIdle {
            val entry = capturedEntries.single()
            assertEquals(1_230L, entry.amountCents)
            assertEquals(BusinessType.EXPENSE, entry.businessType)
            assertEquals(EntryType.EXPENSE, entry.type)
            assertEquals("餐饮", entry.category)
            assertTrue(entry.editedByUser)
            assertTrue(rememberedCategory)
        }
    }

    @Test
    fun refundRequiresAnOriginalExpenseWithEnoughRemainingRefundAmount() {
        val eligible = expense(20, 5_000, "合成商店", "购物")
        val insufficient = expense(30, 3_000, "额度不足的合成消费", "其他")
        val unconfirmed = expense(40, 10_000, "未确认消费", "其他").copy(needsReview = true)
        val oldRefund = LedgerEntry(id = 31, amountCents = 1_000, type = EntryType.INCOME,
            businessType = BusinessType.REFUND, category = "其他", relatedEntryId = 30,
            occurredAt = TEST_TIME + 1_000)
        val originalEntries = listOf(eligible, insufficient, unconfirmed, oldRefund)
        val event = reviewEvent(candidate().copy(amountCents = 2_500, businessType = BusinessType.REFUND,
            direction = FundsDirection.INCOMING, categorySuggestion = "退款"))
        showCenter(listOf(event), originalEntries)
        composeRule.onNodeWithText("核对并确认").performClick()
        assertCannotSave()
        composeRule.onNodeWithTag("recognition-refund-original-30").assertDoesNotExist()
        composeRule.onNodeWithTag("recognition-refund-original-40").assertDoesNotExist()
        composeRule.onNodeWithTag("recognition-refund-original-20").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("recognition-confirm").performScrollTo().assertIsEnabled().performClick()
        composeRule.runOnIdle {
            val refund = capturedEntries.single()
            assertEquals(BusinessType.REFUND, refund.businessType)
            assertEquals(EntryType.INCOME, refund.type)
            assertEquals(2_500L, refund.amountCents)
            assertEquals(20L, refund.relatedEntryId)
            assertEquals("购物", refund.category)
            assertFalse(rememberedCategory)
            assertEquals(5_000L, originalEntries.first { it.id == 20L }.amountCents)
            assertEquals(4, originalEntries.size)
        }
    }

    @Test
    fun unlinkKeepsCanonicalEntryAndUndoIgnoreUsesExplicitUserActionFlag() {
        val canonical = expense(70, 1_230, "已人工修改的合成商户", "购物").copy(editedByUser = true)
        val orderEvent = RecognitionEvent(id = 1, candidate = candidate(), decision = RecognitionDecision.AUTO,
            reasons = listOf("订单凭据已关联账单"), linkedEntryId = canonical.id, association = "ORDER")
        val ignoredEvent = RecognitionEvent(id = 2, candidate = candidate().copy(observedAt = TEST_TIME + 1_000),
            decision = RecognitionDecision.IGNORE, reasons = listOf("用户已忽略此来源"),
            association = "NONE", userIgnored = true)
        showCenter(listOf(orderEvent, ignoredEvent), listOf(canonical))
        composeRule.onNodeWithTag("recognition-filter-1").performClick()
        composeRule.onNodeWithTag("recognition-events").performScrollToNode(hasText("撤销关联"))
        composeRule.onNodeWithText("撤销关联").performScrollTo().performClick()
        composeRule.onNodeWithTag("recognition-filter-2").performClick()
        composeRule.onNodeWithText("恢复待确认").performScrollTo().assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(1L), unlinkedEvents)
            assertEquals(listOf(2L), restoredEvents)
            assertTrue(capturedEntries.isEmpty())
            assertTrue(editedEntries.isEmpty())
            assertEquals(1_230L, canonical.amountCents)
            assertEquals("购物", canonical.category)
            assertTrue(canonical.editedByUser)
            assertNull(canonical.relatedEntryId)
        }
    }

    private fun assertCannotSave() {
        composeRule.onNodeWithTag("recognition-confirm").performScrollTo().assertIsNotEnabled()
        composeRule.runOnIdle { assertTrue(capturedEntries.isEmpty()) }
    }

    private fun showCenter(events: List<RecognitionEvent>, entries: List<LedgerEntry> = emptyList()) {
        composeRule.setContent {
            JizhangTheme {
                RecognitionCenter(events = events, entries = entries, saving = false,
                    onConfirm = { _, entry, remember, _, _ -> capturedEntries += entry; rememberedCategory = remember },
                    onLink = { _, _ -> }, onIgnore = {}, onUndoIgnore = { restoredEvents += it },
                    onUnlink = { unlinkedEvents += it }, onEditEntry = { editedEntries += it }, onDismiss = {})
            }
        }
    }

    private fun reviewEvent(candidate: TransactionCandidate) = RecognitionEvent(id = 1, candidate = candidate,
        decision = RecognitionDecision.REVIEW, reasons = listOf("合成截图需要人工核对"))

    private fun candidate() = TransactionCandidate(provider = EntrySource.WECHAT, eventFingerprint = "a".repeat(64),
        amountCents = 1_230, direction = FundsDirection.OUTGOING, businessType = BusinessType.EXPENSE,
        merchant = "合成测试商户", occurredAt = TEST_TIME, observedAt = TEST_TIME, categorySuggestion = "其他",
        completion = CompletionState.COMPLETED, evidence = EvidenceType.IMPORTED_IMAGE,
        hasStableIdentity = false, ruleVersion = "wechat-screen-synthetic-ui-test")

    private fun expense(id: Long, amount: Long, merchant: String, category: String) = LedgerEntry(id = id,
        amountCents = amount, type = EntryType.EXPENSE, businessType = BusinessType.EXPENSE,
        category = category, merchant = merchant, occurredAt = TEST_TIME - 5_000, source = EntrySource.WECHAT)

    private companion object { const val TEST_TIME = 1_791_860_400_000L }
}
