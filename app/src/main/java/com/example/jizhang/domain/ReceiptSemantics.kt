package com.example.jizhang.domain

/** These local rules suggest economic meaning; they never approve a ledger write. */
object ReceiptSemantics {
    fun infer(
        text: String,
        direction: EntryType,
        merchant: String,
        isRefund: Boolean = false,
        isEnvelope: Boolean = false,
        notificationTitle: String = "",
    ): BusinessType {
        if (isRefund || Regex("(?:退款|退还|退回)(?:已)?(?:成功|到账)|已全额退款|红包已退(?:回|还)").containsMatchIn(text)) {
            return BusinessType.REFUND
        }
        if (Regex("还款|还贷|归还借款|归还贷款|还借款|还钱").containsMatchIn(text)) return BusinessType.REPAYMENT
        if (Regex("提现|余额充值|零钱充值|账户充值|钱包充值|支付宝充值|余额宝|基金赎回|赎回到账|内部转移").containsMatchIn(text)) {
            return BusinessType.TRANSFER
        }
        if (direction == EntryType.INCOME && Regex(
                "(?:工资|薪资|薪酬|奖金)(?:已)?到账|(?:工资金额|薪资金额|收入类型)[：: ]*(?:工资|薪资|[¥￥0-9])",
            ).containsMatchIn(text)) return BusinessType.INCOME
        if (isEnvelope) return if (direction == EntryType.EXPENSE) BusinessType.EXPENSE else BusinessType.INCOME
        if (Regex("转账|转出|转入|借款|贷款|理财|投资|基金申购").containsMatchIn(text)) return BusinessType.TRANSFER
        // The strict upstream parser has already authenticated the provider/title and
        // attached a single lawful amount to completion evidence. A missing merchant
        // must not remove that established payment-notification capability.
        if (direction == EntryType.EXPENSE && Regex(
                "(?:支付|付款|消费|扣款)(?:已)?(?:成功|完成)|已(?:支付|付款|消费|扣款)|微信支付凭证|向.{1,80}付款",
            ).containsMatchIn(text)) return BusinessType.EXPENSE
        val merchantField = Regex("(?:商户全称|商户名称|商家名称|商家|商户)[：: \\n]+[^：:\\n]+")
            .containsMatchIn(text)
        if (direction == EntryType.EXPENSE && merchant.isNotBlank() &&
            (merchantField || LedgerCategories.infer(merchant, direction) != "其他")) return BusinessType.EXPENSE
        if (direction == EntryType.INCOME && (merchantField ||
                notificationTitle in setOf("微信收款助手", "收款助手"))) return BusinessType.INCOME
        return BusinessType.UNKNOWN
    }
}

data class ReceiptOrderFacts(
    val orderId: String? = null,
    val originalOrderId: String? = null,
    val orderNamespace: String? = null,
    val originalOrderNamespace: String? = null,
    val conflict: Boolean = false,
)

/** Provider-issued identifiers and merchant/generic order numbers occupy different namespaces. */
object ReceiptOrderIdentifiers {
    val providerLabels = listOf("微信支付交易单号", "微信支付订单号", "支付宝交易单号", "支付宝交易号", "交易单号", "交易号", "转账单号")
    val refundLabels = listOf("退款单号", "退款订单号", "退款编号")
    val originalLabels = listOf("原微信支付交易单号", "原微信支付订单号", "原支付宝交易单号", "原支付宝交易号", "原支付交易单号", "原交易单号", "原订单号", "原支付订单号", "原订单编号")
    private val genericLabels = listOf("红包单号", "交易订单号", "订单编号", "订单号")
    private val identifier = "[A-Za-z0-9][A-Za-z0-9_-]{7,79}"

    fun extract(text: String, refund: Boolean): ReceiptOrderFacts {
        val provider = values(text, providerLabels)
        val generic = values(text, genericLabels)
        if (!refund) {
            val selected = provider.ifEmpty { generic }
            return ReceiptOrderFacts(selected.distinct().singleOrNull(),
                orderNamespace = if (provider.isNotEmpty()) "provider_transaction" else null,
                conflict = selected.distinct().size > 1)
        }
        val own = values(text, refundLabels)
        val explicitOriginal = values(text, originalLabels)
        val knownOriginal = values(text, originalLabels.filter { it.contains("交易") || it.contains("微信支付订单") })
        val original = knownOriginal.ifEmpty { explicitOriginal.ifEmpty { provider.ifEmpty { generic } } }
        // A generic transaction ID on a refund page has an unknown role. It can identify
        // the page, but cannot assert that it is a new refund or an original payment.
        return ReceiptOrderFacts(
            orderId = own.distinct().singleOrNull(), originalOrderId = original.distinct().singleOrNull(),
            orderNamespace = if (own.isNotEmpty()) "provider_refund" else null,
            originalOrderNamespace = if (knownOriginal.isNotEmpty() && knownOriginal.distinct().size == 1) "provider_transaction" else null,
            conflict = own.distinct().size > 1 || original.distinct().size > 1,
        )
    }

    private fun values(text: String, labels: List<String>): List<String> = labels.flatMap { label ->
        Regex("(?:^|[\\s，,;；])${Regex.escape(label)}[：:\\s]+($identifier)(?=$|[\\s，,。;；])")
            .findAll(text).map { it.groupValues[1] }.toList()
    }
}
