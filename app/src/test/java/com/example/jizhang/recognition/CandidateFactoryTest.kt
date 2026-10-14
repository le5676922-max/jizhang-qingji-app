package com.example.jizhang.recognition

import com.example.jizhang.domain.BusinessType
import com.example.jizhang.domain.ScreenPaymentParser
import org.junit.Assert.*
import org.junit.Test

class CandidateFactoryTest {
    private val wechat = "com.tencent.mm"
    private val alipay = "com.eg.android.AlipayGphone"
    private val now = 1_791_868_830_000L
    private val event = "a".repeat(64)

    private fun screen(text: String, evidence: EvidenceType = EvidenceType.ACCESSIBILITY): TransactionCandidate =
        CandidateFactory.fromScreen(ScreenPaymentParser.parse(wechat, text)!!, now, evidence)

    @Test fun merchantPaymentRetainsCompletedFactsAndProviderOrderNamespace() {
        val candidate = screen("付款详情\n支付成功\n实付金额：28元\n商户名称：星巴克\n交易单号：PAY202610130001")
        assertEquals(2_800L, candidate.amountCents)
        assertEquals(FundsDirection.OUTGOING, candidate.direction)
        assertEquals(BusinessType.EXPENSE, candidate.businessType)
        assertEquals("provider_transaction", candidate.orderNamespace)
        assertEquals(RecognitionDecision.AUTO, DecisionEngine.evaluate(candidate).decision)
    }

    @Test fun existingOfficialPaymentNotificationRemainsAutomaticWithoutMerchant() {
        val candidate = CandidateFactory.fromNotification(wechat, "微信支付", "支付成功12.30元",
            event, emptyList(), now)!!
        assertEquals(BusinessType.EXPENSE, candidate.businessType)
        assertEquals(RecognitionDecision.AUTO, DecisionEngine.evaluate(candidate).decision)
        assertEquals("", candidate.merchant)
    }

    @Test fun fundsArrivalAloneDoesNotInventIncomeAndRepaymentStillTakesPriority() {
        val arrival = CandidateFactory.fromNotification(alipay, "支付宝", "收款到账12.30元",
            event, emptyList(), now)!!
        val repayment = CandidateFactory.fromNotification(wechat, "微信支付", "支付成功12.30元，备注：归还借款",
            event, emptyList(), now)!!
        assertEquals(BusinessType.UNKNOWN, arrival.businessType)
        assertEquals(RecognitionDecision.REVIEW, DecisionEngine.evaluate(arrival).decision)
        assertEquals(BusinessType.REPAYMENT, repayment.businessType)
        assertEquals(RecognitionDecision.REVIEW, DecisionEngine.evaluate(repayment).decision)
    }

    @Test fun friendTransferAndReceiptAreTransfersInsteadOfExpenseAndIncome() {
        val sent = screen("转账详情\n已转出\n转账金额：100元\n收款方：张三\n交易单号：WX202610130001")
        val received = screen("转账详情\n你已收款\n到账金额：100元\n付款方：张三\n交易单号：WX202610130002")
        assertEquals(BusinessType.TRANSFER, sent.businessType)
        assertEquals(BusinessType.TRANSFER, received.businessType)
        assertEquals(FundsDirection.OUTGOING, sent.direction)
        assertEquals(FundsDirection.INCOMING, received.direction)
        assertEquals(RecognitionDecision.REVIEW, DecisionEngine.evaluate(sent).decision)
        assertEquals(RecognitionDecision.REVIEW, DecisionEngine.evaluate(received).decision)
    }

    @Test fun withdrawalAndRepaymentCannotBorrowCompletedPaymentAsConsumption() {
        val withdrawal = screen("交易详情\n已转出\n金额：500元\n交易类型：余额提现\n交易单号：WD202610130001")
        val repayment = screen("支付详情\n支付成功\n实付金额：500元\n商户名称：信用卡还款\n交易单号：RP202610130001")
        assertEquals(BusinessType.TRANSFER, withdrawal.businessType)
        assertEquals(BusinessType.REPAYMENT, repayment.businessType)
        assertEquals(RecognitionDecision.REVIEW, DecisionEngine.evaluate(withdrawal).decision)
        assertEquals(RecognitionDecision.REVIEW, DecisionEngine.evaluate(repayment).decision)
    }

    @Test fun refundHasIndependentIdAndOriginalPaymentRole() {
        val candidate = screen("交易详情\n退款成功\n退款金额：12元\n退款单号：REF202610130001\n原微信支付交易单号：PAY202610130001")
        assertEquals(BusinessType.REFUND, candidate.businessType)
        assertEquals("REF202610130001", candidate.orderId)
        assertEquals("PAY202610130001", candidate.originalOrderId)
        assertEquals("provider_refund", candidate.orderNamespace)
        assertEquals("provider_transaction", candidate.originalOrderNamespace)
        assertEquals(RecognitionDecision.REVIEW, DecisionEngine.evaluate(candidate).decision)
        assertEquals(RecognitionDecision.AUTO, DecisionEngine.evaluate(candidate, refundLinked = true).decision)
    }

    @Test fun originalOrderOnlyNeverMasqueradesAsARefundIdentity() {
        val candidate = screen("交易详情\n退款成功\n退款金额：12元\n订单号：PAY202610130001\n退款时间：2026-10-13 13:20:30")
        assertNull(candidate.orderId)
        assertNull(candidate.orderNamespace)
        assertEquals("PAY202610130001", candidate.originalOrderId)
        assertNull(candidate.originalOrderNamespace)
        assertFalse(candidate.hasStableIdentity)
        assertEquals(RecognitionDecision.REVIEW, DecisionEngine.evaluate(candidate, refundLinked = true).decision)
    }

    @Test fun genericOrderNumbersDoNotClaimProviderNamespace() {
        val candidate = screen("支付详情\n支付成功\n实付金额：28元\n商户名称：星巴克\n订单编号：SHOP202610130001")
        assertEquals("SHOP202610130001", candidate.orderId)
        assertNull(candidate.orderNamespace)
    }

    @Test fun nodeOcrAndImportHaveSeparateEventsWithLookupOnlyLegacyIdentity() {
        val text = "付款详情\n支付成功\n实付金额：28元\n商户名称：星巴克\n交易单号：PAY202610130001"
        val node = screen(text)
        val ocr = screen(text, EvidenceType.OCR)
        val image = screen(text, EvidenceType.IMPORTED_IMAGE)
        assertNotEquals(node.eventFingerprint, ocr.eventFingerprint)
        assertNotEquals(ocr.eventFingerprint, image.eventFingerprint)
        assertEquals(node.legacyFingerprints, ocr.legacyFingerprints)
        assertTrue(node.alternateFingerprints.isEmpty())
        assertEquals(RecognitionDecision.REVIEW, DecisionEngine.evaluate(ocr).decision)
    }

    @Test fun weakPagesDoNotPermanentlyMergeEqualPayments() {
        val text = "支付成功\n支付金额：28元\n商户名称：星巴克"
        val first = screen(text)
        val second = screen(text)
        assertNotEquals(first.eventFingerprint, second.eventFingerprint)
        assertFalse(first.hasStableIdentity)
        assertTrue(first.legacyFingerprints.isEmpty())
    }

    @Test fun notificationPreservesExistingEventAliasesAndExtractsNativeOrder() {
        val candidate = CandidateFactory.fromNotification(alipay, "付款成功",
            "付款金额：28元\n商户名称：星巴克\n支付宝交易号：ALI202610130001", event, listOf("b".repeat(64)), now)!!
        assertEquals(event, candidate.eventFingerprint)
        assertEquals(listOf("b".repeat(64)), candidate.alternateFingerprints)
        assertEquals("ALI202610130001", candidate.orderId)
        assertEquals("provider_transaction", candidate.orderNamespace)
        assertEquals(BusinessType.EXPENSE, candidate.businessType)
        assertEquals("alipay-notification-v2.0.1", candidate.ruleVersion)
    }

    @Test fun alipayExistingTradeNumberLabelAndProviderRuleVersionAreKept() {
        val parsed = ScreenPaymentParser.parse(alipay,
            "支付详情\n支付成功\n实付金额：28元\n商户名称：星巴克\n支付宝交易号：ALI202610130001")!!
        val candidate = CandidateFactory.fromScreen(parsed, now, EvidenceType.ACCESSIBILITY)
        assertEquals("ALI202610130001", candidate.orderId)
        assertEquals("provider_transaction", candidate.orderNamespace)
        assertEquals("alipay-screen-v2.0.1", candidate.ruleVersion)
        assertEquals(RecognitionDecision.AUTO, DecisionEngine.evaluate(candidate).decision)
    }

    @Test fun explicitProviderOriginalOrderOutranksGenericMerchantOriginalOrder() {
        val candidate = screen("交易详情\n退款成功\n退款金额：12元\n退款单号：REF202610130001\n原交易单号：PAY202610130001\n原订单号：SHOP202610130001")
        assertEquals("PAY202610130001", candidate.originalOrderId)
        assertEquals("provider_transaction", candidate.originalOrderNamespace)
        assertFalse(candidate.reasons.contains("订单标识存在冲突"))
    }

    @Test fun notificationRefundAndSalaryRetainEconomicMeaning() {
        val refund = CandidateFactory.fromNotification(alipay, "退款到账",
            "退款金额：12元\n退款单号：REF202610130001\n原交易单号：PAY202610130001", event, emptyList(), now)!!
        val salary = CandidateFactory.fromNotification(alipay, "支付宝", "工资已到账5000元", event, emptyList(), now)!!
        assertEquals(BusinessType.REFUND, refund.businessType)
        assertEquals("PAY202610130001", refund.originalOrderId)
        assertEquals(BusinessType.INCOME, salary.businessType)
        assertEquals(RecognitionDecision.AUTO, DecisionEngine.evaluate(salary).decision)
    }

    @Test fun unknownFailedChatAndMalformedEventIdentityProduceNoCandidate() {
        assertNull(CandidateFactory.fromNotification("com.other.app", "付款成功", "付款金额12元", event, emptyList(), now))
        assertNull(CandidateFactory.fromNotification(alipay, "付款成功", "未付款12元", event, emptyList(), now))
        assertNull(CandidateFactory.fromNotification(wechat, "张三", "支付成功12元", event, emptyList(), now))
        assertNull(CandidateFactory.fromNotification(alipay, "付款成功", "付款金额12元", "raw-event", emptyList(), now))
    }

    @Test fun walletArrivalAndLoanReturnDoNotBecomeLaborIncome() {
        val wallet = CandidateFactory.fromNotification(alipay, "支付宝", "收款到账500元，交易类型：提现", event, emptyList(), now)!!
        val loan = CandidateFactory.fromNotification(alipay, "支付宝", "收款到账500元，备注：归还借款", event, emptyList(), now)!!
        assertEquals(BusinessType.TRANSFER, wallet.businessType)
        assertEquals(BusinessType.REPAYMENT, loan.businessType)
        assertEquals(RecognitionDecision.REVIEW, DecisionEngine.evaluate(wallet).decision)
        assertEquals(RecognitionDecision.REVIEW, DecisionEngine.evaluate(loan).decision)
    }
}
