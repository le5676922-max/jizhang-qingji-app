package com.example.jizhang.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerCategoriesTest {
    @Test
    fun categoriesAreStableAndSuggestionsAreInTheirCorrespondingList() {
        assertEquals(listOf("餐饮", "购物", "交通", "生活", "娱乐", "医疗", "教育", "其他"), LedgerCategories.expense)
        assertEquals(listOf("工资", "收款", "退款", "其他"), LedgerCategories.income)
        listOf("星巴克", "滴滴出行", "京东", "电费", "医院", "学校学费", "电影院", "未知商户")
            .forEach { assertTrue(LedgerCategories.infer(it, EntryType.EXPENSE) in LedgerCategories.expense) }
    }

    @Test
    fun categoryInferenceUsesTransactionDirection() {
        assertEquals("餐饮", LedgerCategories.infer("Starbucks", EntryType.EXPENSE))
        assertEquals("交通", LedgerCategories.infer("滴滴出行", EntryType.EXPENSE))
        assertEquals("医疗", LedgerCategories.infer("医院药房", EntryType.EXPENSE))
        assertEquals("生活", LedgerCategories.infer("水费", EntryType.EXPENSE))
        assertEquals("退款", LedgerCategories.infer("星巴克退款到账", EntryType.INCOME))
        assertEquals("工资", LedgerCategories.infer("工资到账", EntryType.INCOME))
        assertEquals("收款", LedgerCategories.infer("收到转账", EntryType.INCOME))
        assertEquals("其他", LedgerCategories.infer("不认识的商户", EntryType.EXPENSE))
        assertEquals("其他", LedgerCategories.infer("意外收入", EntryType.INCOME))
    }
}
