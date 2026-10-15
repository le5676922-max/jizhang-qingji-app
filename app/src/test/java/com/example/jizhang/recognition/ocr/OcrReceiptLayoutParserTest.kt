package com.example.jizhang.recognition.ocr

import com.example.jizhang.domain.BusinessType
import com.example.jizhang.recognition.CandidateFactory
import com.example.jizhang.recognition.DecisionEngine
import com.example.jizhang.recognition.EvidenceType
import com.example.jizhang.recognition.RecognitionDecision
import org.junit.Assert.*
import org.junit.Test

/** Different receipt areas must not turn a balance, discount or random currency into a payment. */
class OcrReceiptLayoutParserTest {
    private val provider = "com.tencent.mm"
    private fun line(text: String, x: Int = 30, y: Int, right: Int = 520) =
        OcrLine(text, OcrRect(x, y, right, y + 30))
    private fun page(vararg facts: OcrLine) = OcrDocument(600, 1000,
        listOf(line("支付成功", y = 50)) + facts)
    private fun identity() = line("微信支付交易单号：TEST_PAY_0001", y = 700)

    @Test fun associatesLabelWithSameRowValueAndAlwaysReviewsImage() {
        val document = page(line("实付金额", y=250, right=170), line("28.56元", x=320,y=250,right=460),
            line("商户名称：便利店", y=400), identity())
        val payment = requireNotNull(OcrReceiptLayoutParser.parse(provider, document))
        assertEquals(2856L,payment.payment.amountCents)
        assertEquals("便利店",payment.payment.merchant)
        assertEquals(BusinessType.EXPENSE,payment.businessType)
        assertEquals(RecognitionDecision.REVIEW,DecisionEngine.evaluate(CandidateFactory.fromScreen(
            payment,System.currentTimeMillis(),EvidenceType.IMPORTED_IMAGE,true)).decision)
    }

    @Test fun ignoresHigherBalanceAndOrderAmountAndLowerDiscount() {
        val payment = requireNotNull(OcrReceiptLayoutParser.parse(provider,page(
            line("账户余额：9999.00元",y=160),line("订单金额：40.00元",y=220),
            line("优惠金额：11.44元",y=300),line("实付金额：28.56元",y=400),identity())))
        assertEquals(2856L,payment.payment.amountCents)
    }

    @Test fun bareAmountDoesNotBecomePaymentWithoutAFieldLabel() {
        assertNull(OcrReceiptLayoutParser.parse(provider,page(line("¥9999.00",y=250),identity())))
    }

    @Test fun valueInAnotherAreaCannotBindToFarAwayAmountLabel() {
        assertNull(OcrReceiptLayoutParser.parse(provider,page(line("实付金额",y=200,right=170),
            line("28.56元",x=320,y=550,right=460),identity())))
    }

    @Test fun interveningDiscountLabelBlocksFalseSameRowMatch() {
        assertNull(OcrReceiptLayoutParser.parse(provider,page(line("实付金额",y=250,right=170),
            line("优惠金额",x=210,y=250,right=330),line("10.00元",x=380,y=250,right=490),identity())))
    }

    @Test fun repeatedRealPayValuesProduceOptionsInsteadOfAnAutomaticChosenAmount() {
        val document = page(line("实付金额：28.56元",y=250),line("实付金额：31.00元",y=400),identity())
        val interpreted = OcrReceiptLayoutParser.interpret(provider,document)
        assertNotNull(interpreted.payment)
        assertEquals(listOf(2856L,3100L),interpreted.amountOptions)
        assertTrue(interpreted.reasons.any { it.contains("多个") })
        assertNull(OcrReceiptLayoutParser.parse(provider,document))
    }

    @Test fun missingGeometryAndTruncatedDocumentsAreRejected() {
        assertNull(OcrReceiptLayoutParser.parse(provider,OcrDocument(600,1000,
            listOf(OcrLine("支付成功",null),OcrLine("实付金额：28.56元",null)))))
        assertNull(OcrReceiptLayoutParser.parse(provider,page(line("实付金额：28.56元",y=250)).copy(truncated=true)))
        assertNull(OcrReceiptLayoutParser.parse(provider,page(line("实付金额：28.56元",y=250)).copy(width=0)))
    }

    @Test fun aChatHeadingAndPaymentRequestCannotAuthorizeOcr() {
        assertNull(OcrReceiptLayoutParser.parse(provider,page(line("聊天信息",y=10),line("实付金额：28.56元",y=250))))
        assertNull(OcrReceiptLayoutParser.parse(provider,page(line("待付款",y=100),line("实付金额：28.56元",y=250))))
        assertNull(OcrReceiptLayoutParser.parse("other.application",page(line("实付金额：28.56元",y=250))))
    }

    @Test fun compoundOcrLineMayUseElementGeometryWithoutDuplicatingFacts() {
        val mixed = OcrLine("实付金额 28.56元",OcrRect(30,250,460,280),tokens=listOf(
            OcrToken("实付金额",OcrRect(30,250,170,280)),OcrToken("28.56元",OcrRect(320,250,460,280))))
        assertEquals(2856L,requireNotNull(OcrReceiptLayoutParser.parse(provider,page(mixed,identity()))).payment.amountCents)
    }

    @Test fun noThirdDecimalOrOutOfBoundsTextIsAccepted() {
        assertNull(OcrReceiptLayoutParser.parse(provider,page(line("实付金额：12.345元",y=250),identity())))
        assertNull(OcrReceiptLayoutParser.parse(provider,page(OcrLine("实付金额：28.56元",OcrRect(30,200,700,230)),identity())))
    }

    @Test fun knownTransferLabelVariantMatchesItsActualBoundaryWithoutRewritingMoney() {
        fun receipt(vararg amounts: OcrLine) = OcrDocument(600, 1000, listOf(
            line("转账详情", y=50), line("转账成功", y=110),
            line("收款人：测试用户", y=400), identity()) + amounts)
        listOf("转账全額：28.56元", "转账全额：28.56元", "转账全額:28.56元", "转账全額 28.56元").forEach {
            assertEquals(it, 2856L, requireNotNull(OcrReceiptLayoutParser.parse(provider, receipt(line(it,y=250)))).payment.amountCents)
        }
        assertEquals(2856L, requireNotNull(OcrReceiptLayoutParser.parse(provider, receipt(
            line("转账全額",y=250,right=170),line("28.56元",x=320,y=250,right=460)))).payment.amountCents)
        assertNull(OcrReceiptLayoutParser.parse(provider,receipt(line("转账全額外说明：28.56元",y=250))))
        assertNull(OcrReceiptLayoutParser.parse(provider,receipt(line("转账全額：12.345元",y=250))))
    }
}
