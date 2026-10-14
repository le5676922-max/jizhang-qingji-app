package com.example.jizhang.automation

import com.example.jizhang.domain.EntrySource
import com.example.jizhang.domain.EntryType
import com.example.jizhang.domain.ParsedPayment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationEventIdentityTest {
    private val payment = ParsedPayment(1_230, EntryType.EXPENSE, "其他", "", EntrySource.WECHAT)

    @Test
    fun notificationUpdateCanChangePostTimeMerchantAndCategoryWithoutCreatingAnotherTransaction() {
        val first = identity(postTime = 1_001, payment = payment)
        val enriched = identity(postTime = 1_999, payment = payment.copy(category = "餐饮", merchant = "新补充商户"))
        assertNotEquals(first.primary, enriched.primary)
        assertEquals(first.stableTransaction, enriched.stableTransaction)
        assertTrue(first.primary.matches(Regex("[a-f0-9]{64}")))
        assertTrue(first.stableTransaction!!.matches(Regex("[a-f0-9]{64}")))
    }

    @Test
    fun equalAmountsWithDifferentKeysOrEventTimesRemainDistinct() {
        val first = identity()
        assertNotEquals(first.stableTransaction, identity(key = "another-key").stableTransaction)
        assertNotEquals(first.stableTransaction, identity(notificationWhen = 1_001).stableTransaction)
        assertNotEquals(first.stableTransaction, identity(packageName = "com.eg.android.AlipayGphone").stableTransaction)
    }

    @Test
    fun differentAmountsOrDirectionsDoNotShareTheStableIdentity() {
        val first = identity()
        assertNotEquals(first.stableTransaction, identity(payment = payment.copy(amountCents = 1_231)).stableTransaction)
        assertNotEquals(first.stableTransaction, identity(payment = payment.copy(type = EntryType.INCOME)).stableTransaction)
    }

    @Test
    fun missingEventTimeFallsBackToExactPostedEventWithoutGuessing() {
        val first = identity(notificationWhen = 0)
        val later = identity(postTime = 2_000, notificationWhen = 0)
        assertNull(first.stableTransaction)
        assertNotEquals(first.primary, later.primary)
        assertEquals(first.primary, identity(notificationWhen = 0, payment = payment.copy(merchant = "补充商户")).primary)
    }

    private fun identity(
        packageName: String = "com.tencent.mm",
        key: String = "payment-key",
        postTime: Long = 1_001,
        notificationWhen: Long = 1_000,
        payment: ParsedPayment = this.payment,
    ) = NotificationEventIdentity.create(packageName, key, postTime, notificationWhen, payment)
}
