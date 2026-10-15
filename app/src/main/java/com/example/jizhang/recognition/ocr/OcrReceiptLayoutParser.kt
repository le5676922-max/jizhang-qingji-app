package com.example.jizhang.recognition.ocr

import com.example.jizhang.automation.ScreenCapturePolicy
import com.example.jizhang.domain.Money
import com.example.jizhang.domain.ReceiptOrderIdentifiers
import com.example.jizhang.domain.ScreenPayment
import com.example.jizhang.domain.ScreenPaymentParser

data class OcrLayoutResult(
    val payment: ScreenPayment? = null,
    val amountOptions: List<Long> = emptyList(),
    val reasons: List<String> = emptyList(),
)

/** Explicit field geometry only: never selects the first/largest/unlabelled currency value. */
object OcrReceiptLayoutParser {
    private val amountLabels = listOf("实付金额", "实际付款", "实际支付", "实付", "转账金额", "付款金额",
        "支付金额", "消费金额", "扣款金额", "红包总金额", "总金额", "红包金额", "退款金额", "退还金额",
        "退回金额", "退款总金额", "到账金额", "收款金额", "入账金额", "领取金额", "收到金额", "金额", "交易金额")
    private val auxiliary = listOf("订单金额", "商品金额", "原价", "优惠金额", "优惠", "优惠券", "账户余额",
        "余额", "零钱余额", "可用余额", "手续费", "服务费")
    private val identityLabels = (ReceiptOrderIdentifiers.providerLabels + ReceiptOrderIdentifiers.refundLabels +
        ReceiptOrderIdentifiers.originalLabels + listOf("红包单号", "交易订单号", "订单编号", "订单号")).distinct()
    private val timeLabels = listOf("交易时间", "转账时间", "支付时间", "付款时间", "收款时间", "到账时间", "发送时间", "退款时间", "退还时间")
    private val merchantLabels = listOf("收款方", "收款人", "转账对象", "商户名称", "商家名称", "商户全称",
        "商家", "商户", "交易对方", "付款方", "付款人", "转账方", "发送方")
    private val businessLabels = listOf("交易类型", "业务类型", "资金用途", "交易状态")
    private val labels = (amountLabels + auxiliary + identityLabels + timeLabels + merchantLabels + businessLabels).sortedByDescending(String::length)
    private val monetary = Regex("^([+−-]?)\\s*[¥￥]?\\s*([0-9]+(?:\\.[0-9]{1,2})?)\\s*(?:元|人民币)?$")
    private val identity = Regex("[A-Za-z0-9][A-Za-z0-9_-]{7,79}")
    private val time = Regex("20[0-9]{2}(?:[-/][0-9]{2}[-/][0-9]{2}|年[0-9]{2}月[0-9]{2}日) [0-9]{2}:[0-9]{2}(?::[0-9]{2})?")
    private val sensitiveText = Regex("密码|验证码|银行卡号|账号|手机号|(?:[0-9][ -]?){13,19}")

    /** Compatibility helper declines ambiguous amounts so callers cannot silently lose options. */
    fun parse(packageName: String, page: OcrDocument): ScreenPayment? = interpret(packageName, page)
        .takeIf { it.amountOptions.size <= 1 }?.payment

    fun interpret(packageName: String, page: OcrDocument): OcrLayoutResult {
        if (packageName !in ScreenCapturePolicy.SUPPORTED_PACKAGES || page.truncated ||
            page.width <= 0 || page.height <= 0 || page.lines.size > 128)
            return failure("图片或识别结果超出安全处理范围")
        if (page.lines.any { ScreenCapturePolicy.isExcludedPageHeading(it.text) })
            return failure("图片包含聊天、列表、未完成或敏感页面标记")
        val headings = page.lines.filter { line ->
            line.bounds?.inside(page.width, page.height) == true &&
                line.bounds.top < page.height * 0.4 && ScreenCapturePolicy.isIndependentReceiptTitle(line.text)
        }
        if (headings.isEmpty()) return failure("未找到有位置依据的独立交易标题")
        val units = mutableListOf<UnitText>()
        fun add(text: String, bounds: OcrRect?) {
            if (text.length !in 1..256 || bounds?.inside(page.width, page.height) != true) return
            val normalized = normalizeLabel(text.trim())
            if (units.any { it.text == normalized && it.bounds.overlaps(bounds) }) return
            units.add(UnitText(normalized, bounds))
        }
        page.lines.forEach { line ->
            add(line.text, line.bounds)
            line.tokens.forEach { add(it.text, it.bounds) }
        }
        val labelUnits = units.mapNotNull { unit ->
            val label = fieldLabel(unit.text) ?: return@mapNotNull null
            LabeledUnit(label, unit, unit.text.removePrefix(label).trimStart('：', ':', ' '))
        }
        val facts = mutableListOf<FieldFact>()
        for (field in labelUnits) {
            if (field.inline.isNotEmpty()) {
                if (validValue(field.label, field.inline)) addFact(facts, FieldFact(field.label, field.inline, field.unit.bounds))
                continue
            }
            val matches = units.filter { value ->
                value !== field.unit && validValue(field.label, value.text) && nearby(field.unit.bounds, value.bounds, page.width) &&
                    !labelUnits.any { other -> other !== field && blocks(field.unit.bounds, value.bounds, other.unit.bounds) }
            }
            matches.forEach { addFact(facts, FieldFact(field.label, it.text, it.bounds)) }
        }
        val moneyFacts = facts.filter { it.label in amountLabels }
        if (moneyFacts.isEmpty()) return failure("未找到金额标签与邻近金额，不猜测首个或最大金额")
        val headerFacts = headings.map { normalizeLabel(it.text.trim()) }.distinct()
        val nonMoney = facts.filter { it.label !in amountLabels && it.label !in auxiliary }
        // Direction/counterparty facts remain intact; the domain parser validates them together.
        val canonical = (headerFacts + facts.map { "${it.label}：${it.value}" }).distinct().joinToString("\n")
        val payment = ScreenPaymentParser.parse(packageName, canonical, fromOcr = true)
        if (payment != null) return OcrLayoutResult(payment)

        // Several values in one real-pay area form a review proposal, never an automatic amount.
        val refundable = headerFacts.any { it.contains("退款") || it.contains("退还") }
        val envelope = headerFacts.any { it.contains("红包") }
        val bestRank = moneyFacts.minOf { rank(it.label, refundable, envelope) }
        val preferred = moneyFacts.filter { rank(it.label, refundable, envelope) == bestRank }
        val options = preferred.mapNotNull { cents(it.value) }.distinct().sorted()
        if (options.size !in 2..8) return failure("交易方向、状态或金额区域无法一致确认")
        val label = preferred.first().label // Only the field name; no amount is chosen as actual payment.
        val proposals = options.mapNotNull { amount ->
            val proposalText = (headerFacts + nonMoney.map { "${it.label}：${it.value}" } +
                "$label：${Money.format(amount)}元").joinToString("\n")
            ScreenPaymentParser.parse(packageName, proposalText, fromOcr = true)
        }
        if (proposals.size != options.size || proposals.map {
                listOf(it.payment.type, it.businessType, it.orderFacts, it.payment.merchant, it.occurredAt)
            }.distinct().size != 1) return failure("交易方向或身份冲突，无法形成安全候选")
        return OcrLayoutResult(proposals.first(), options, listOf("实付金额区域存在多个候选值，须选择并核对实际金额"))
    }

    private data class UnitText(val text: String, val bounds: OcrRect)
    private data class LabeledUnit(val label: String, val unit: UnitText, val inline: String)
    private data class FieldFact(val label: String, val value: String, val bounds: OcrRect)
    private fun failure(message: String) = OcrLayoutResult(reasons = listOf(message))
    private fun fieldLabel(text: String): String? = labels.firstOrNull {
        text == it || text.startsWith("$it：") || text.startsWith("$it:") || text.startsWith("$it ")
    }
    private fun normalizeLabel(text: String): String {
        // Match the whole known label, not a hard-coded character offset. Keep every
        // monetary character unchanged; longer words containing the alias are not fields.
        val alias = listOf("转账全額", "转账全额").firstOrNull { spelling ->
            text == spelling || text.startsWith("$spelling：") || text.startsWith("$spelling:") || text.startsWith("$spelling ")
        }
        if (alias != null) return "转账金额" + text.removePrefix(alias)
        val traditional = amountLabels.firstOrNull { label ->
            val spelling = label.replace("额", "額")
            text == spelling || text.startsWith("$spelling：") || text.startsWith("$spelling:") || text.startsWith("$spelling ")
        } ?: return text
        return traditional + text.removePrefix(traditional.replace("额", "額"))
    }
    private fun validValue(label: String, value: String): Boolean = when (label) {
        in amountLabels, in auxiliary -> cents(value) != null
        in identityLabels -> identity.matches(value)
        in timeLabels -> time.matches(value)
        else -> value.length in 1..80 && !sensitiveText.containsMatchIn(value) && !ScreenCapturePolicy.isExcludedPageHeading(value)
    }
    private fun cents(value: String): Long? = monetary.matchEntire(value)?.groupValues?.get(2)?.let(Money::parseCents)
    private fun nearby(label: OcrRect, value: OcrRect, width: Int): Boolean {
        val overlap = (minOf(label.bottom, value.bottom) - maxOf(label.top, value.top)).coerceAtLeast(0)
        val sameRow = overlap >= minOf(label.height, value.height) * 0.5 && value.left >= label.right - label.height &&
            value.left - label.right <= width * 0.55
        val below = value.top >= label.bottom && value.top - label.bottom <= maxOf(label.height, value.height) * 1.6 &&
            value.left < label.right && value.right > label.left
        return sameRow || below
    }
    private fun blocks(label: OcrRect, value: OcrRect, other: OcrRect): Boolean =
        (other.left >= label.right && other.right <= value.left && other.top < value.bottom && other.bottom > value.top) ||
            (other.top >= label.bottom && other.bottom <= value.top && other.left < value.right && other.right > value.left)
    private fun addFact(facts: MutableList<FieldFact>, fact: FieldFact) {
        if (facts.none { it.label == fact.label && it.value == fact.value && it.bounds.overlaps(fact.bounds) }) facts.add(fact)
    }
    private fun rank(label: String, refund: Boolean, envelope: Boolean): Int = when {
        refund && label in setOf("退款金额", "退还金额", "退回金额", "退款总金额") -> 0
        envelope && label in setOf("红包总金额", "总金额", "红包金额") -> 0
        label in setOf("实付金额", "实际付款", "实际支付", "实付") -> 0
        label in setOf("金额", "交易金额") -> 2
        else -> 1
    }
}
