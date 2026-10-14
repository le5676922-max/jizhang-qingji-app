package com.example.jizhang.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenNodeEvidenceTest {
    @Test fun titleProbeOnlyAcceptsWindowTopBand() {
        val window = ScreenNodeBounds(0, 0, 1_000, 2_000)
        assertTrue(ScreenNodeBounds(20, 40, 900, 120).inTitleBand(window))
        assertFalse(ScreenNodeBounds(20, 700, 900, 820).inTitleBand(window))
        assertFalse(ScreenNodeBounds(-10, 40, 900, 120).inTitleBand(window))
        assertFalse(ScreenNodeBounds(20, 40, 20, 120).inTitleBand(window))
    }

    @Test fun collectorKeepsExplicitReceiptFactsAndDropsUnrelatedText() {
        listOf("转账详情", "转账成功", "转账金额：12.30元", "收款人：测试用户",
            "交易单号：SCREEN_TRANSFER_001", "红包已发出", "待领取").forEach {
            assertTrue(it, ReceiptNodeTextPolicy.independentText(it))
        }
        listOf("朋友说明天给我转账", "账号：6222000012345678", "银行卡号：6222000012345678",
            "6222000012345678", "备注：今晚聚餐", "账户余额：100元", "商品金额：200元",
            "转账成功\n朋友说明天转账").forEach {
            assertFalse(it, ReceiptNodeTextPolicy.independentText(it))
        }
    }

    @Test fun sensitiveResourceIdsAreExcludedBeforeTextLookup() {
        listOf("com.app:id/card_number", "bank_account", "chat_message", "password_input", "balance").forEach {
            assertTrue(ReceiptNodeTextPolicy.excludesResource(it))
        }
        assertFalse(ReceiptNodeTextPolicy.excludesResource("com.app:id/transfer_amount"))
        assertFalse(ReceiptNodeTextPolicy.excludesResource("com.app:id/transaction_id"))
    }

    @Test fun separatedIdValueMustBeNearItsAllowedLabel() {
        val label = ScreenNodeEvidence("交易单号", ScreenNodeBounds(20, 400, 180, 450), 3)
        assertTrue(ReceiptNodeTextPolicy.neighboringValue("SCREEN_TRANSFER_001", ScreenNodeBounds(190, 400, 800, 450), listOf(label)))
        assertTrue(ReceiptNodeTextPolicy.neighboringValue("SCREEN_TRANSFER_001", ScreenNodeBounds(20, 460, 800, 510), listOf(label)))
        assertFalse(ReceiptNodeTextPolicy.neighboringValue("SCREEN_TRANSFER_001", ScreenNodeBounds(20, 900, 800, 950), listOf(label)))
    }

    @Test fun merchantValueCannotBorrowAnUnrelatedAmountLabel() {
        val label = ScreenNodeEvidence("转账金额", ScreenNodeBounds(20, 400, 180, 450), 3)
        assertFalse(ReceiptNodeTextPolicy.neighboringValue("张三", ScreenNodeBounds(190, 400, 800, 450), listOf(label)))
        assertTrue(ReceiptNodeTextPolicy.neighboringValue("12.30元", ScreenNodeBounds(190, 400, 800, 450), listOf(label)))
    }

    @Test fun explicitOrderNumberIsPreservedWhileBareCardDigitsAreDiscarded() {
        assertTrue(ReceiptNodeTextPolicy.independentText("交易单号：20261013000012345678"))
        assertFalse(ReceiptNodeTextPolicy.independentText("20261013000012345678"))
        assertFalse(ReceiptNodeTextPolicy.independentText("6222 0000 1234 5678"))
    }

    @Test fun directionProofIsRetainedForTheDomainConflictValidator() {
        listOf("你已收款", "对方已收钱", "已存入零钱", "转账已转出").forEach {
            assertTrue(it, ReceiptNodeTextPolicy.independentText(it))
        }
        listOf("退款处理中", "交易状态：失败", "等待支付", "未到账").forEach {
            assertTrue(it, ScreenCapturePolicy.isExcludedPageHeading(it))
        }
    }
}
