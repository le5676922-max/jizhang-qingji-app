package com.example.jizhang.recognition

import com.example.jizhang.domain.BusinessType
import com.example.jizhang.domain.EntrySource
import com.example.jizhang.domain.EntryType
import com.example.jizhang.domain.LedgerEntry
import com.example.jizhang.domain.Money
import java.util.Locale

/** orderIdentity is the already-hashed OWN order, including its provider/namespace. */
data class MatcherEventSnapshot(
    val candidate: TransactionCandidate,
    val orderIdentity: String? = null,
    val linkedEntryId: Long? = null,
    val userIgnored: Boolean = false,
    val originalOrderIdentity: String? = null,
)

data class MatcherEntrySnapshot(val entry: LedgerEntry, val deleted: Boolean = false)

data class TransactionMatchResult(
    val strongEntryId: Long? = null,
    val suggestedEntryIds: List<Long> = emptyList(),
    val identityConflict: Boolean = false,
    val deletedOrderSuppressed: Boolean = false,
    val userIgnoredOrderSuppressed: Boolean = false,
    val reasons: List<String> = emptyList(),
)

/** Never merges or changes a ledger. The caller commits decisions/associations in one transaction. */
object TransactionMatcher {
    private const val SOFT_WINDOW_MS = 120_000L
    private val digest = Regex("[a-f0-9]{64}")

    fun match(
        candidate: TransactionCandidate,
        orderIdentity: String?,
        events: List<MatcherEventSnapshot>,
        entries: List<MatcherEntrySnapshot>,
        originalOrderIdentity: String? = null,
    ): TransactionMatchResult {
        val byId = entries.associateBy { it.entry.id }
        val hasOrder = orderIdentity != null && digest.matches(orderIdentity) &&
            !candidate.orderNamespace.isNullOrBlank()
        val sameOrder = if (!hasOrder) emptyList() else events.filter { event ->
            event.orderIdentity == orderIdentity && event.candidate.provider == candidate.provider &&
                event.candidate.orderNamespace == candidate.orderNamespace
        }
        val trusted = trusted(candidate)
        val reasons = mutableListOf<String>()
        var conflict = orderIdentity != null && !digest.matches(orderIdentity)
        if (conflict) reasons.add("订单身份格式不合法，需要人工核对")
        val ignored = sameOrder.any { it.userIgnored && it.candidate.hasStableIdentity &&
            sameBusinessScope(candidate, it.candidate) && !refundOriginalConflict(candidate, originalOrderIdentity, it) }
        if (ignored && trusted) return TransactionMatchResult(userIgnoredOrderSuppressed = true,
            reasons = listOf("同一稳定订单已被人工忽略，不会自动重新入账"))
        if (ignored) reasons.add("识别单号对应人工忽略记录，只提供人工核对")

        val matchedIds = sameOrder.mapNotNull { it.linkedEntryId }.distinct()
        val deleted = sameOrder.any { event -> event.linkedEntryId?.let { byId[it]?.deleted } == true &&
            sameBusinessScope(candidate, event.candidate) && !refundOriginalConflict(candidate, originalOrderIdentity, event) }
        if (deleted && trusted) {
            // A tombstone wins even after the user edited its amount: the same stable
            // order must not create a replacement ledger just because its facts changed.
            return TransactionMatchResult(deletedOrderSuppressed = true,
                reasons = listOf("同一稳定订单的账单已删除，不会自动重新创建"))
        }
        if (deleted) reasons.add("识别单号对应已删除账单，只提供人工核对")

        val strongIds = mutableSetOf<Long>()
        val suggestions = mutableSetOf<Long>()
        sameOrder.forEach { event ->
            // An original expense and its refund can share numeric IDs. A wrong own-order
            // role is a conflict; originalOrderId is deliberately never read by this matcher.
            if (knownFactsConflict(candidate, event.candidate) || refundOriginalConflict(candidate, originalOrderIdentity, event)) {
                conflict = true
                event.linkedEntryId?.takeIf { byId[it]?.deleted == false }?.let(suggestions::add)
                return@forEach
            }
            val entryId = event.linkedEntryId ?: return@forEach
            val canonical = byId[entryId]
            if (canonical == null) {
                conflict = true
                return@forEach
            }
            if (canonical.deleted) return@forEach
            suggestions.add(entryId)
            if (!canonicalAgrees(candidate, canonical.entry)) {
                conflict = true
                return@forEach
            }
            if (trusted && trusted(event.candidate) && !event.userIgnored && !canonical.entry.needsReview) {
                strongIds.add(entryId)
            }
        }
        if (matchedIds.size > 1 || strongIds.size > 1) conflict = true
        if (conflict) reasons.add("同一订单的金额、方向、业务类型或关联账单存在冲突")
        if (!conflict && strongIds.size == 1) return TransactionMatchResult(strongEntryId = strongIds.single(),
            reasons = listOf("同来源和订单命名空间的已完成交易与已确认账单一致"))

        entries.filterNot { it.deleted }.forEach { snapshot ->
            val entry = snapshot.entry
            if (!softFactsAgree(candidate, entry)) return@forEach
            // Different reliable provider orders are separate transactions even when their
            // price, merchant and time are identical. Do not manufacture a duplicate warning.
            val differentStrongOrder = hasOrder && events.any { event ->
                event.linkedEntryId == entry.id && event.candidate.provider == candidate.provider &&
                    event.candidate.orderNamespace == candidate.orderNamespace &&
                    event.orderIdentity != null && digest.matches(event.orderIdentity) &&
                    event.orderIdentity != orderIdentity && trusted(event.candidate)
            }
            if (!differentStrongOrder) suggestions.add(entry.id)
        }
        if (suggestions.isNotEmpty()) reasons.add("金额、时间或商户接近，仅建议人工关联")
        return TransactionMatchResult(suggestedEntryIds = suggestions.sorted(), identityConflict = conflict,
            reasons = reasons.distinct())
    }

    private fun trusted(candidate: TransactionCandidate): Boolean =
        candidate.provider in setOf(EntrySource.WECHAT, EntrySource.ALIPAY) &&
            candidate.evidence in setOf(EvidenceType.NOTIFICATION, EvidenceType.ACCESSIBILITY) &&
            candidate.completion == CompletionState.COMPLETED && candidate.hasStableIdentity &&
            candidate.amountCents in 1..Money.MAX_CENTS && candidate.direction != FundsDirection.UNKNOWN &&
            candidate.businessType != BusinessType.UNKNOWN &&
            candidate.amountOptions.all { it == candidate.amountCents }

    private fun knownFactsConflict(first: TransactionCandidate, second: TransactionCandidate): Boolean =
        first.amountCents != second.amountCents ||
            (first.direction != FundsDirection.UNKNOWN && second.direction != FundsDirection.UNKNOWN &&
                first.direction != second.direction) ||
            (first.businessType != BusinessType.UNKNOWN && second.businessType != BusinessType.UNKNOWN &&
                first.businessType != second.businessType)

    private fun sameBusinessScope(first: TransactionCandidate, second: TransactionCandidate): Boolean =
        (first.businessType == BusinessType.UNKNOWN || second.businessType == BusinessType.UNKNOWN ||
            first.businessType == second.businessType) &&
            (first.direction == FundsDirection.UNKNOWN || second.direction == FundsDirection.UNKNOWN ||
                first.direction == second.direction)

    private fun refundOriginalConflict(candidate: TransactionCandidate, originalIdentity: String?, event: MatcherEventSnapshot): Boolean =
        candidate.businessType == BusinessType.REFUND && originalIdentity != null && event.originalOrderIdentity != null &&
            (originalIdentity != event.originalOrderIdentity || candidate.originalOrderNamespace != event.candidate.originalOrderNamespace)

    private fun canonicalAgrees(candidate: TransactionCandidate, entry: LedgerEntry): Boolean =
        candidate.amountCents == entry.amountCents && candidate.businessType == entry.businessType &&
            (entry.source == candidate.provider || entry.source == EntrySource.MANUAL) &&
            candidate.direction == direction(entry)

    private fun softFactsAgree(candidate: TransactionCandidate, entry: LedgerEntry): Boolean {
        if (entry.id <= 0 || candidate.completion != CompletionState.COMPLETED ||
            candidate.amountCents != entry.amountCents || candidate.direction != direction(entry) ||
            (entry.source != candidate.provider && entry.source != EntrySource.MANUAL) ||
            (candidate.businessType != BusinessType.UNKNOWN && entry.businessType != BusinessType.UNKNOWN &&
                candidate.businessType != entry.businessType) || candidate.occurredAt <= 0 || entry.occurredAt <= 0) return false
        // Avoid abs(Long.MIN_VALUE) wraparound on malformed imported timestamps.
        val early = minOf(candidate.occurredAt, entry.occurredAt)
        val late = maxOf(candidate.occurredAt, entry.occurredAt)
        if (late - early > SOFT_WINDOW_MS) return false
        val newMerchant = merchantKey(candidate.merchant)
        val oldMerchant = merchantKey(entry.merchant)
        return newMerchant.isEmpty() || oldMerchant.isEmpty() || newMerchant == oldMerchant
    }

    private fun direction(entry: LedgerEntry) = if (entry.type == EntryType.INCOME)
        FundsDirection.INCOMING else FundsDirection.OUTGOING

    private fun merchantKey(value: String): String = value.replace('\u00a0', ' ').replace('\u3000', ' ')
        .trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
}
