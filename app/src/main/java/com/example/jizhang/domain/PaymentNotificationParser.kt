package com.example.jizhang.domain

/**
 * Conservative, local parser for completed transactions in payment-service notifications.
 * Notification wording is not a public WeChat/Alipay API. Unknown formats are ignored;
 * notification permission does not reveal transactions that these apps never notify about.
 */
object PaymentNotificationParser {
    private val wechatTitles = setOf(
        "微信支付", "微信支付凭证", "收款到账通知", "微信收款助手", "收款助手",
    )
    private val alipayTitles = setOf(
        "支付宝", "支付宝通知", "交易提醒", "支付助手", "付款成功", "支付成功",
        "收款到账", "收款成功", "收款到账通知", "到账通知", "到账提醒", "转账到账",
        "退款成功", "退款到账",
    )
    private val excluded = Regex(
        "失败|未成功|未完成|未支付|未付款|未扣款|未到账|未入账|取消|关闭|撤销|撤回|" +
            "处理中|待处理|受理中|待到账|等待到账|将到账|即将到账|预计|将于|待入账|" +
            "待支付|待付款|等待支付|等待付款|请支付|请付款|请收款|请领取|待收款|待领取|未领取|未收款|" +
            "尚未|待确认|未确认|等待确认|支付请求|付款请求|转账请求|" +
            "退款申请|申请退款|退款受理|退款审核|" +
            "优惠券|代金券|满减|红包|促销|优惠活动|立减|返现|现金奖励|领取福利|积分|余额|" +
            "还款提醒|账单已出|待还|转账给我|转给我|借给我|" +
            "(?:请|需要|记得|赶紧|麻烦).{0,8}(?:付款|支付|转账|收款)|[?？]",
    )
    private val expenseEvidence = Regex(
        "(?:支付|付款|扣款|消费)(?:已)?(?:成功|完成)|" +
            "(?:已成功|成功)(?:支付|付款|扣款|消费)|已(?:支付|付款|扣款|消费)|" +
            "(?:你|您)(?:已)?(?:向|给|在).{1,60}?(?:支付|付款|消费)了|微信支付凭证",
    )
    private val incomeEvidence = Regex(
        "(?:收款|转账|工资|薪资|收入|退款|退还)(?:已)?到账|" +
            "(?:收款|退款|退还)(?:已)?成功|(?:已成功|成功)(?:收款|入账)|" +
            "(?:已)?(?:到账|入账)|(?:已)?收到.{0,30}(?:转账|款项|退款|工资)",
    )
    private val amount = Regex(
        "(?<![0-9.,+−-])(?:[¥￥]\\s*([0-9]+(?:\\.[0-9]{1,2})?)|" +
            "([0-9]+(?:\\.[0-9]{1,2})?)\\s*(?:元|人民币))(?![0-9.,])",
    )
    private val trailingField = Regex(
        "\\s+(?:支付金额|付款金额|交易金额|收款金额|金额|支付时间|付款时间|交易时间|订单号|交易单号)[：:]?",
    )
    // Observed WeChat payment-service receipts use this complete, directional sentence
    // without an additional “成功”: “向 麦当劳 付款 12.30 元” / “向你付款 100.00 元”.
    private val wechatDirectionalReceipt = Regex(
        "向\\s*(.{1,60}?)\\s*付款\\s*(?:[¥￥]\\s*)?[0-9]+(?:\\.[0-9]{1,2})?\\s*元[。！!]?",
    )

    /** Check only the title before callers copy a payment notification's body. */
    fun isServiceTitle(packageName: String, title: String): Boolean {
        val normalized=normalize(title).trim()
        return when(packageName) {
            "com.tencent.mm" -> normalized in wechatTitles
            "com.eg.android.AlipayGphone" -> normalized in alipayTitles
            else -> false
        }
    }

    fun parse(packageName: String, title: String, text: String): ParsedPayment? {
        val source = when (packageName) {
            "com.tencent.mm" -> EntrySource.WECHAT
            "com.eg.android.AlipayGphone" -> EntrySource.ALIPAY
            else -> return null
        }
        val cleanTitle = normalize(title).trim()
        val allowedTitles = if (source == EntrySource.WECHAT) wechatTitles else alipayTitles
        if (cleanTitle !in allowedTitles) return null
        val body = normalize(text).trim()
        if (body.isEmpty() || body.length > 4_000) return null
        val combined = "$cleanTitle\n$body"
        if (excluded.containsMatchIn(combined)) return null

        val directionalReceipt = if (
            source == EntrySource.WECHAT && cleanTitle in setOf("微信支付", "微信支付凭证")
        ) wechatDirectionalReceipt.matchEntire(body) else null
        val receiptRecipient = directionalReceipt?.groupValues?.get(1)?.trim()
        if (receiptRecipient != null && receiptRecipient.isEmpty()) return null
        val receiptIsIncome = receiptRecipient in setOf("你", "您")
        if (directionalReceipt == null && Regex("向\\s*[你您]\\s*(?:付款|支付)").containsMatchIn(body)) {
            // An unrecognized inward payment template must not inherit an expense status.
            return null
        }
        // A status title can provide the completion evidence, but never the amount.
        val expense = if (directionalReceipt != null) !receiptIsIncome
            else expenseEvidence.containsMatchIn(combined)
        val income = if (directionalReceipt != null) receiptIsIncome
            else incomeEvidence.containsMatchIn(combined)
        if (expense == income) return null
        val type = if (expense) EntryType.EXPENSE else EntryType.INCOME

        // Multiple currency figures can be balances, totals, fees, or another transaction.
        // Even equal repeated figures are ignored instead of guessed or silently rounded.
        val amounts = amount.findAll(body).take(2).toList()
        if (amounts.size != 1) return null
        val money = amounts.single()
        if (directionalReceipt == null && !isAmountAssociated(body, cleanTitle, money, type)) return null
        val raw = money.groups[1]?.value ?: money.groups[2]?.value ?: return null
        val cents = Money.parseCents(raw) ?: return null
        val merchant = if (directionalReceipt != null && type == EntryType.EXPENSE) {
            receiptRecipient.orEmpty()
        } else merchant(body, type)
        val categoryText = if (directionalReceipt != null && type == EntryType.INCOME) "$body 收款"
            else "$merchant $body"
        return ParsedPayment(
            amountCents = cents,
            type = type,
            category = LedgerCategories.infer(categoryText, type),
            merchant = merchant,
            source = source,
        )
    }

    private fun isAmountAssociated(
        body: String,
        title: String,
        money: MatchResult,
        type: EntryType,
    ): Boolean {
        val labels = if (type == EntryType.EXPENSE) {
            "支付金额|付款金额|消费金额|扣款金额|实付金额|交易金额|金额"
        } else {
            "收款金额|到账金额|入账金额|退款金额|工资金额|交易金额|金额"
        }
        val beforeAmount = body.substring(0, money.range.first)
        if (Regex("(?:^|[\\s，,。;；])(?:$labels)[：:\\s]*$").containsMatchIn(beforeAmount)) {
            return true
        }
        if (type == EntryType.EXPENSE && Regex(
                "(?:^|[\\s，,。;；])(?:(?:你|您)(?:已)?)?(?:向|给|在).{1,60}?" +
                    "(?:支付|付款|消费)(?:了)?[：:\\s]*$",
            ).containsMatchIn(beforeAmount)) return true
        val evidence = if (type == EntryType.EXPENSE) expenseEvidence else incomeEvidence
        // A completed status must touch its amount, allowing punctuation and currency words.
        // “支付成功，本月预算12元” therefore does not attach the budget to the payment.
        val bridge = Regex("[\\s：:，,]*(?:人民币|元)?[\\s：:，,]*")
        if (evidence.findAll(body).any { match ->
                when {
                    match.range.last < money.range.first ->
                        bridge.matches(body.substring(match.range.last + 1, money.range.first))
                    money.range.last < match.range.first ->
                        bridge.matches(body.substring(money.range.last + 1, match.range.first))
                    else -> false
                }
            }) return true

        // Some notification layouts put the completed status in the title and only the
        // currency figure on the first body line. Provider names alone are insufficient.
        val firstLine = body.lineSequence().first().trim()
        val amountOnly = Regex("(?:[¥￥]\\s*[0-9]+(?:\\.[0-9]{1,2})?(?:元)?|[0-9]+(?:\\.[0-9]{1,2})?\\s*元)")
        return evidence.containsMatchIn(title) && amountOnly.matches(firstLine)
    }

    private fun merchant(body: String, type: EntryType): String {
        val fields = if (type == EntryType.EXPENSE) {
            "商户全称|商户名称|商家名称|收款方|商户|商家|交易对方"
        } else {
            "付款方|转账方|商户全称|商户名称|商家名称|商户|商家|交易对方"
        }
        val labeled = Regex("(?:$fields)[：:]\\s*([^，,。;；\\n]{1,80})")
            .find(body)?.groupValues?.get(1)
        if (labeled != null) {
            return trailingField.split(labeled, limit = 2).first().trim().take(80)
        }
        if (type == EntryType.EXPENSE) {
            return Regex(
                "(?:^|[\\s，,。;；])(?:(?:你|您)(?:已)?)?(?:向|给|在)(.{1,60}?)" +
                    "(?:支付|付款|消费)(?:了|(?=[\\s：:]*[¥￥0-9]))",
            )
                .find(body)?.groupValues?.get(1)?.trim().orEmpty()
        }
        return ""
    }

    private fun normalize(value: String): String = value
        .replace('\u00A0', ' ')
        .replace('\u3000', ' ')
        .replace("\u200B", "")
        .replace("\r\n", "\n")
}
