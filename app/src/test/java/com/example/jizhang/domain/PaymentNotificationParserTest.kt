package com.example.jizhang.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentNotificationParserTest {
    private val wechat = "com.tencent.mm"
    private val alipay = "com.eg.android.AlipayGphone"

    @Test
    fun wechatPaymentReceiptPreservesCentsAndMerchant() {
        val parsed = PaymentNotificationParser.parse(
            wechat, "微信支付", "微信支付凭证\n支付金额：￥28.50\n商户全称：星巴克\n支付时间：2026-10-13 12:30",
        )!!
        assertEquals(2850L, parsed.amountCents)
        assertEquals(EntryType.EXPENSE, parsed.type)
        assertEquals(EntrySource.WECHAT, parsed.source)
        assertEquals("星巴克", parsed.merchant)
        assertEquals("餐饮", parsed.category)
        assertFalse(parsed.needsReview)
    }

    @Test
    fun completedAlipayPaymentParsesASingleLabeledAmount() {
        val parsed = PaymentNotificationParser.parse(
            alipay, "支付宝", "付款成功，金额 19.90 元，收款方：京东",
        )!!
        assertEquals(1990L, parsed.amountCents)
        assertEquals(EntryType.EXPENSE, parsed.type)
        assertEquals(EntrySource.ALIPAY, parsed.source)
        assertEquals("京东", parsed.merchant)
        assertEquals("购物", parsed.category)
    }

    @Test
    fun aStatusTitleCanCompleteAReceiptWithAnExplicitCurrencyAmount() {
        val parsed = PaymentNotificationParser.parse(alipay, "支付成功", "¥0.29\n商家：便利店")!!
        assertEquals(29L, parsed.amountCents)
        assertEquals("便利店", parsed.merchant)
    }

    @Test
    fun directionalCompletedSentenceSuppliesAMerchant() {
        val parsed = PaymentNotificationParser.parse(wechat, "微信支付", "你向星巴克支付了25.00元")!!
        assertEquals(2500L, parsed.amountCents)
        assertEquals("星巴克", parsed.merchant)
        assertEquals(EntryType.EXPENSE, parsed.type)
    }

    @Test
    fun observedWechatDirectionalReceiptWithoutSuccessWordIsAnExpense() {
        val parsed = PaymentNotificationParser.parse(wechat, "微信支付", "向 麦当劳 付款 12.30 元")!!
        assertEquals(1230L, parsed.amountCents)
        assertEquals(EntryType.EXPENSE, parsed.type)
        assertEquals("麦当劳", parsed.merchant)
        assertEquals("餐饮", parsed.category)
    }

    @Test
    fun observedWechatPaymentTowardsYouIsIncome() {
        val parsed = PaymentNotificationParser.parse(wechat, "微信支付", "向你付款 100.00 元")!!
        assertEquals(10_000L, parsed.amountCents)
        assertEquals(EntryType.INCOME, parsed.type)
        assertEquals("收款", parsed.category)
        assertEquals("", parsed.merchant)
        assertEquals(
            EntryType.INCOME,
            PaymentNotificationParser.parse(wechat, "微信支付", "向您付款100.00元")!!.type,
        )
    }

    @Test
    fun directionalReceiptCannotBeBorrowedByAChatOrAnIncompleteMessage() {
        assertNull(PaymentNotificationParser.parse(wechat, "张三", "向你付款 100.00 元"))
        assertNull(PaymentNotificationParser.parse(alipay, "支付宝", "向你付款 100.00 元"))
        assertNull(PaymentNotificationParser.parse(wechat, "微信支付", "向 麦当劳 付款 12.30 元，请付款"))
        assertNull(PaymentNotificationParser.parse(wechat, "微信支付", "向 麦当劳 付款 12.30 元，待支付"))
        assertNull(PaymentNotificationParser.parse(wechat, "微信支付", "向 付款12元"))
        assertNull(PaymentNotificationParser.parse(wechat, "微信支付", "支付成功，向你付款100元"))
    }

    @Test
    fun completedServiceMessageCanSupplyADirectionWithoutAPersonalPronoun() {
        val parsed = PaymentNotificationParser.parse(wechat, "微信支付", "支付成功，向星巴克支付28.00元")!!
        assertEquals(2800L, parsed.amountCents)
        assertEquals("星巴克", parsed.merchant)
        val alipayPayment = PaymentNotificationParser.parse(alipay, "支付宝", "支付成功，向便利店付款18.50元")!!
        assertEquals(1850L, alipayPayment.amountCents)
        assertEquals("便利店", alipayPayment.merchant)
    }

    @Test
    fun arrivalReceiptIsIncomeAndKeepsThePayer() {
        val parsed = PaymentNotificationParser.parse(
            wechat, "收款到账通知", "收款到账100.00元，付款方：张三",
        )!!
        assertEquals(10_000L, parsed.amountCents)
        assertEquals(EntryType.INCOME, parsed.type)
        assertEquals("张三", parsed.merchant)
        assertEquals("收款", parsed.category)
    }

    @Test
    fun refundAndSalaryHaveIncomeCategories() {
        val refund = PaymentNotificationParser.parse(alipay, "退款到账", "退款金额：12.00元，商户：京东")!!
        assertEquals(EntryType.INCOME, refund.type)
        assertEquals("退款", refund.category)
        val salary = PaymentNotificationParser.parse(alipay, "支付宝", "工资已到账5000.00元")!!
        assertEquals(EntryType.INCOME, salary.type)
        assertEquals("工资", salary.category)
    }

    @Test
    fun irrelevantPackageOrUntrustedChatTitleIsIgnored() {
        assertNull(PaymentNotificationParser.parse("com.other.app", "微信支付", "支付成功12.00元"))
        listOf("张三", "朋友", "家庭群", "微信", "[3条] 张三", "微信支付 (2条消息)")
            .forEach { assertNull(it, PaymentNotificationParser.parse(wechat, it, "微信支付成功12.00元")) }
        assertNull(PaymentNotificationParser.parse(alipay, "朋友", "付款成功12.00元"))
    }

    @Test
    fun providerTitleAloneDoesNotAssertATransaction() {
        listOf("12.00元", "转账给我12元", "给你转账12.00元", "转账成功12元", "收款金额：12.00元")
            .forEach { body ->
                assertNull(body, PaymentNotificationParser.parse(wechat, "微信支付", body))
                assertNull(body, PaymentNotificationParser.parse(alipay, "支付宝", body))
            }
    }

    @Test
    fun failuresPendingTransactionsAndRequestsNeverBecomeEntries() {
        listOf(
            "付款失败12.00元", "付款成功状态未确认12.00元", "未付款成功12.00元",
            "待支付12.00元", "付款成功后到账12.00元，请付款", "转账12.00元，请收款",
            "收款到账12.00元，待领取", "退款12.00元，处理中", "预计到账12.00元",
            "退款成功12.00元，退款申请受理中", "已支付12.00元，交易已关闭",
        ).forEach { body -> assertNull(body, PaymentNotificationParser.parse(alipay, "支付宝", body)) }
    }

    @Test
    fun marketingAndBalanceAmountsAreIgnored() {
        listOf(
            "付款成功12.00元，优惠券到账", "红包到账12.00元", "余额12.00元",
            "付款成功12.00元，余额50.00元", "收款到账12.00元，领取福利",
            "付款成功12.00元，返现到账", "积分到账12元",
        ).forEach { body -> assertNull(body, PaymentNotificationParser.parse(alipay, "支付宝", body)) }
    }

    @Test
    fun multipleOrConflictingTransactionAmountsAreIgnored() {
        listOf(
            "付款成功12.00元，手续费1.00元", "付款成功12.00元，合计12.00元",
            "付款成功12.00元，退款到账8.00元", "付款成功且收款到账12.00元",
        ).forEach { body -> assertNull(body, PaymentNotificationParser.parse(alipay, "支付宝", body)) }
    }

    @Test
    fun aSingleUnrelatedAmountDoesNotBorrowACompletedStatus() {
        listOf(
            "付款成功，本月预算12.00元", "付款成功。贷款本金12.00元",
            "付款成功，收益12.00元", "付款成功，收款金额12.00元",
        ).forEach { body -> assertNull(body, PaymentNotificationParser.parse(alipay, "支付宝", body)) }
        assertNull(PaymentNotificationParser.parse(alipay, "支付成功", "预算：12.00元"))
    }

    @Test
    fun statusCanFollowItsCurrencyAmount() {
        val parsed = PaymentNotificationParser.parse(alipay, "支付宝", "￥18.60元，支付成功")!!
        assertEquals(1860L, parsed.amountCents)
        assertEquals(EntryType.EXPENSE, parsed.type)
    }

    @Test
    fun malformedBareOrOverPreciseAmountsAreIgnored() {
        listOf(
            "付款成功12.001元", "付款成功￥12.001", "付款成功1,234.56元", "付款成功12.00",
            "付款成功0.00元", "付款成功1000000000.00元", "付款成功-12.00元",
        ).forEach { body -> assertNull(body, PaymentNotificationParser.parse(alipay, "支付宝", body)) }
    }

    @Test
    fun spacingAndLineEndingsDoNotChangeTheAmount() {
        val parsed = PaymentNotificationParser.parse(
            alipay, " 支付成功 ", "付款金额：￥\u00A012.50\r\n商家：星巴克\u3000支付时间：12:30",
        )!!
        assertEquals(1250L, parsed.amountCents)
        assertEquals("星巴克", parsed.merchant)
    }

    @Test
    fun missingOrVeryLongBodyIsIgnored() {
        assertNull(PaymentNotificationParser.parse(wechat, "微信支付", ""))
        assertNull(PaymentNotificationParser.parse(wechat, "微信支付", "支付成功12元" + "x".repeat(4_001)))
    }

    @Test
    fun repeatedTransactionsAreNotDeduplicatedInsideTheParser() {
        val first = PaymentNotificationParser.parse(alipay, "支付宝", "支付成功10.00元，商户：超市")
        val second = PaymentNotificationParser.parse(alipay, "支付宝", "支付成功10.00元，商户：超市")
        assertNotNull(first)
        assertEquals(first, second)
    }
}
