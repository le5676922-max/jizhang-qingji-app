package com.example.jizhang.data

data class LedgerSettings(
    val autoEnabled: Boolean = false,
    val wechatEnabled: Boolean = true,
    val alipayEnabled: Boolean = true,
    val monthlyBudgetCents: Long = 300_000,
    val screenEnabled: Boolean = false,
    val screenOcrEnabled: Boolean = false,
)
