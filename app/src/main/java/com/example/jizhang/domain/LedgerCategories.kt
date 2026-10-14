package com.example.jizhang.domain

import java.util.Locale

object LedgerCategories {
    val expense: List<String> = listOf("餐饮", "购物", "交通", "生活", "娱乐", "医疗", "教育", "其他")
    val income: List<String> = listOf("工资", "收款", "退款", "其他")

    private val expenseKeywords = listOf(
        "医疗" to listOf("医院", "药店", "药房", "门诊", "挂号", "医疗", "诊所"),
        "教育" to listOf("学费", "学校", "教育", "培训", "课程", "书店"),
        "交通" to listOf("地铁", "公交", "出租", "滴滴", "高德打车", "停车", "加油", "车票", "铁路", "机票", "高速"),
        "生活" to listOf("水费", "电费", "燃气", "房租", "物业", "话费", "宽带"),
        "餐饮" to listOf("餐厅", "餐饮", "饭店", "外卖", "咖啡", "奶茶", "火锅", "肯德基", "麦当劳", "星巴克", "早餐", "午餐", "晚餐", "kfc", "starbucks"),
        "娱乐" to listOf("电影", "影院", "游戏", "娱乐", "ktv", "乐园"),
        "购物" to listOf("淘宝", "天猫", "京东", "超市", "便利店", "商场", "拼多多", "购物", "服装"),
    )

    /** Keyword suggestions are editable; unrecognized merchants remain in “其他”. */
    fun infer(text: String, type: EntryType): String {
        val normalized = text.lowercase(Locale.ROOT)
        if (type == EntryType.INCOME) {
            return when {
                listOf("退款", "退还").any(normalized::contains) -> "退款"
                listOf("工资", "薪资", "薪酬", "奖金").any(normalized::contains) -> "工资"
                listOf("收款", "转账", "到账", "入账").any(normalized::contains) -> "收款"
                else -> "其他"
            }
        }
        return expenseKeywords.firstOrNull { (_, keywords) -> keywords.any(normalized::contains) }
            ?.first ?: "其他"
    }
}
