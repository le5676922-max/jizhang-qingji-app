package com.example.jizhang.recognition

import com.example.jizhang.domain.BusinessType
import com.example.jizhang.domain.EntrySource
import com.example.jizhang.domain.EntryType
import com.example.jizhang.domain.PaymentNotificationParser
import com.example.jizhang.domain.ReceiptOrderIdentifiers
import com.example.jizhang.domain.ReceiptSemantics
import com.example.jizhang.domain.ScreenPayment
import java.security.MessageDigest
import java.util.UUID

/** Adapts existing conservative parsers; unknown formats do not become invented transactions. */
object CandidateFactory {
    fun fromScreen(
        screen: ScreenPayment,
        capturedAt: Long,
        evidence: EvidenceType,
        userInitiated: Boolean = false,
    ): TransactionCandidate {
        val payment = screen.payment
        // Each collection channel has its own event. A legacy hash is lookup-only: the
        // repository must not register it as a common alias for new node/OCR/image events.
        val stable = screen.hasStableIdentity &&
            (screen.businessType != BusinessType.REFUND || screen.orderFacts.orderId != null)
        val identity = if (stable) "screen-v2|$evidence|${payment.source}|${screen.receiptKey}"
            else "screen-review-v2|${UUID.randomUUID()}"
        return TransactionCandidate(
            provider = payment.source, eventFingerprint = sha256(identity),
            amountCents = payment.amountCents,
            direction = if (payment.type == EntryType.EXPENSE) FundsDirection.OUTGOING else FundsDirection.INCOMING,
            businessType = screen.businessType, merchant = safeMerchant(payment.merchant),
            occurredAt = screen.occurredAt ?: capturedAt, observedAt = capturedAt,
            categorySuggestion = payment.category, completion = CompletionState.COMPLETED,
            evidence = evidence, orderId = screen.orderFacts.orderId,
            originalOrderId = screen.orderFacts.originalOrderId,
            hasStableIdentity = stable,
            ruleVersion = "${providerPrefix(payment.source)}-screen-v2.0.1", userInitiated = userInitiated,
            orderNamespace = screen.orderFacts.orderNamespace,
            originalOrderNamespace = screen.orderFacts.originalOrderNamespace,
            legacyFingerprints = if (screen.hasStableIdentity)
                listOf(sha256("screen|${payment.source}|${screen.receiptKey}")) else emptyList(),
            reasons = buildList {
                if (payment.needsReview) add("页面信息需要人工核对")
                if (screen.orderFacts.conflict) add("订单标识存在冲突")
            },
        )
    }

    fun fromNotification(
        packageName: String,
        title: String,
        body: String,
        eventFingerprint: String,
        aliases: List<String>,
        observedAt: Long,
    ): TransactionCandidate? {
        if (!eventFingerprint.matches(Regex("[a-f0-9]{64}")) || aliases.size > 2 ||
            aliases.any { !it.matches(Regex("[a-f0-9]{64}")) }) return null
        val payment = PaymentNotificationParser.parse(packageName, title, body) ?: return null
        val combined = "$title\n$body"
        val kind = ReceiptSemantics.infer(combined, payment.type, payment.merchant,
            notificationTitle = title.trim())
        val identifiers = ReceiptOrderIdentifiers.extract(combined, kind == BusinessType.REFUND)
        return TransactionCandidate(
            provider = payment.source, eventFingerprint = eventFingerprint,
            alternateFingerprints = aliases.distinct(), amountCents = payment.amountCents,
            direction = if (payment.type == EntryType.EXPENSE) FundsDirection.OUTGOING else FundsDirection.INCOMING,
            businessType = kind, merchant = safeMerchant(payment.merchant),
            occurredAt = observedAt, observedAt = observedAt, categorySuggestion = payment.category,
            completion = CompletionState.COMPLETED, evidence = EvidenceType.NOTIFICATION,
            orderId = identifiers.orderId, originalOrderId = identifiers.originalOrderId,
            hasStableIdentity = true, ruleVersion = "${providerPrefix(payment.source)}-notification-v2.0.1",
            orderNamespace = identifiers.orderNamespace, originalOrderNamespace = identifiers.originalOrderNamespace,
            reasons = buildList {
                if (payment.needsReview) add("通知信息需要人工核对")
                if (identifiers.conflict) add("订单标识存在冲突")
            },
        )
    }

    private fun safeMerchant(value: String): String = value.trim().take(80)
        .takeUnless { Regex("[0-9]{7,}|密码|验证码|银行卡号").containsMatchIn(it) }.orEmpty()

    private fun providerPrefix(source: EntrySource): String = when (source) {
        EntrySource.WECHAT -> "wechat"
        EntrySource.ALIPAY -> "alipay"
        EntrySource.MANUAL -> "unsupported"
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
