package com.example.jizhang.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Simulated visible receipt samples, not a claim to cover every provider/version UI. */
class ScreenPaymentParserTest {
    private val wechat = "com.tencent.mm"
    private val alipay = "com.eg.android.AlipayGphone"

    private fun transfer(
        amount: String = "25.80",
        id: String = "1000050013261013000012345678",
        time: String = "2026-10-13 13:20:30",
    ) = "转账详情\n转出成功\n转账金额：￥$amount\n收款方：张三\n交易单号：$id\n转账时间：$time"

    @Test
    fun outgoingWechatTransferUsesCentsRecipientAndExplicitIdentity() {
        val receipt = ScreenPaymentParser.parse(wechat, transfer())!!
        assertEquals(2_580L, receipt.payment.amountCents)
        assertEquals(EntryType.EXPENSE, receipt.payment.type)
        assertEquals(EntrySource.WECHAT, receipt.payment.source)
        assertEquals("张三", receipt.payment.merchant)
        assertEquals("1000050013261013000012345678", receipt.transactionId)
        assertEquals(1_791_868_830_000L, receipt.occurredAt)
        assertFalse(receipt.payment.needsReview)
        assertTrue(receipt.hasStableIdentity)
    }

    @Test
    fun completedAlipayTransferResultAllowsOneBareProminentAmount() {
        val receipt = ScreenPaymentParser.parse(alipay, "转账成功\n18.60\n收款人：李四\n完成")!!
        assertEquals(1_860L, receipt.payment.amountCents)
        assertEquals(EntrySource.ALIPAY, receipt.payment.source)
        assertTrue(receipt.payment.needsReview)
    }

    @Test
    fun counterpartCollectionStatusesAreExpensesOnlyOnTransferDetails() {
        listOf("对方已收款", "对方已收钱").forEach { status ->
            val receipt = ScreenPaymentParser.parse(
                wechat, "转账详情\n$status\n转账金额：25.80元\n收款人：张三\n交易单号：WX202610130001",
            )!!
            assertEquals(status, EntryType.EXPENSE, receipt.payment.type)
            assertEquals(2_580L, receipt.payment.amountCents)
            assertFalse(receipt.payment.needsReview)
            assertNull(status, ScreenPaymentParser.parse(wechat, "$status\n25.80元"))
            assertNull(status, ScreenPaymentParser.parse(wechat, "聊天记录\n转账详情\n$status\n25.80元"))
        }
    }

    @Test
    fun selfCollectionAndChangeDepositStatusesAreIncomeOnlyOnTransferDetails() {
        listOf("你已收款", "您已收款", "你已收钱", "您已收钱", "已存入零钱").forEach { status ->
            val receipt = ScreenPaymentParser.parse(
                wechat, "转账详情\n$status\n到账金额：25.80元\n付款方：张三\n交易单号：WX202610130001",
            )!!
            assertEquals(status, EntryType.INCOME, receipt.payment.type)
            assertEquals("张三", receipt.payment.merchant)
            assertFalse(receipt.payment.needsReview)
            assertNull(status, ScreenPaymentParser.parse(wechat, "$status\n25.80元"))
        }
        assertNull(ScreenPaymentParser.parse(wechat, "转账详情\n已收款\n金额：25.80元"))
    }

    @Test
    fun alreadyTransferredNeedsTransferDetailAndExplicitRecipient() {
        listOf("收款人", "收款方", "转账对象").forEach { label ->
            val receipt = ScreenPaymentParser.parse(
                alipay, "转账详情\n已转账\n转账金额：25.80元\n$label：张三",
            )!!
            assertEquals(label, EntryType.EXPENSE, receipt.payment.type)
            assertTrue(receipt.payment.needsReview)
        }
        listOf(
            "已转账\n金额：25.80元\n收款人：张三",
            "转账详情\n已转账\n金额：25.80元",
            "转账详情\n已转账\n金额：25.80元\n付款方：张三",
            "转账详情\n已转账\n金额：25.80元\n收款人\n转账详情",
            "转账详情\n已转账\n金额：25.80元\n收款人：13800138000",
        ).forEach { assertNull(it, ScreenPaymentParser.parse(alipay, it)) }
    }

    @Test
    fun typedTransferBillCanSupplyTransferDetailContext() {
        val receipt = ScreenPaymentParser.parse(
            alipay, "账单详情\n交易类型：转账\n已转账\n金额：25.80元\n收款人：张三\n订单号：ALI202610130001",
        )!!
        assertEquals(EntryType.EXPENSE, receipt.payment.type)
        assertFalse(receipt.payment.needsReview)
        assertNull(ScreenPaymentParser.parse(alipay, "账单详情\n对方已收款\n金额：25.80元"))
    }

    @Test
    fun synonymousStatusesWithOneStableTransferOrderDescribeOneEvent() {
        val receipt = ScreenPaymentParser.parse(
            wechat, "转账详情\n转账成功\n对方已收款\n转账金额：25.80元\n交易单号：WX202610130001",
        )!!
        val soleStatus = ScreenPaymentParser.parse(
            wechat, "转账详情\n对方已收款\n转账金额：25.80元\n交易单号：WX202610130001",
        )!!
        assertEquals(soleStatus.receiptKey, receipt.receiptKey)
        assertEquals(EntryType.EXPENSE, receipt.payment.type)
        assertFalse(receipt.payment.needsReview)
        val received = ScreenPaymentParser.parse(
            wechat, "转账详情\n你已收款\n已存入零钱\n到账金额：25.80元\n交易单号：WX202610130002",
        )!!
        assertEquals(EntryType.INCOME, received.payment.type)
    }

    @Test
    fun repeatedAndUnidentifiedMultipleStatusesCannotHideMultipleTransfers() {
        listOf(
            "转账详情\n转账成功\n对方已收款\n转账金额：25.80元",
            "转账详情\n转账成功\n转账成功\n转账金额：25.80元\n交易单号：WX202610130001",
            "转账详情\n转账成功\n对方已收款\n转账金额：25.80元\n交易单号：WX202610130001\n交易单号：WX202610130002",
            "转账详情\n对方已收款\n你已收款\n金额：25.80元\n交易单号：WX202610130001",
            "转账详情\n转账成功\n对方已收款\n已转出\n转账金额：25.80元\n交易单号：WX202610130001",
        ).forEach { assertNull(it, ScreenPaymentParser.parse(wechat, it)) }
    }

    @Test
    fun fullRefundStatusIsIncomeWithRefundIdentity() {
        val receipt = ScreenPaymentParser.parse(
            alipay, "账单详情\n已全额退款\n退款金额：25.80元\n退款单号：REF202610130001",
        )!!
        assertEquals(EntryType.INCOME, receipt.payment.type)
        assertEquals("退款", receipt.payment.category)
        assertFalse(receipt.payment.needsReview)
        assertTrue(receipt.hasStableIdentity)
    }

    @Test
    fun arrivalIsIncomeAndKeepsPayer() {
        val receipt = ScreenPaymentParser.parse(
            alipay, "收款详情\n转账已到账\n到账金额：100.00元\n付款方：李四\n到账时间：2026-10-13 13:21",
        )!!
        assertEquals(10_000L, receipt.payment.amountCents)
        assertEquals(EntryType.INCOME, receipt.payment.type)
        assertEquals("收款", receipt.payment.category)
        assertEquals("李四", receipt.payment.merchant)
        assertTrue(receipt.payment.needsReview)
        assertFalse(receipt.hasStableIdentity)
    }

    @Test
    fun dispatchedEnvelopeIsExpenseEvenWhenUnclaimed() {
        val receipt = ScreenPaymentParser.parse(
            wechat, "红包详情\n你发出的红包\n待领取\n红包总金额：50.00元\n红包个数：5\n发送时间：2026-10-13 13:22:00",
        )!!
        assertEquals(5_000L, receipt.payment.amountCents)
        assertEquals(EntryType.EXPENSE, receipt.payment.type)
        assertEquals("其他", receipt.payment.category)
        assertFalse(receipt.payment.needsReview)
        assertTrue(receipt.hasStableIdentity)
    }

    @Test
    fun explicitlyDispatchedEnvelopeResultDoesNotNeedDetailTitle() {
        val receipt = ScreenPaymentParser.parse(alipay, "红包已发出\n总金额：12.00元\n待领取")!!
        assertEquals(1_200L, receipt.payment.amountCents)
        assertEquals(EntryType.EXPENSE, receipt.payment.type)
        assertTrue(receipt.payment.needsReview)
    }

    @Test
    fun envelopeRefundIsIncomeAndUsesRefundAmount() {
        val receipt = ScreenPaymentParser.parse(
            wechat, "红包详情\n红包退款成功\n退款金额：6.66元\n退款时间：2026-10-13 13:23",
        )!!
        assertEquals(666L, receipt.payment.amountCents)
        assertEquals(EntryType.INCOME, receipt.payment.type)
        assertEquals("退款", receipt.payment.category)
    }

    @Test
    fun receivedEnvelopeIsIncomeOnlyAfterReceiptStatus() {
        val receipt = ScreenPaymentParser.parse(
            wechat, "红包详情\n红包领取成功\n领取金额：8.88元\n发送方：张三",
        )!!
        assertEquals(888L, receipt.payment.amountCents)
        assertEquals(EntryType.INCOME, receipt.payment.type)
        assertEquals("张三", receipt.payment.merchant)
        assertNull(ScreenPaymentParser.parse(wechat, "红包详情\n待领取\n红包金额：8.88元"))
    }

    @Test
    fun pendingRecipientIsAllowedOnlyWithOutgoingDebitEvidence() {
        val receipt = ScreenPaymentParser.parse(
            wechat, "转账详情\n已转出\n待收款\n转账金额：10.00元",
        )!!
        assertEquals(EntryType.EXPENSE, receipt.payment.type)
        listOf("待收款", "请收款", "未收款").forEach { status ->
            assertNull(status, ScreenPaymentParser.parse(wechat, "转账详情\n$status\n转账金额：10.00元"))
        }
        assertNull(ScreenPaymentParser.parse(wechat, "收款详情\n收款成功\n待到账\n收款金额：10.00元"))
    }

    @Test
    fun completionButtonCannotCompleteInputOrDetails() {
        listOf(
            "转账\n转账金额：100元\n完成",
            "转账详情\n金额：100元\n完成",
            "红包详情\n总金额：100元\n完成",
            "账单详情\n金额：100元\n交易成功\n完成",
        ).forEach { assertNull(it, ScreenPaymentParser.parse(wechat, it)) }
    }

    @Test
    fun sendEnvelopeInputAndPasswordPagesAreRejectedEvenWithMisleadingStatus() {
        listOf(
            "红包详情\n你发出的红包\n总金额：100元\n塞钱进红包",
            "红包已发出\n红包金额：100元\n支付密码",
            "转账成功\n转账金额：100元\n请输入支付密码",
            "支付成功\n实付金额：100元\n验证码：123456",
            "转账详情\n转出成功\n转账金额：100元\n确认转账",
        ).forEach { assertNull(it, ScreenPaymentParser.parse(alipay, it)) }
    }

    @Test
    fun failedCancelledAndProcessingStatesAreRejected() {
        listOf("付款失败", "未支付", "未扣款", "交易已关闭", "退款处理中", "待付款", "状态未确认", "预计到账")
            .forEach { status ->
                assertNull(status, ScreenPaymentParser.parse(alipay, "账单详情\n支付成功\n金额：12元\n$status"))
            }
    }

    @Test
    fun chatAndTransactionListsNeverBecomeReceipts() {
        listOf(
            "聊天记录\n张三\n转账成功\n12元",
            "张三\n转账成功\n12元\n发送",
            "账单\n支付成功\n12元\n支付成功\n20元",
            "交易记录\n收款成功\n收款金额：12元",
            "通讯录\n支付成功\n金额：12元",
        ).forEach { assertNull(it, ScreenPaymentParser.parse(wechat, it)) }
    }

    @Test
    fun arbitrarySentenceCannotBorrowAResultStatus() {
        listOf(
            "张三说转账成功\n金额：12元",
            "聊天中说：支付成功\n实付金额：12元",
            "转账详情\n请转账\n金额：12元",
            "支付成功\n预算12元",
            "支付成功\n本月预算：12元",
        ).forEach { assertNull(it, ScreenPaymentParser.parse(alipay, it)) }
    }

    @Test
    fun feesAndBalancesCannotReplaceTheLabeledMainAmount() {
        val receipt = ScreenPaymentParser.parse(
            alipay, "转账详情\n转出成功\n转账金额：100元\n手续费：0.10元\n账户余额：500元",
        )!!
        assertEquals(10_000L, receipt.payment.amountCents)
        assertNull(ScreenPaymentParser.parse(alipay, "支付成功\n手续费：1.00元\n余额：500元"))
    }

    @Test
    fun actualPaymentCanBeLowerThanGrossOrderAmount() {
        val receipt = ScreenPaymentParser.parse(
            alipay, "支付详情\n支付成功\n实付金额：18.00元\n交易金额：20.00元\n优惠金额：2.00元\n商户名称：星巴克",
        )!!
        assertEquals(1_800L, receipt.payment.amountCents)
        assertEquals("餐饮", receipt.payment.category)
    }

    @Test
    fun conflictingMainAmountsAndUnlabeledAmountsAreRejected() {
        listOf(
            "转账详情\n转出成功\n转账金额：12元\n转账金额：20元",
            "支付详情\n支付成功\n支付金额：12元\n金额：20元",
            "支付成功\n12.00\n20.00",
            "支付成功\n12.00\n支付成功\n12.00",
            "支付成功\n实付金额：12元\n张三：20元",
            "交易详情\n支付成功\n支付金额：12元\n收款金额：20元",
        ).forEach { assertNull(it, ScreenPaymentParser.parse(alipay, it)) }
    }

    @Test
    fun sameMainAmountMayAppearTwiceInTheSameReceipt() {
        val receipt = ScreenPaymentParser.parse(alipay, "支付成功\n￥12.00\n支付金额：12.00元")!!
        assertEquals(1_200L, receipt.payment.amountCents)
    }

    @Test
    fun contradictoryDirectionsAreRejected() {
        listOf(
            "交易详情\n转出成功\n收款成功\n金额：12元",
            "红包详情\n红包已发出\n红包退款成功\n金额：12元",
            "交易详情\n支付成功\n退款成功\n金额：12元",
        ).forEach { assertNull(it, ScreenPaymentParser.parse(wechat, it)) }
    }

    @Test
    fun collectionDetailCannotBorrowGenericTransferSuccessAsAnExpense() {
        val conflicting = "收款详情\n转账成功\n付款方：张三\n转账金额：12.30元\n交易单号：CONFLICT001"
        assertNull(ScreenPaymentParser.parse(wechat, conflicting))
        assertNull(ScreenPaymentParser.parse(wechat, conflicting, fromOcr = true))
        assertNull(ScreenPaymentParser.parse(alipay, conflicting))
        assertNull(ScreenPaymentParser.parse(wechat, "收款详情\n转出成功\n金额：12.30元\n交易单号：CONFLICT001"))
    }

    @Test
    fun payerOnlyTransferSuccessNeedsClearDirectionRatherThanAnAssumedDebit() {
        listOf("付款方", "付款人", "转账方", "发送方").forEach { label ->
            assertNull(label, ScreenPaymentParser.parse(
                wechat, "转账详情\n转账成功\n$label：张三\n转账金额：12.30元\n交易单号：CONFLICT001",
            ))
        }
        val outgoing = ScreenPaymentParser.parse(
            wechat, "转账详情\n已转出\n付款方：本人\n转账金额：12.30元\n交易单号：TRANSFER001",
        )!!
        assertEquals(EntryType.EXPENSE, outgoing.payment.type)
        val withRecipient = ScreenPaymentParser.parse(
            wechat, "转账详情\n转账成功\n付款方：本人\n收款人：张三\n转账金额：12.30元\n交易单号：TRANSFER001",
        )!!
        assertEquals(EntryType.EXPENSE, withRecipient.payment.type)
    }

    @Test
    fun explicitCollectionStatusWithPayerRemainsIncome() {
        val receipt = ScreenPaymentParser.parse(
            wechat, "收款详情\n收款成功\n付款方：张三\n收款金额：12.30元\n交易单号：COLLECT0001",
        )!!
        assertEquals(EntryType.INCOME, receipt.payment.type)
        assertEquals(1_230L, receipt.payment.amountCents)
        assertEquals("张三", receipt.payment.merchant)
        assertFalse(receipt.payment.needsReview)
    }

    @Test
    fun paymentDetailRejectsIncomingConflictButCanDescribeItsRefund() {
        assertNull(ScreenPaymentParser.parse(
            alipay, "付款详情\n收款成功\n收款金额：12.30元\n交易单号：CONFLICT001",
        ))
        val refund = ScreenPaymentParser.parse(
            alipay, "付款详情\n退款成功\n退款金额：12.30元\n退款单号：REFUND0001",
        )!!
        assertEquals(EntryType.INCOME, refund.payment.type)
        assertEquals("退款", refund.payment.category)
    }

    @Test
    fun packageAllowlistBlocksOtherApplications() {
        assertNull(ScreenPaymentParser.parse("com.example.chat", transfer()))
        assertNull(ScreenPaymentParser.parse("com.tencent.mm.fake", transfer()))
    }

    @Test
    fun ocrAlwaysRequiresReviewEvenWithACompleteReceiptIdentity() {
        val receipt = ScreenPaymentParser.parse(wechat, transfer(), fromOcr = true)!!
        assertTrue(receipt.payment.needsReview)
        assertEquals(2_580L, receipt.payment.amountCents)
    }

    @Test
    fun actualMlKitOutputWithMisreadTransferAmountLabelRemainsReviewable() {
        val text = "转账详惰\n转账成功\n转账全額:88.66元\n收款人:测试用户\n交易单号:0CR20261013TEST"
        val receipt = ScreenPaymentParser.parse(wechat, text, fromOcr = true)!!
        assertEquals(8_866L, receipt.payment.amountCents)
        assertEquals(EntryType.EXPENSE, receipt.payment.type)
        assertEquals("测试用户", receipt.payment.merchant)
        assertEquals("0CR20261013TEST", receipt.transactionId)
        assertTrue(receipt.payment.needsReview)
        assertTrue(receipt.hasStableIdentity)
        assertNull(ScreenPaymentParser.parse(wechat, text))
    }

    @Test
    fun exactOcrAmountAliasesSupportTraditionalCharacterAndSplitFields() {
        listOf("转账全额", "转账全額", "转账金額", "金額").forEach { label ->
            val receipt = ScreenPaymentParser.parse(
                alipay, "转账成功\n$label\n88.66元\n交易单号：0CR20261013TEST", fromOcr = true,
            )!!
            assertEquals(label, 8_866L, receipt.payment.amountCents)
            assertTrue(receipt.payment.needsReview)
        }
        val receipt = ScreenPaymentParser.parse(
            alipay, "支付成功\n实付金額:80.00元\n订单金額:88.66元", fromOcr = true,
        )!!
        assertEquals(8_000L, receipt.payment.amountCents)
        assertTrue(receipt.payment.needsReview)
    }

    @Test
    fun ocrAmountAliasesDoNotCompleteFailedUnpaidUnknownOrChatPages() {
        listOf(
            "转账详惰\n转账全額:88.66元\n完成",
            "转账成功\n转账全額:88.66元\n未支付",
            "转账失败\n转账全額:88.66元",
            "待付款\n转账全额:88.66元",
            "聊天记录\n转账成功\n转账全額:88.66元",
            "张三\n转账成功\n转账全額:88.66元\n发送",
            "转账成功\n转账全額:88.66元\n请输入支付密码",
        ).forEach { assertNull(it, ScreenPaymentParser.parse(wechat, it, fromOcr = true)) }
    }

    @Test
    fun ocrLabelCorrectionsNeverRepairDigitsDecimalsOrOrderIds() {
        listOf("88.O6", "88,66", "88.666", "8B.66").forEach { value ->
            assertNull(value, ScreenPaymentParser.parse(
                wechat, "转账成功\n转账全額:$value 元\n交易单号:0CR20261013TEST", fromOcr = true,
            ))
        }
        val zeroId = ScreenPaymentParser.parse(
            wechat, "转账成功\n转账全額:88.66元\n交易单号:0CR20261013TEST", fromOcr = true,
        )!!
        val letterId = ScreenPaymentParser.parse(
            wechat, "转账成功\n转账全額:88.66元\n交易单号:OCR20261013TEST", fromOcr = true,
        )!!
        assertNotEquals(zeroId.receiptKey, letterId.receiptKey)
    }

    @Test
    fun multipleOcrAliasAmountsStillCannotBecomeASinglePayment() {
        assertNull(ScreenPaymentParser.parse(
            wechat, "转账成功\n转账全額:88.66元\n转账金額:99.00元", fromOcr = true,
        ))
    }

    @Test
    fun traditionalAmountLabelsKeepIncomingAndRefundDirection() {
        val incoming = ScreenPaymentParser.parse(
            alipay, "收款成功\n到账金額:88.66元", fromOcr = true,
        )!!
        val refund = ScreenPaymentParser.parse(
            alipay, "退款成功\n退款金額:88.66元\n退款单号:REF20261013TEST", fromOcr = true,
        )!!
        assertEquals(EntryType.INCOME, incoming.payment.type)
        assertEquals(EntryType.INCOME, refund.payment.type)
        assertEquals("退款", refund.payment.category)
        assertTrue(incoming.payment.needsReview)
        assertTrue(refund.payment.needsReview)
    }

    @Test
    fun noIdentityAndNoTimeAlwaysRequiresReview() {
        val receipt = ScreenPaymentParser.parse(wechat, "转账详情\n已转出\n金额：25.80元")!!
        assertNull(receipt.transactionId)
        assertNull(receipt.occurredAt)
        assertTrue(receipt.payment.needsReview)
    }

    @Test
    fun anOrderIdMakesKeysStableAcrossLayoutAmountAndRecipientLabels() {
        val first = ScreenPaymentParser.parse(wechat, transfer())!!
        val alternate = ScreenPaymentParser.parse(
            wechat,
            "转账详情\n已转出\n￥25.80\n收款人\n张三（昵称）\n交易单号\n1000050013261013000012345678",
        )!!
        assertEquals(first.receiptKey, alternate.receiptKey)
        assertFalse(alternate.payment.needsReview)
    }

    @Test
    fun distinctOrdersWithSameAmountGetDifferentReceiptKeys() {
        val first = ScreenPaymentParser.parse(wechat, transfer(id = "WX2026101300001"))!!
        val second = ScreenPaymentParser.parse(wechat, transfer(id = "WX2026101300002"))!!
        assertNotEquals(first.receiptKey, second.receiptKey)
    }

    @Test
    fun timeIdentifiedSameAmountTransfersAreDistinct() {
        val first = ScreenPaymentParser.parse(wechat, "转账详情\n已转出\n金额：10元\n转账时间：2026-10-13 13:20:30")!!
        val second = ScreenPaymentParser.parse(wechat, "转账详情\n已转出\n金额：10元\n转账时间：2026-10-13 13:20:31")!!
        assertNotEquals(first.receiptKey, second.receiptKey)
        assertFalse(first.payment.needsReview)
        assertTrue(first.hasStableIdentity)
    }

    @Test
    fun minutePrecisionWithoutOrderIdIsWeakAndRequiresReview() {
        val first = ScreenPaymentParser.parse(
            wechat, "转账详情\n已转出\n金额：10元\n转账时间：2026-10-13 13:20",
        )!!
        val second = ScreenPaymentParser.parse(
            wechat, "转账详情\n已转出\n金额：10元\n转账时间：2026-10-13 13:20",
        )!!
        assertEquals(first.receiptKey, second.receiptKey)
        assertTrue(first.payment.needsReview)
        assertFalse(first.hasStableIdentity)
        assertFalse(second.hasStableIdentity)
        assertEquals(1_791_868_800_000L, first.occurredAt)
    }

    @Test
    fun minutePrecisionWithTransactionIdStillHasStableIdentity() {
        val receipt = ScreenPaymentParser.parse(wechat, transfer(time = "2026-10-13 13:20"))!!
        assertTrue(receipt.hasStableIdentity)
        assertFalse(receipt.payment.needsReview)
    }

    @Test
    fun originalOrderRefundNeedsSecondPrecisionOrDedicatedRefundId() {
        val minuteOnly = ScreenPaymentParser.parse(
            alipay, "账单详情\n已全额退款\n退款金额：12元\n订单号：ALI202610130001\n退款时间：2026-10-13 13:20",
        )!!
        val seconds = ScreenPaymentParser.parse(
            alipay, "账单详情\n已全额退款\n退款金额：12元\n订单号：ALI202610130001\n退款时间：2026-10-13 13:20:30",
        )!!
        assertTrue(minuteOnly.payment.needsReview)
        assertFalse(minuteOnly.hasStableIdentity)
        assertFalse(seconds.payment.needsReview)
        assertTrue(seconds.hasStableIdentity)
    }

    @Test
    fun debitAndRefundSharingAnOriginalOrderRemainDistinct() {
        val debit = ScreenPaymentParser.parse(alipay, "交易详情\n支付成功\n金额：12元\n订单号：ALI202610130001")!!
        val refund = ScreenPaymentParser.parse(alipay, "交易详情\n退款成功\n退款金额：12元\n订单号：ALI202610130001")!!
        assertNotEquals(debit.receiptKey, refund.receiptKey)
    }

    @Test
    fun dedicatedRefundIdOutranksOriginalOrderAndRemainsStableAcrossLayouts() {
        val first = ScreenPaymentParser.parse(
            alipay, "账单详情\n退款成功\n退款金额：12元\n订单号：ALI202610130001\n退款单号：REF202610130001",
        )!!
        val second = ScreenPaymentParser.parse(
            alipay, "退款成功\n12.00元\n退款单号：REF202610130001\n退款时间：2026-10-13 13:20:30",
        )!!
        assertEquals("REF202610130001", first.transactionId)
        assertEquals(first.receiptKey, second.receiptKey)
        assertFalse(first.payment.needsReview)
    }

    @Test
    fun separatePartialRefundsWithOnlyOriginalOrderUseTimeAndAmount() {
        val first = ScreenPaymentParser.parse(
            alipay, "账单详情\n退款成功\n退款金额：12元\n订单号：ALI202610130001\n退款时间：2026-10-13 13:20:30",
        )!!
        val later = ScreenPaymentParser.parse(
            alipay, "账单详情\n退款成功\n退款金额：12元\n订单号：ALI202610130001\n退款时间：2026-10-13 13:20:31",
        )!!
        val noRefundIdentity = ScreenPaymentParser.parse(
            alipay, "账单详情\n退款成功\n退款金额：12元\n订单号：ALI202610130001",
        )!!
        assertNotEquals(first.receiptKey, later.receiptKey)
        assertTrue(noRefundIdentity.payment.needsReview)
    }

    @Test
    fun timestampsAreStrictAndDoNotRollInvalidDatesForward() {
        listOf(
            "2026-02-30 13:20:30", "2026-10-13 24:00:00", "2026-10-13 13:60:00",
            "2026-10-13 13:20:60", "2026-1-3 13:20", "13:20", "2026-10-13 13:20 extra",
        ).forEach { time -> assertNull(time, ScreenPaymentParser.parse(wechat, transfer(time = time))) }
    }

    @Test
    fun chineseAndSlashDatesResolveToTheSameChinaTimezoneInstant() {
        val dash = ScreenPaymentParser.parse(wechat, transfer())!!
        val chinese = ScreenPaymentParser.parse(wechat, transfer(time = "2026年10月13日 13:20:30"))!!
        val slash = ScreenPaymentParser.parse(wechat, transfer(time = "2026/10/13 13:20:30"))!!
        assertEquals(dash.occurredAt, chinese.occurredAt)
        assertEquals(dash.occurredAt, slash.occurredAt)
    }

    @Test
    fun multipleTransactionIdsOrTimesIndicateAnAmbiguousPage() {
        assertNull(ScreenPaymentParser.parse(wechat, transfer() + "\n交易单号：1000050013261013000099999999"))
        assertNull(ScreenPaymentParser.parse(wechat, transfer() + "\n转账时间：2026-10-13 13:20:31"))
        assertNull(ScreenPaymentParser.parse(wechat, transfer(id = "短单号")))
    }

    @Test
    fun refundTimeIsPreferredOverTheOriginalTransactionTime() {
        val receipt = ScreenPaymentParser.parse(
            alipay, "账单详情\n退款成功\n退款金额：12元\n交易时间：2026-10-12 13:20:30\n退款时间：2026-10-13 13:20:30",
        )!!
        assertEquals(1_791_868_830_000L, receipt.occurredAt)
    }

    @Test
    fun accessibilityLabelAndValueNodesAreParsedWithoutCountingMetadataAsMoney() {
        val receipt = ScreenPaymentParser.parse(
            wechat,
            "红包详情\n你发出的红包\n总金额\n12.00元\n红包个数\n2\n交易单号\n12345678901234567890\n发送时间\n2026-10-13 13:20:30\n手续费\n0.01元",
        )!!
        assertEquals(1_200L, receipt.payment.amountCents)
        assertEquals("12345678901234567890", receipt.transactionId)
    }

    @Test
    fun currencySignsAndSignedProminentAmountsMustAgreeWithDirection() {
        assertEquals(1_200L, ScreenPaymentParser.parse(alipay, "支付成功\n-12.00")!!.payment.amountCents)
        assertEquals(1_200L, ScreenPaymentParser.parse(alipay, "收款成功\n+12.00")!!.payment.amountCents)
        assertEquals(1_200L, ScreenPaymentParser.parse(alipay, "支付成功\n￥12.00元")!!.payment.amountCents)
        assertNull(ScreenPaymentParser.parse(alipay, "支付成功\n+12.00"))
        assertNull(ScreenPaymentParser.parse(alipay, "收款成功\n-12.00"))
    }

    @Test
    fun zeroOversizedGroupedAndOverPreciseAmountsAreRejected() {
        listOf("0.00", "1000000000.00", "1,234.56", "12.001", "-12.001", "NaN")
            .forEach { amount ->
                assertNull(amount, ScreenPaymentParser.parse(wechat, "转账详情\n已转出\n转账金额：￥$amount"))
            }
    }

    @Test
    fun normalizationAndScreenSizeLimitsAreEnforced() {
        val receipt = ScreenPaymentParser.parse(alipay, "支付成功\r\n支付金额：￥\u00a012.50\r\n商户名称：星巴克\u3000")!!
        assertEquals(1_250L, receipt.payment.amountCents)
        assertEquals("星巴克", receipt.payment.merchant)
        assertNull(ScreenPaymentParser.parse(alipay, ""))
        assertNull(ScreenPaymentParser.parse(alipay, "支付成功\n12元\n" + "x".repeat(12_001)))
    }

    @Test
    fun receiptSummaryAndKeyExcludeUnrelatedScreenText() {
        val receipt = ScreenPaymentParser.parse(
            wechat, transfer() + "\n祝福语：愿你好运\n手机号：13800138000\n备注：私人信息",
        )!!
        assertFalse(receipt.summary.contains("13800138000"))
        assertFalse(receipt.receiptKey.contains("13800138000"))
        assertFalse(receipt.summary.contains("私人信息"))
        assertFalse(receipt.receiptKey.contains("私人信息"))
        assertEquals(ScreenPaymentParser.parse(wechat, transfer())!!.receiptKey, receipt.receiptKey)
    }
}
