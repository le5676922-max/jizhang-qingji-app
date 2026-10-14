package com.example.jizhang.domain

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** A local receipt observation. The automation engine hashes receiptKey before persistence. */
data class ScreenPayment(
    val payment: ParsedPayment,
    val transactionId: String? = null,
    val occurredAt: Long? = null,
    val receiptKey: String,
    val summary: String,
    val hasStableIdentity: Boolean = transactionId != null || occurredAt != null,
    val businessType: BusinessType = BusinessType.UNKNOWN,
    val orderFacts: ReceiptOrderFacts = ReceiptOrderFacts(),
)

/**
 * Conservative parser for visible WeChat/Alipay result and detail pages, not chat messages.
 * Accessibility text and OCR wording are not stable provider APIs. Unknown layouts are ignored.
 * No raw screen text, account number, password, or verification code is returned for storage.
 */
object ScreenPaymentParser {
    private val detailTitle = Regex("^(?:转账详情|交易详情|交易记录详情|账单详情|支付详情|付款详情|收款详情|红包详情|微信红包|支付宝红包)$")
    private val outgoing = Regex("^(?:转账成功|转出成功|转账已转出|已转出|已成功转出|付款成功|支付成功|支付完成|付款完成|交易状态[：: ]*(?:支付成功|付款成功|转出成功))[。！!]?$")
    private val incoming = Regex("^(?:收款成功|收款到账|收款已到账|转账到账|转账已到账|已到账|入账成功|已入账|到账成功|已收到转账|交易状态[：: ]*(?:收款成功|已到账))[。！!]?$")
    private val refund = Regex("^(?:退款成功|退款到账|退款已到账|已退款|已退还|已全额退款|红包退款成功|红包退回成功|红包已退还|红包已退回)[。！!]?$")
    private val transferCounterpartyReceived = Regex("^(?:对方已收款|对方已收钱)[。！!]?$")
    private val genericTransferSuccess = Regex("^转账成功[。！!]?$")
    private val transferSelfReceived = Regex("^(?:你已收款|您已收款|你已收钱|您已收钱|已存入零钱)[。！!]?$")
    private val transferred = Regex("^已转账[。！!]?$")
    private val transferExpenseStatuses = Regex("^(?:转账成功|转出成功|转账已转出|已转出|已成功转出|已转账|对方已收款|对方已收钱)[。！!]?$")
    private val transferIncomeStatuses = Regex("^(?:收款成功|收款到账|收款已到账|转账到账|转账已到账|已到账|入账成功|已入账|到账成功|已收到转账|你已收款|您已收款|你已收钱|您已收钱|已存入零钱)[。！!]?$")
    private val envelopeSent = Regex("^(?:红包已发出|已发出红包|红包发送成功|红包已发送|已发送红包)[。！!]?$")
    private val envelopeReceived = Regex("^(?:红包领取成功|已领取红包|红包已到账|已收到红包)[。！!]?$")
    private val denied = Regex(
        "失败|未成功|未完成|未支付|未付款|未扣款|未到账|未入账|取消|撤销|已关闭|交易关闭|" +
            "支付密码|付款码|验证码|请输入|确认支付|确认付款|确认转账|立即转账|立即支付|" +
            "塞钱进红包|放入红包|拼手气红包|支付请求|付款请求|转账请求|退款申请|申请退款|" +
            "退款审核|退款处理中|待支付|待付款|等待支付|等待付款|预计到账|将到账|尚未到账|" +
            "处理中|受理中|待处理|等待确认|待确认|状态未确认",
    )
    private val listsAndChats = Regex("^(?:账单|交易记录|账单列表|聊天记录|微信聊天|搜索聊天记录|转账记录|红包记录|收支明细|最近交易|通讯录|发送|按住说话|切换到按住说话|聊天信息|聊天详情|消息|联系人)$")
    private val pending = Regex("待收款|未收款|待领取|未领取|请收款|请领取|待到账|等待到账")
    private val amountValue = Regex("^([+−-]?)\\s*(?:[¥￥]\\s*)?([0-9]+(?:\\.[0-9]{1,2})?)\\s*(?:元|人民币)?$")
    private val currencyValue = Regex("[¥￥]\\s*[+−-]?[0-9][0-9.,]*|[+−-]?[0-9][0-9.,]*\\s*(?:元|人民币)")
    private val auxiliaryMoney = Regex("^(?:手续费|服务费|余额|账户余额|可用余额|零钱余额|优惠|优惠金额|优惠券|原价|商品金额|订单金额|红包个数|已领取个数|领取人数|剩余个数|备注|说明|祝福语|留言)(?:[：: ]|$)")
    private val idLabels = (ReceiptOrderIdentifiers.providerLabels + listOf("交易订单号", "红包单号", "订单编号", "订单号")).distinct()
    private val refundIdLabels = listOf("退款单号", "退款订单号", "退款编号")
    private val timeLabels = listOf("交易时间", "转账时间", "支付时间", "付款时间", "收款时间", "到账时间", "发送时间", "退款时间", "退还时间")
    private val identity = Regex("[A-Za-z0-9][A-Za-z0-9_-]{7,79}")
    private val dateTime = Regex("(?:20[0-9]{2})(?:-[0-9]{2}-[0-9]{2}|/[0-9]{2}/[0-9]{2}|年[0-9]{2}月[0-9]{2}日) [0-9]{2}:[0-9]{2}(?::[0-9]{2})?")
    private val excludedBareLabels = Regex("^(?:红包个数|已领取个数|领取人数|剩余个数|账户|账号|收款账号|付款账号|手机号|银行卡|卡号|商户单号|商家订单号)(?:[：: ]|$)")
    private val amountFieldLabels = listOf("实付金额", "实际付款", "实际支付", "实付", "转账金额", "付款金额", "支付金额", "消费金额", "扣款金额", "红包总金额", "总金额", "红包金额", "退款金额", "退还金额", "退回金额", "退款总金额", "到账金额", "收款金额", "入账金额", "领取金额", "收到金额", "金额", "交易金额")
    private val ocrAmountAliases = (amountFieldLabels + listOf("优惠金额", "商品金额", "订单金额"))
        .filter { it.contains("金额") }.associate { it.replace("金额", "金額") to it } +
        mapOf("转账全額" to "转账金额", "转账全额" to "转账金额")

    fun parse(packageName: String, text: String, fromOcr: Boolean = false): ScreenPayment? {
        val source = when (packageName) {
            "com.tencent.mm" -> EntrySource.WECHAT
            "com.eg.android.AlipayGphone" -> EntrySource.ALIPAY
            else -> return null
        }
        val clean = normalize(text)
        if (clean.isEmpty() || clean.length > 12_000 || denied.containsMatchIn(clean)) return null
        val lines = clean.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.any(listsAndChats::matches)) return null
        val hasDetail = lines.any(detailTitle::matches)
        val hasTransferDetail = lines.any { it == "转账详情" } ||
            (hasDetail && fieldValues(lines, listOf("交易类型", "交易方式")).any { it.value == "转账" })
        val hasEnvelope = lines.any { it in setOf("红包详情", "微信红包", "支付宝红包") }
        val sentEnvelope = lines.any(envelopeSent::matches) ||
            (hasEnvelope && lines.any { it == "你发出的红包" || it == "您发出的红包" })
        val receivedEnvelope = hasEnvelope && lines.any(envelopeReceived::matches)
        val refunded = lines.any(refund::matches)
        val outgoingTransferAlias = hasTransferDetail && lines.any(transferCounterpartyReceived::matches)
        val incomingTransferAlias = hasTransferDetail && lines.any(transferSelfReceived::matches)
        // “已转账” alone does not name the direction. A transfer detail plus a labeled
        // recipient establishes that the observer paid, rather than received, this transfer.
        val directionalTransferred = hasTransferDetail && lines.any(transferred::matches) &&
            fieldValues(lines, listOf("收款方", "收款人", "转账对象")).any { safeCounterpartyValue(it.value) }
        val out = lines.any(outgoing::matches) || sentEnvelope || outgoingTransferAlias || directionalTransferred
        val inward = lines.any(incoming::matches) || receivedEnvelope || refunded || incomingTransferAlias
        if (out == inward) return null
        // The provider's generic “转账成功” is commonly an outgoing result, but cannot
        // override a collection detail or payer-only inward layout. Explicitly opposite
        // page semantics indicate a conflicting/unknown receipt, not a debit to guess.
        if (out && lines.any { it == "收款详情" }) return null
        if (inward && !refunded && lines.any { it == "付款详情" }) return null
        val genericOutgoingOnly = lines.any(genericTransferSuccess::matches) &&
            !lines.any { outgoing.matches(it) && !genericTransferSuccess.matches(it) } &&
            !sentEnvelope && !outgoingTransferAlias && !directionalTransferred
        if (genericOutgoingOnly &&
            fieldValues(lines, listOf("付款方", "付款人", "转账方", "发送方")).any { safeCounterpartyValue(it.value) } &&
            fieldValues(lines, listOf("收款方", "收款人", "转账对象")).none { safeCounterpartyValue(it.value) }) return null
        // A pending recipient does not reverse a completed outgoing debit. Generic pending
        // cards lack debit evidence, and incoming funds cannot be counted before arrival.
        if (pending.containsMatchIn(clean) && (!out || (!hasDetail && !lines.any(envelopeSent::matches)))) return null
        val explicitResultLines = lines.filter { outgoing.matches(it) || incoming.matches(it) ||
            refund.matches(it) || envelopeSent.matches(it) || envelopeReceived.matches(it) ||
            (outgoingTransferAlias && transferCounterpartyReceived.matches(it)) ||
            (incomingTransferAlias && transferSelfReceived.matches(it)) ||
            (directionalTransferred && transferred.matches(it)) }
        // A page containing several result cards is not a single receipt, even when all
        // cards happen to show the same amount. Duplicate a11y nodes should be removed
        // by the screen collector; an unknown multi-status layout is safer to ignore.
        if (explicitResultLines.size > 2) return null
        val hasExplicitResult = explicitResultLines.isNotEmpty()
        if (!hasDetail && !hasExplicitResult) return null
        if (sentEnvelope && !hasExplicitResult && !hasEnvelope) return null

        val type = if (out) EntryType.EXPENSE else EntryType.INCOME
        // Field-only OCR corrections happen after completed-direction checks. Never alter
        // statuses, digits, decimals, IDs, or names; every OCR candidate still needs review.
        val amountLines = if (fromOcr) normalizeOcrAmountFields(lines) else lines
        val amount = parseAmount(amountLines, type, refunded, hasEnvelope || sentEnvelope) ?: return null
        val refundIds = if (refunded) fieldValues(lines, refundIdLabels) else emptyList()
        val ids = refundIds.ifEmpty {
            fieldValues(lines, ReceiptOrderIdentifiers.providerLabels).ifEmpty { fieldValues(lines, idLabels) }
        }
        if (ids.any { !identity.matches(it.value) }) return null
        val distinctIds = ids.map { it.value }.distinct()
        if (distinctIds.size > 1) return null
        val transactionId = distinctIds.singleOrNull()

        val times = fieldValues(lines, timeLabels)
        val preferredTimes = if (refunded) {
            times.filter { it.label in setOf("退款时间", "退还时间") }.ifEmpty { times }
        } else times.filter { it.label !in setOf("退款时间", "退还时间") }
        val timestamps = preferredTimes.map { parseTime(it.value) ?: return null }.distinct()
        if (timestamps.size > 1) return null
        val occurredAt = timestamps.singleOrNull()
        val secondsPrecision = preferredTimes.any { it.value.count { character -> character == ':' } == 2 }
        val hasStableIdentity = if (refunded && refundIds.isEmpty()) {
            // Original order IDs identify the debit, not each of its partial refunds.
            occurredAt != null && secondsPrecision
        } else transactionId != null || (occurredAt != null && secondsPrecision)
        // Providers can render one transfer's success plus where it arrived. Permit two
        // different synonymous statuses only on a transfer detail with one stable order;
        // identical repeated statuses, mixed types, and unidentified multi-card pages fail.
        if (explicitResultLines.size > 1 && !(hasTransferDetail && transactionId != null &&
                explicitResultLines.distinct().size == 2 &&
                (explicitResultLines.all(transferExpenseStatuses::matches) ||
                    explicitResultLines.all(transferIncomeStatuses::matches)))) return null
        val merchant = merchant(lines, type)
        val businessType = ReceiptSemantics.infer(clean, type, merchant,
            isRefund = refunded, isEnvelope = hasEnvelope || sentEnvelope)
        val orderFacts = ReceiptOrderIdentifiers.extract(clean, refunded)
        val categoryText = when {
            refunded -> "$merchant 退款"
            type == EntryType.INCOME && receivedEnvelope -> "$merchant 红包收款"
            type == EntryType.INCOME -> "$merchant 转账收款"
            else -> merchant
        }
        val payment = ParsedPayment(
            amountCents = amount,
            type = type,
            category = LedgerCategories.infer(categoryText, type),
            merchant = merchant,
            source = source,
            needsReview = fromOcr || !hasStableIdentity || orderFacts.conflict,
        )
        // IDs identify the same page across formatting/merchant-label changes. An original
        // debit and later refund can share a provider order, so direction remains in the key.
        val receiptKey = when {
            // An original payment order can have several partial refunds. If a dedicated
            // refund ID is absent, keep refund time/amount in its conservative identity.
            refunded && transactionId != null && refundIds.isEmpty() ->
                "${source.name}|${type.name}|order-refund|$transactionId|$amount|${occurredAt ?: "unverified"}"
            transactionId != null -> "${source.name}|${type.name}|id|$transactionId"
            occurredAt != null -> "${source.name}|${type.name}|time|$occurredAt|$amount"
            else -> "${source.name}|${type.name}|unverified|$amount|${merchant.lowercase(Locale.ROOT)}"
        }
        val provider = if (source == EntrySource.WECHAT) "微信" else "支付宝"
        val kind = when {
            refunded -> "退款收入"
            sentEnvelope -> "红包支出"
            receivedEnvelope -> "红包收入"
            type == EntryType.EXPENSE -> "付款 / 转账支出"
            else -> "收款收入"
        }
        return ScreenPayment(payment, transactionId, occurredAt, receiptKey,
            "$provider · $kind · ${Money.format(amount)}元" + if (merchant.isEmpty()) "" else " · $merchant",
            hasStableIdentity = hasStableIdentity, businessType = businessType, orderFacts = orderFacts)
    }

    private data class Field(val label: String, val value: String, val line: Int, val valueLine: Int)
    private data class AmountCandidate(val rank: Int, val cents: Long, val line: Int, val valueLine: Int)

    private fun parseAmount(lines: List<String>, type: EntryType, refunded: Boolean, envelope: Boolean): Long? {
        val rankedLabels = when {
            refunded -> listOf(
                listOf("退款金额", "退还金额", "退回金额", "退款总金额"),
                listOf("到账金额", "收款金额", "入账金额"), listOf("金额", "交易金额"),
            )
            type == EntryType.INCOME -> listOf(
                listOf("到账金额", "收款金额", "入账金额", "领取金额", "收到金额"),
                listOf("红包金额"), listOf("金额", "交易金额"),
            )
            envelope -> listOf(
                listOf("红包总金额", "总金额", "红包金额"),
                listOf("实付金额", "支付金额", "付款金额"), listOf("金额", "交易金额"),
            )
            else -> listOf(
                listOf("实付金额", "实际付款", "实际支付", "实付"),
                listOf("转账金额", "付款金额", "支付金额", "消费金额", "扣款金额"),
                listOf("金额", "交易金额"),
            )
        }
        val candidates = rankedLabels.flatMapIndexed { rank, labels ->
            fieldValues(lines, labels).map { field ->
                val cents = parseMoney(field.value, type) ?: return null
                AmountCandidate(rank, cents, field.line, field.valueLine)
            }
        }
        val known = candidates.flatMap { listOf(it.line, it.valueLine) }.toSet()
        // Opposite-direction amount fields can identify a second event on a mixed page.
        if (fieldValues(lines, amountFieldLabels).any { it.line !in known }) return null
        val excludedFields = fieldValues(lines, idLabels + refundIdLabels + ReceiptOrderIdentifiers.originalLabels + timeLabels)
        val excludedLines = excludedFields.flatMap { listOf(it.line, it.valueLine) }.toMutableSet()
        lines.forEachIndexed { index, line ->
            if (auxiliaryMoney.containsMatchIn(line) || excludedBareLabels.containsMatchIn(line)) {
                excludedLines.add(index)
                // Accessibility often exposes label/value as neighboring nodes.
                if (!line.contains('：') && !line.contains(':') && !line.contains(' ') && index + 1 < lines.size) {
                    excludedLines.add(index + 1)
                }
            }
        }
        val standalone = mutableListOf<Long>()
        lines.forEachIndexed { index, line ->
            if (index in known || index in excludedLines) return@forEachIndexed
            if (amountValue.matches(line)) {
                standalone.add(parseMoney(line, type) ?: return null)
            } else if (currencyValue.containsMatchIn(line)) {
                // Never attach a status to an unrelated message/row amount.
                return null
            }
        }
        if (candidates.isEmpty()) return standalone.distinct().singleOrNull()
        val bestRank = candidates.minOf { it.rank }
        val best = candidates.filter { it.rank == bestRank }.map { it.cents }.distinct().singleOrNull() ?: return null
        if (standalone.any { it != best }) return null
        // Explicit actual-pay/refund/total fields outrank an order's gross amount. Two
        // conflicting main fields of equal rank, or lower-rank main fields, are ambiguous.
        if (bestRank != 0 && candidates.any { it.cents != best }) return null
        return best
    }

    private fun parseMoney(value: String, type: EntryType): Long? {
        val match = amountValue.matchEntire(value) ?: return null
        val sign = match.groupValues[1]
        if ((sign == "+" && type == EntryType.EXPENSE) ||
            (sign in setOf("-", "−") && type == EntryType.INCOME)) return null
        return Money.parseCents(match.groupValues[2])
    }

    private fun normalizeOcrAmountFields(lines: List<String>): List<String> = lines.map { line ->
        val alias = ocrAmountAliases.keys.firstOrNull { label ->
            line == label || line.startsWith("$label：") || line.startsWith("$label:") || line.startsWith("$label ")
        }
        if (alias == null) line else ocrAmountAliases.getValue(alias) + line.removePrefix(alias)
    }

    private fun fieldValues(lines: List<String>, labels: List<String>): List<Field> {
        val result = mutableListOf<Field>()
        // Longest-first prevents “金额” from consuming “退款金额”, or shorter ID labels
        // from consuming provider-prefixed ones. Whole-line fields do not borrow chats.
        val sorted = labels.sortedByDescending(String::length)
        lines.forEachIndexed { index, line ->
            val label = sorted.firstOrNull { candidate ->
                line == candidate || line.startsWith("$candidate：") || line.startsWith("$candidate:") ||
                    line.startsWith("$candidate ")
            } ?: return@forEachIndexed
            val inline = line.removePrefix(label).trimStart('：', ':', ' ')
            if (inline.isNotEmpty()) result.add(Field(label, inline, index, index))
            else if (index + 1 < lines.size) result.add(Field(label, lines[index + 1], index, index + 1))
        }
        return result
    }

    private fun merchant(lines: List<String>, type: EntryType): String {
        val labels = if (type == EntryType.EXPENSE) {
            listOf("收款方", "收款人", "转账对象", "商户名称", "商家名称", "商户全称", "商家", "商户", "交易对方")
        } else listOf("付款方", "付款人", "转账方", "发送方", "商户名称", "商家名称", "交易对方")
        return fieldValues(lines, labels).firstOrNull()?.value
            ?.takeIf(::safeCounterpartyValue)
            .orEmpty()
    }

    private fun safeCounterpartyValue(value: String): Boolean = value.isNotEmpty() &&
        value.length <= 80 && !currencyValue.containsMatchIn(value) &&
        !Regex("[0-9]{7,}").containsMatchIn(value) && !detailTitle.matches(value) &&
        !outgoing.matches(value) && !incoming.matches(value) && !refund.matches(value) &&
        !transferCounterpartyReceived.matches(value) && !transferSelfReceived.matches(value) &&
        !transferred.matches(value)

    private fun parseTime(value: String): Long? {
        if (!dateTime.matches(value)) return null
        val format = when {
            value.contains('年') -> "yyyy年MM月dd日 HH:mm"
            value.contains('/') -> "yyyy/MM/dd HH:mm"
            else -> "yyyy-MM-dd HH:mm"
        } + if (value.count { it == ':' } == 2) ":ss" else ""
        val parser = SimpleDateFormat(format, Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }
        val position = ParsePosition(0)
        val parsed = parser.parse(value, position) ?: return null
        return parsed.time.takeIf { position.index == value.length }
    }

    private fun normalize(text: String): String = text
        .replace("\r\n", "\n").replace('\r', '\n')
        .replace('\u00a0', ' ').replace('\u3000', ' ')
        .replace("\u200b", "").trim()
}
