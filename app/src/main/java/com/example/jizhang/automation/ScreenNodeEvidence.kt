package com.example.jizhang.automation

import com.example.jizhang.domain.Money

/** Ephemeral pixel geometry. No Android objects or screen dumps are persisted. */
internal data class ScreenNodeBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0
    fun overlaps(other: ScreenNodeBounds): Boolean = !isEmpty && !other.isEmpty &&
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    fun inTitleBand(window: ScreenNodeBounds): Boolean = !isEmpty && !window.isEmpty &&
        top >= window.top && bottom <= window.top + window.height * 0.24 &&
        left >= window.left && right <= window.right

    fun nearValue(value: ScreenNodeBounds): Boolean {
        if (isEmpty || value.isEmpty) return false
        val verticalOverlap = (minOf(bottom, value.bottom) - maxOf(top, value.top)).coerceAtLeast(0)
        val sameRow = verticalOverlap >= minOf(height, value.height) * 0.5 && value.left >= right - height
        val below = value.top >= bottom && value.top - bottom <= maxOf(height, value.height) * 1.5 &&
            value.left <= right && value.right >= left
        return sameRow || below
    }
}

internal data class ScreenNodeEvidence(val text: String, val bounds: ScreenNodeBounds, val depth: Int)

data class ScreenCaptureMetrics(
    val visitedNodes: Int = 0,
    val retainedFields: Int = 0,
    val retainedCharacters: Int = 0,
    val durationMs: Long = 0,
)

/** Limits the collector's retained output to transaction facts, not arbitrary receipt-page text. */
internal object ReceiptNodeTextPolicy {
    private val amountLabels = setOf(
        "实付金额", "实际付款", "实际支付", "实付", "转账金额", "付款金额", "支付金额", "消费金额",
        "扣款金额", "红包总金额", "总金额", "红包金额", "退款金额", "退还金额", "退回金额",
        "退款总金额", "到账金额", "收款金额", "入账金额", "领取金额", "收到金额", "金额", "交易金额",
    )
    private val idLabels = setOf(
        "微信支付交易单号", "微信支付订单号", "支付宝交易单号", "支付宝交易号", "交易单号", "交易订单号", "转账单号", "红包单号",
        "订单编号", "订单号", "交易号", "退款单号", "退款订单号", "退款编号", "退款号",
        "原订单号", "原订单编号", "原交易单号", "原交易号", "原支付单号", "原支付订单号", "原转账单号", "原红包单号",
        "原微信支付交易单号", "原微信支付订单号", "原支付宝交易单号", "原支付宝交易号", "原支付交易单号",
    )
    private val timeLabels = setOf(
        "交易时间", "转账时间", "支付时间", "付款时间", "收款时间", "到账时间", "发送时间", "退款时间", "退还时间",
    )
    private val merchantLabels = setOf(
        "收款方", "收款人", "转账对象", "商户名称", "商家名称", "商户全称", "商家", "商户", "交易对方",
        "付款方", "付款人", "转账方", "发送方",
    )
    private val businessLabels = setOf("交易类型", "业务类型", "资金用途", "交易状态")
    private val allLabels = (amountLabels + idLabels + timeLabels + merchantLabels + businessLabels).sortedByDescending(String::length)
    private val restrictedResource = Regex(
        "(?:password|pwd|verify|captcha|bank|card|account|phone|chat|message|remark|memo|note|balance|coupon|goods|cart)",
        RegexOption.IGNORE_CASE,
    )
    private val restrictedLabel = Regex(
        "^(?:银行卡|银行卡号|卡号|账户|账号|付款账号|收款账号|银行账号|银行账户|手机号|手机号码|" +
            "备注|祝福语|留言|说明|余额|零钱余额|账户余额|优惠|优惠金额|原价|商品金额|订单金额)(?:[：: ]|$)",
    )
    private val identity = Regex("[A-Za-z0-9][A-Za-z0-9_-]{7,79}")
    private val date = Regex("20[0-9]{2}(?:[-/][0-9]{2}[-/][0-9]{2}|年[0-9]{2}月[0-9]{2}日) [0-9]{2}:[0-9]{2}(?::[0-9]{2})?")
    private val money = Regex("^[+−-]?\\s*[¥￥]?\\s*([0-9]+(?:\\.[0-9]{1,2})?)\\s*(?:元|人民币)?$")
    private val plainLongDigits = Regex("(?:[0-9][ -]?){13,19}")

    fun excludesResource(resourceId: String): Boolean = restrictedResource.containsMatchIn(resourceId.substringAfterLast('/'))
    fun excludesField(text: String): Boolean = restrictedLabel.containsMatchIn(text.trim())
    fun isBareSensitiveNumber(text: String): Boolean = plainLongDigits.matches(text.trim())
    fun containsSensitiveNumber(text: String): Boolean = plainLongDigits.containsMatchIn(text)

    fun independentText(text: String): Boolean {
        val value = text.trim()
        if (value.isEmpty() || value.length > 256 || value.contains('\n') || excludesField(value)) return false
        if (ScreenCapturePolicy.isIndependentReceiptTitle(value) ||
            value in setOf("你发出的红包", "您发出的红包", "待领取", "待收款", "已领取", "未领取")
        ) return true
        val label = label(value)
        if (label != null) {
            val inline = value.removePrefix(label).trimStart('：', ':', ' ')
            return inline.isEmpty() || safeValue(label, inline)
        }
        // A bare number that could be an account/card number is never retained as an amount.
        return validMoney(value)
    }

    fun neighboringValue(text: String, bounds: ScreenNodeBounds, retained: List<ScreenNodeEvidence>): Boolean =
        retained.any { evidence ->
            val label = label(evidence.text) ?: return@any false
            evidence.text.trim() == label && evidence.bounds.nearValue(bounds) && safeValue(label, text.trim())
        }

    private fun label(text: String): String? = allLabels.firstOrNull { label ->
        text == label || text.startsWith("$label：") || text.startsWith("$label:") || text.startsWith("$label ")
    }

    private fun safeValue(label: String, value: String): Boolean = when (label) {
        in amountLabels -> validMoney(value)
        in idLabels -> identity.matches(value)
        in timeLabels -> date.matches(value)
        in merchantLabels -> value.length in 1..80 && !plainLongDigits.containsMatchIn(value) &&
            !Regex("密码|验证码|银行卡|账号|账户").containsMatchIn(value)
        else -> value.length in 1..80 && !plainLongDigits.containsMatchIn(value) &&
            !ScreenCapturePolicy.isSensitiveContext(value, "")
    }

    private fun validMoney(value: String): Boolean {
        if (plainLongDigits.matches(value)) return false
        val match = money.matchEntire(value) ?: return false
        return Money.parseCents(match.groupValues[1]) != null
    }
}
