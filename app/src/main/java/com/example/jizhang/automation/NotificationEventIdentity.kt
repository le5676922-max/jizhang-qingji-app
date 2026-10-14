package com.example.jizhang.automation

import com.example.jizhang.domain.ParsedPayment
import java.security.MessageDigest

data class NotificationFingerprints(val primary: String, val stableTransaction: String?)

/**
 * One notification may gain merchant details or a new postTime during an update.
 * Its stable identity therefore uses only package, key, nonzero when, amount, and direction.
 * Distinct keys or event times remain distinct even when their amounts are equal.
 * If an app reuses all five identity fields for another payment, notifications cannot distinguish it.
 */
object NotificationEventIdentity {
    fun create(
        packageName: String,
        key: String,
        postTime: Long,
        notificationWhen: Long,
        payment: ParsedPayment,
    ) = NotificationFingerprints(
        primary = sha256("$packageName\u0000$key\u0000$postTime"),
        stableTransaction = if (notificationWhen > 0) {
            sha256("transaction\u0000$packageName\u0000$key\u0000$notificationWhen\u0000${payment.amountCents}\u0000${payment.type.name}")
        } else null,
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
