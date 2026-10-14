package com.example.jizhang.automation

/** Pure collection guard, kept separate so privacy limits can be checked without Android stubs. */
internal object ScreenCapturePolicy {
    const val WECHAT_PACKAGE = "com.tencent.mm"
    const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
    val SUPPORTED_PACKAGES = setOf(WECHAT_PACKAGE, ALIPAY_PACKAGE)
    const val MAX_NODES = 200
    const val MAX_DEPTH = 24
    const val TITLE_PROBE_NODES = 24
    const val TITLE_PROBE_DEPTH = 6
    const val TITLE_PROBE_CHARACTERS = 48
    const val MAX_TEXT_LENGTH = 4_096
    const val READ_INTERVAL_MS = 650L
    const val OCR_INTERVAL_MS = 8_000L

    private val receiptActivity = Regex(
        "(?:remittance(?:result|detail|receipt)|luckymoney(?:detail|busi)|" +
            "(?:payment|trade|bill|transfer)(?:result|detail|receipt))",
        RegexOption.IGNORE_CASE,
    )
    private val sensitiveActivity = Regex(
        "(?:chatting|chatactivity|password|pwd|verify|verification|luckymoney(?:prepare|send)|" +
            "remittance(?:ui$|newui$)|walletpayui)",
        RegexOption.IGNORE_CASE,
    )
    private val receiptMarkers = listOf(
        "支付成功", "付款成功", "支付完成", "付款完成", "转账成功", "转出成功", "转账已转出", "已转出", "已成功转出",
        "已转账", "对方已收款", "对方已收钱", "你已收款", "您已收款", "你已收钱", "您已收钱",
        "已收款", "收款成功", "收款到账", "收款已到账", "转账到账",
        "转账已到账", "入账成功", "已入账", "已收到转账",
        "红包已发送", "红包发送成功", "红包已发出", "红包详情", "转账详情", "交易详情",
        "已发送红包", "已发出红包", "红包领取成功", "已领取红包", "红包已到账", "已收到红包",
        "账单详情", "交易记录详情", "支付详情", "付款详情", "收款详情", "微信红包", "支付宝红包",
        "交易成功", "到账成功", "已到账",
        "已存入零钱", "退款成功", "已全额退款", "退款到账", "退款已到账", "已退款", "已退还",
        "红包已退回", "红包已退还", "红包退回成功", "红包退款成功",
    )
    private val sensitiveMarkers = listOf(
        "支付密码", "输入密码", "请输入密码", "验证码", "短信验证", "身份验证", "忘记密码",
        "请输入", "确认支付", "确认付款", "确认转账", "立即支付", "立即付款", "立即转账",
        "付款码", "输入金额", "塞钱进红包", "放入红包", "拼手气红包",
        "支付请求", "付款请求", "转账请求",
    )
    private val uncompletedTransaction = Regex(
        "失败|未成功|未完成|未支付|未付款|未扣款|未到账|未入账|取消|撤销|已关闭|交易关闭|" +
            "退款申请|申请退款|退款审核|退款处理中|待支付|待付款|等待支付|等待付款|" +
            "预计到账|将到账|尚未到账|处理中|受理中|待处理|等待确认|待确认|状态未确认",
    )
    // Exact lines distinguish a list/chat heading from legitimate "账单详情" or merchant names.
    // These match the parser's list/chat guard rather than broad substring matches.
    private val listAndChatHeadings = setOf(
        "账单", "交易记录", "账单列表", "聊天记录", "微信聊天", "搜索聊天记录", "转账记录",
        "红包记录", "收支明细", "最近交易", "通讯录", "发送", "按住说话", "切换到按住说话",
        "聊天信息", "聊天详情", "消息", "联系人", "商品详情", "购物车", "待付款订单", "待付款",
        "确认订单", "支付失败", "付款失败", "已取消", "交易已取消", "订单已取消",
    )

    fun hasPaymentContext(text: String, activityClass: String): Boolean =
        !isSensitiveContext(text, activityClass) &&
            (receiptMarkers.any(text::contains) || receiptActivity.containsMatchIn(activityClass))

    fun isKnownReceiptActivity(activityClass: String): Boolean =
        !isSensitiveContext("", activityClass) && receiptActivity.containsMatchIn(activityClass)

    fun isIndependentReceiptTitle(text: String): Boolean = lines(text).singleOrNull()
        ?.trimEnd('。', '！', '!') in receiptMarkers

    /** Generic TextViews in an unknown Activity can be chat bubbles, even near the top. */
    fun mayProbeHeading(knownActivity: Boolean, headingMetadata: Boolean, leafTextView: Boolean): Boolean =
        headingMetadata || (knownActivity && leafTextView)

    fun isExcludedPageHeading(text: String): Boolean =
        lines(text).any(listAndChatHeadings::contains) || isSensitiveContext(text, "") ||
            uncompletedTransaction.containsMatchIn(text)

    fun isSensitiveContext(text: String, activityClass: String): Boolean =
        sensitiveActivity.containsMatchIn(activityClass) || sensitiveMarkers.any(text::contains) ||
            lines(text).any(listAndChatHeadings::contains)

    fun mayUseOcr(
        packageName: String,
        text: String,
        activityClass: String,
        hasSensitiveInput: Boolean,
        recentWindowChange: Boolean,
        eventPaymentHint: Boolean,
    ): Boolean {
        if (packageName !in SUPPORTED_PACKAGES || hasSensitiveInput ||
            isSensitiveContext(text, activityClass) || uncompletedTransaction.containsMatchIn(text)
        ) return false
        // A keyword inside an ordinary chat/list row is not enough to authorize a screenshot.
        // Empty inaccessible nodes require a known receipt Activity and a fresh window/hint event.
        val independentTitle = lines(text).any(::isIndependentReceiptTitle)
        val knownReceiptActivity = isKnownReceiptActivity(activityClass)
        return (knownReceiptActivity || independentTitle) &&
            (recentWindowChange || eventPaymentHint || independentTitle)
    }

    private fun lines(text: String): Sequence<String> = text
        .replace("\r\n", "\n").replace('\r', '\n')
        .replace('\u00a0', ' ').replace('\u3000', ' ')
        .lineSequence().map(String::trim).filter(String::isNotEmpty)
}
