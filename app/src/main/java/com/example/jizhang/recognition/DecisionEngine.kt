package com.example.jizhang.recognition

import com.example.jizhang.domain.BusinessType
import com.example.jizhang.domain.EntrySource
import com.example.jizhang.domain.Money

/** Pure decision function. The repository resolves order linkage and conflicts transactionally. */
object DecisionEngine {
    fun evaluate(
        candidate: TransactionCandidate,
        duplicateConflict: Boolean = false,
        refundLinked: Boolean = false,
    ): DecisionResult {
        if (candidate.amountCents !in 1..Money.MAX_CENTS) return ignored("金额不合法，已忽略")
        if (candidate.provider !in setOf(EntrySource.WECHAT, EntrySource.ALIPAY)) return ignored("交易来源不受支持")
        when (candidate.completion) {
            CompletionState.FAILED -> return ignored("交易失败，未入账")
            CompletionState.PENDING -> return ignored("交易尚未完成，未入账")
            CompletionState.CANCELLED -> return ignored("交易已取消，未入账")
            else -> Unit
        }
        val reasons = mutableListOf<String>()
        // Only fixed adapter reasons pass through. Never persist an arbitrary screen dump
        // that a caller might accidentally put into the candidate's explanation field.
        val adapterReasons = setOf(
            "页面信息需要人工核对", "通知信息需要人工核对", "订单标识存在冲突",
        )
        reasons.addAll(candidate.reasons.filter { it in adapterReasons })
        if (candidate.reasons.any { it !in adapterReasons }) reasons.add("识别依据需要人工核对")
        if (candidate.completion != CompletionState.COMPLETED) reasons.add("交易完成状态未确认")
        if (!candidate.hasStableIdentity) reasons.add("缺少稳定交易标识，请核对是否重复")
        if (candidate.evidence in setOf(EvidenceType.OCR, EvidenceType.IMPORTED_IMAGE, EvidenceType.SHARED_IMAGE)) {
            reasons.add("图片或 OCR 识别需要人工核对")
        }
        if (candidate.evidence == EvidenceType.LEGACY) reasons.add("旧版识别依据不完整，需要人工核对")
        if (duplicateConflict) reasons.add("可能与已有交易重复，需要确认关联")
        val options = candidate.amountOptions.distinct()
        if (options.size > 1 || options.any { it != candidate.amountCents || it !in 1..Money.MAX_CENTS }) {
            reasons.add("存在多个或不一致的金额候选")
        }
        if (candidate.occurredAt <= 0 || candidate.observedAt <= 0 ||
            candidate.occurredAt > candidate.observedAt + 5 * 60_000L) reasons.add("交易时间需要核对")
        if (candidate.observedAt - candidate.occurredAt > 15 * 60_000L) reasons.add("历史交易需要核对")
        when (candidate.businessType) {
            BusinessType.TRANSFER -> reasons.add("转账用途或账户归属未确认，不计入消费和收入")
            BusinessType.REPAYMENT -> reasons.add("还款涉及本金或负债，不计入重复消费")
            BusinessType.UNKNOWN -> reasons.add("交易经济含义无法确认")
            BusinessType.REFUND -> {
                if (candidate.direction != FundsDirection.INCOMING) reasons.add("退款资金方向存在冲突")
                if (!refundLinked) reasons.add("退款尚未关联唯一原消费")
            }
            BusinessType.EXPENSE -> if (candidate.direction != FundsDirection.OUTGOING) reasons.add("消费支出方向存在冲突")
            BusinessType.INCOME -> if (candidate.direction != FundsDirection.INCOMING) reasons.add("收入资金方向存在冲突")
        }
        if (candidate.direction == FundsDirection.UNKNOWN) reasons.add("资金方向无法确认")
        return if (reasons.isEmpty()) DecisionResult(RecognitionDecision.AUTO, listOf("交易已完成，金额和业务方向一致"))
            else DecisionResult(RecognitionDecision.REVIEW, reasons.distinct())
    }

    private fun ignored(reason: String) = DecisionResult(RecognitionDecision.IGNORE, listOf(reason))
}
