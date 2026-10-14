package com.example.jizhang.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCapturePolicyTest {
    @Test fun identityPreflightRequiresKnownClassOrAnIndependentHeading() {
        assertTrue(ScreenCapturePolicy.isKnownReceiptActivity("com.example.ledger.fixture.TransferResultActivity"))
        assertFalse(ScreenCapturePolicy.isKnownReceiptActivity("com.tencent.mm.ui.chatting.ChattingUI"))
        assertFalse(ScreenCapturePolicy.isKnownReceiptActivity("com.example.UnknownActivity"))
        assertTrue(ScreenCapturePolicy.isIndependentReceiptTitle("转账详情"))
        assertFalse(ScreenCapturePolicy.isIndependentReceiptTitle("张三说转账成功100元"))
        assertFalse(ScreenCapturePolicy.isIndependentReceiptTitle("转账详情\n聊天正文"))
    }

    @Test fun shoppingAndPendingHeadingsFailThePreflight() {
        listOf("商品详情", "购物车", "待付款订单", "确认订单", "付款失败", "订单已取消").forEach {
            assertTrue(it, ScreenCapturePolicy.isExcludedPageHeading(it))
        }
        assertFalse(ScreenCapturePolicy.isExcludedPageHeading("转账详情"))
    }

    @Test fun unknownActivityCannotProbeGenericTextViewsAsTitles() {
        assertFalse(ScreenCapturePolicy.mayProbeHeading(false, false, true))
        assertTrue(ScreenCapturePolicy.mayProbeHeading(false, true, true))
        assertTrue(ScreenCapturePolicy.mayProbeHeading(true, false, true))
        assertFalse(ScreenCapturePolicy.mayProbeHeading(true, false, false))
    }

    @Test fun conflictingUnfinishedStatusCannotBeDroppedBeforeOcr() {
        assertFalse(ScreenCapturePolicy.mayUseOcr(ScreenCapturePolicy.WECHAT_PACKAGE,
            "转账详情\n转账成功\n交易状态：未到账", "TransferResultActivity", false, true, true))
        assertFalse(ScreenCapturePolicy.mayUseOcr(ScreenCapturePolicy.ALIPAY_PACKAGE,
            "交易详情\n退款成功\n退款处理中", "TradeDetailActivity", false, true, true))
    }

    @Test fun sensitiveActivityCanBeRejectedUsingOnlyMetadata() {
        listOf(
            "com.tencent.mm.ui.chatting.ChattingUI",
            "com.tencent.mm.plugin.wallet_core.ui.WalletCheckPwdUI",
            "com.example.payment.PasswordInputActivity",
            "com.example.payment.VerifyActivity",
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyPrepareUI",
            "com.tencent.mm.plugin.remittance.ui.RemittanceUI",
            "com.tencent.mm.plugin.wallet_pay.ui.WalletPayUI",
        ).forEach { activity ->
            assertTrue("Sensitive metadata $activity", ScreenCapturePolicy.isSensitiveContext("", activity))
            assertFalse(ScreenCapturePolicy.mayUseOcr(
                ScreenCapturePolicy.WECHAT_PACKAGE, "", activity, false, true, true,
            ))
        }
    }

    @Test fun paymentResultMayUseLocalOcr() {
        assertTrue(ScreenCapturePolicy.mayUseOcr(
            ScreenCapturePolicy.WECHAT_PACKAGE, "转账成功", "", false, false, true,
        ))
    }

    @Test fun knownReceiptActivityMayUseOcrWhenItsNodesAreEmpty() {
        assertTrue(ScreenCapturePolicy.mayUseOcr(
            ScreenCapturePolicy.WECHAT_PACKAGE, "", "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI",
            false, true, false,
        ))
    }

    @Test fun arbitraryPaymentAppActivityCannotTriggerEmptyScreenCapture() {
        assertFalse(ScreenCapturePolicy.mayUseOcr(
            ScreenCapturePolicy.ALIPAY_PACKAGE, "", "com.alipay.mobile.quinox.LauncherActivity",
            false, true, false,
        ))
    }

    @Test fun chatMessageClaimingPaymentSuccessDoesNotPermitCapture() {
        assertFalse(ScreenCapturePolicy.mayUseOcr(
            ScreenCapturePolicy.WECHAT_PACKAGE, "转账成功 ¥10.00", "com.tencent.mm.ui.chatting.ChattingUI",
            false, true, true,
        ))
    }

    @Test fun anyEditableOrPasswordInputBlocksOcr() {
        assertFalse(ScreenCapturePolicy.mayUseOcr(
            ScreenCapturePolicy.ALIPAY_PACKAGE, "转账成功", "", true, true, true,
        ))
        assertFalse(ScreenCapturePolicy.mayUseOcr(
            ScreenCapturePolicy.ALIPAY_PACKAGE, "转账成功 请输入支付密码", "", false, true, true,
        ))
    }

    @Test fun verificationAndUnfinishedPaymentPagesAreExcluded() {
        listOf("转账成功 短信验证码", "确认转账", "请输入金额 塞钱进红包").forEach { text ->
            assertFalse(ScreenCapturePolicy.mayUseOcr(
                ScreenCapturePolicy.WECHAT_PACKAGE, text, "", false, true, true,
            ))
        }
    }

    @Test fun appAllowlistAppliesEvenWithReceiptActivityAndSuccessText() {
        assertFalse(ScreenCapturePolicy.mayUseOcr(
            "com.example.other", "支付成功", "PaymentResultActivity", false, true, true,
        ))
    }

    @Test fun receiptKeywordsAlsoPermitDebouncedContentEvent() {
        assertTrue(ScreenCapturePolicy.mayUseOcr(
            ScreenCapturePolicy.ALIPAY_PACKAGE, "账单详情", "", false, false, false,
        ))
    }

    @Test fun ordinaryActivityWithEmbeddedSuccessCannotTriggerOcr() {
        val embedded = "京东 支付成功100元"
        assertTrue(ScreenCapturePolicy.hasPaymentContext(embedded, "com.example.OrdinaryActivity"))
        assertFalse(ScreenCapturePolicy.mayUseOcr(
            ScreenCapturePolicy.ALIPAY_PACKAGE, embedded, "com.example.OrdinaryActivity",
            false, true, true,
        ))
        assertFalse(ScreenCapturePolicy.mayUseOcr(
            ScreenCapturePolicy.WECHAT_PACKAGE, "张三说：支付成功100元", "com.example.OrdinaryActivity",
            false, true, true,
        ))
    }

    @Test fun explicitStandaloneResultTitlePermitsOrdinaryActivity() {
        assertTrue(ScreenCapturePolicy.mayUseOcr(
            ScreenCapturePolicy.ALIPAY_PACKAGE, "支付成功\n转账金额：100.00元",
            "com.example.OrdinaryActivity", false, false, false,
        ))
    }

    @Test fun fixtureReceiptActivityPermitsEmptyNodesAfterWindowChange() {
        assertTrue(ScreenCapturePolicy.mayUseOcr(
            ScreenCapturePolicy.WECHAT_PACKAGE, "", "com.example.ledger.fixture.TransferResultActivity",
            false, true, false,
        ))
    }

    @Test fun knownReceiptActivityWithoutTextOrFreshTriggerDoesNotCaptureRepeatedly() {
        assertFalse(ScreenCapturePolicy.mayUseOcr(
            ScreenCapturePolicy.WECHAT_PACKAGE, "", "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI",
            false, false, false,
        ))
    }

    @Test fun billListRejectsOcrEvenWithKnownReceiptActivity() {
        val list = "账单\n京东 支付成功100元\n淘宝 支付成功200元"
        listOf("com.example.OrdinaryActivity", "com.example.ledger.fixture.TransferResultActivity").forEach { activity ->
            assertFalse(ScreenCapturePolicy.hasPaymentContext(list, activity))
            assertFalse(ScreenCapturePolicy.mayUseOcr(
                ScreenCapturePolicy.ALIPAY_PACKAGE, list, activity, false, true, true,
            ))
        }
    }

    @Test fun exactListAndChatHeadingBlocksEvenAnIndependentSuccessTitle() {
        listOf("交易记录", "账单列表", "聊天记录", "消息", "联系人", "通讯录", "发送", "按住说话",
            "收支明细", "转账记录", "红包记录", "聊天信息", "聊天详情", "最近交易").forEach { heading ->
            assertFalse("Unsafe heading $heading", ScreenCapturePolicy.mayUseOcr(
                ScreenCapturePolicy.WECHAT_PACKAGE, "$heading\n转账成功\n100元",
                "com.example.ledger.fixture.TransferResultActivity", false, true, true,
            ))
        }
    }

    @Test fun paymentInputControlsBlockOcrWithoutEditableNodes() {
        listOf("确认支付", "立即支付", "立即转账", "付款码", "放入红包", "支付请求").forEach { control ->
            assertFalse("Unsafe control $control", ScreenCapturePolicy.mayUseOcr(
                ScreenCapturePolicy.ALIPAY_PACKAGE, "转账详情\n转账成功\n$control",
                "com.example.ledger.fixture.TransferResultActivity", false, true, true,
            ))
        }
    }

    @Test fun detailTitleIsDistinctFromListHeadingAndNormalizesWhitespace() {
        assertTrue(ScreenCapturePolicy.mayUseOcr(
            ScreenCapturePolicy.ALIPAY_PACKAGE, "\u3000账单详情\u00a0\r\n支付成功！\r\n28.56元",
            "com.example.OrdinaryActivity", false, false, false,
        ))
        assertFalse(ScreenCapturePolicy.mayUseOcr(
            ScreenCapturePolicy.ALIPAY_PACKAGE, "\u3000账单\u00a0\r\n支付成功\r\n28.56元",
            "com.example.OrdinaryActivity", false, true, true,
        ))
    }
}
