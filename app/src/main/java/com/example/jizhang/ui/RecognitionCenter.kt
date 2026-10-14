package com.example.jizhang.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.jizhang.data.RecognitionEvent
import com.example.jizhang.domain.*
import com.example.jizhang.recognition.CompletionState
import com.example.jizhang.recognition.EvidenceType
import com.example.jizhang.recognition.FundsDirection
import com.example.jizhang.recognition.RecognitionDecision
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Pure UI: the parent owns transactions, errors, durable ignoring, and association history. */
@Composable
fun RecognitionCenter(
    events: List<RecognitionEvent>,
    entries: List<LedgerEntry>,
    saving: Boolean,
    onConfirm: (RecognitionEvent, LedgerEntry, Boolean, Boolean, Boolean) -> Unit,
    onLink: (Long, Long) -> Unit,
    onIgnore: (Long) -> Unit,
    onUndoIgnore: (Long) -> Unit,
    onUnlink: (Long) -> Unit,
    onEditEntry: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val editing = events.firstOrNull { it.id == editingId }
    val filtered = events.filter { event -> when (filter) {
        0 -> event.decision == RecognitionDecision.REVIEW
        2 -> event.decision == RecognitionDecision.IGNORE
        else -> true
    } }.sortedByDescending { it.candidate.observedAt }

    Dialog(onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 18.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("智能识别中心", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("待确认来源不计入账本；一笔账可关联多条凭据", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("关闭") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("待确认", "全部", "已忽略").forEachIndexed { index, label ->
                        FilterChip(selected = filter == index, onClick = { filter = index },
                            modifier = Modifier.testTag("recognition-filter-$index"),
                            label = { Text(if (index == 0) "$label ${events.count { it.decision == RecognitionDecision.REVIEW }}" else label) })
                    }
                }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                LazyColumn(Modifier.weight(1f).testTag("recognition-events"), verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp)) {
                    if (filtered.isEmpty()) item {
                        Text(if (filter == 0) "没有待确认交易" else "暂无识别记录",
                            modifier = Modifier.padding(vertical = 28.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                    items(filtered, key = { it.id }) { event ->
                        RecognitionEventCard(event, events, entries, saving,
                            onReview = { editingId = event.id }, onLink = onLink,
                            onIgnore = onIgnore, onUndoIgnore = onUndoIgnore,
                            onUnlink = onUnlink, onEditEntry = { id -> onEditEntry(id); onDismiss() })
                    }
                }
            }
        }
    }
    if (editing != null) {
        RecognitionCandidateEditor(editing, entries, saving, onConfirm, onLink, onIgnore,
            onDismiss = { editingId = null })
    }
}

@Composable
private fun RecognitionEventCard(
    event: RecognitionEvent,
    events: List<RecognitionEvent>,
    entries: List<LedgerEntry>,
    saving: Boolean,
    onReview: () -> Unit,
    onLink: (Long, Long) -> Unit,
    onIgnore: (Long) -> Unit,
    onUndoIgnore: (Long) -> Unit,
    onUnlink: (Long) -> Unit,
    onEditEntry: (Long) -> Unit,
) {
    val candidate = event.candidate
    val linked = entries.firstOrNull { it.id == event.linkedEntryId }
    val suggested = entries.firstOrNull { it.id == event.suggestedEntryId }
    var expanded by rememberSaveable(event.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("recognition-event-${event.id}"), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (candidate.amountOptions.distinct().size > 1) "金额待选择" else "¥ ${Money.format(candidate.amountCents)}", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(when (event.decision) {
                    RecognitionDecision.AUTO -> if (event.linkedEntryDeleted) "账单已删除" else "已处理"
                    RecognitionDecision.REVIEW -> "待确认"
                    RecognitionDecision.IGNORE -> "已忽略"
                }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
            Text("${recognitionBusinessLabel(candidate.businessType)} · ${recognitionDirectionLabel(candidate.direction)}" +
                candidate.merchant.takeIf { it.isNotBlank() }?.let { " · ${redactRecognitionText(it)}" }.orEmpty())
            if (candidate.amountOptions.distinct().size > 1) Text(candidate.amountOptions.distinct()
                .joinToString(" / ") { "¥${Money.format(it)}" }, style = MaterialTheme.typography.bodySmall)
            Text("${recognitionSourceLabel(candidate.provider)} · ${recognitionEvidenceLabel(candidate.evidence)} · ${recognitionDate(candidate.observedAt)}",
                style = MaterialTheme.typography.bodySmall)
            event.reasons.take(2).forEach { Text(redactRecognitionText(it), style = MaterialTheme.typography.bodySmall) }
            if (event.linkedEntryId != null) {
                Text(if (event.linkedEntryDeleted) "关联账单已删除，凭据仍保留" else
                    "关联账单 #${event.linkedEntryId} · ${events.count { it.linkedEntryId == event.linkedEntryId }} 条来源",
                    style = MaterialTheme.typography.bodySmall)
                if (linked?.editedByUser == true) Text("账单已经人工修改，新凭据不会覆盖修改", style = MaterialTheme.typography.bodySmall)
            }
            if (suggested != null && event.linkedEntryId == null) {
                HorizontalDivider()
                Text("可能对应：¥${Money.format(suggested.amountCents)} · ${suggested.merchant.ifBlank { suggested.category }} · ${recognitionDate(suggested.occurredAt)}",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onLink(event.id, suggested.id) },
                    enabled = !saving && candidate.completion == CompletionState.COMPLETED &&
                        candidate.amountOptions.distinct().size <= 1 && candidate.businessType != BusinessType.UNKNOWN &&
                        candidate.direction !in setOf(FundsDirection.UNKNOWN, FundsDirection.INTERNAL)) { Text("关联已有账单") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (event.decision == RecognitionDecision.REVIEW) {
                    Button(onClick = onReview, enabled = !saving) { Text("核对并确认") }
                    TextButton(onClick = { onIgnore(event.id) }, enabled = !saving) { Text("忽略") }
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起详情" else "查看依据") }
            }
            if (event.userIgnored || event.association in setOf("ORDER", "MANUAL")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (event.userIgnored) TextButton(onClick = { onUndoIgnore(event.id) }, enabled = !saving) { Text("恢复待确认") }
                    if (event.association in setOf("ORDER", "MANUAL")) TextButton(onClick = { onUnlink(event.id) }, enabled = !saving) { Text("撤销关联") }
                }
            }
            if (expanded) {
                HorizontalDivider()
                Text("规则：${candidate.ruleVersion.take(80)}", style = MaterialTheme.typography.bodySmall)
                Text("交易时间：${recognitionDate(candidate.occurredAt)}", style = MaterialTheme.typography.bodySmall)
                Text("状态：${recognitionCompletionLabel(candidate.completion)}", style = MaterialTheme.typography.bodySmall)
                event.reasons.drop(2).forEach { Text(redactRecognitionText(it), style = MaterialTheme.typography.bodySmall) }
                val relatedSources = events.filter { event.linkedEntryId != null && it.linkedEntryId == event.linkedEntryId }
                relatedSources.forEach { source -> Text(
                    "${recognitionSourceLabel(source.candidate.provider)} · ${recognitionEvidenceLabel(source.candidate.evidence)} · ${recognitionDate(source.candidate.observedAt)}",
                    style = MaterialTheme.typography.bodySmall) }
                if (linked != null && !event.linkedEntryDeleted) TextButton(onClick = { onEditEntry(linked.id) }, enabled = !saving) {
                    Text("修改或删除这笔账单")
                }
                if (event.association in setOf("ORDER", "MANUAL")) Text("撤销只解除凭据关联，原账单保留。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RecognitionCandidateEditor(
    event: RecognitionEvent,
    entries: List<LedgerEntry>,
    saving: Boolean,
    onConfirm: (RecognitionEvent, LedgerEntry, Boolean, Boolean, Boolean) -> Unit,
    onLink: (Long, Long) -> Unit,
    onIgnore: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val candidate = event.candidate
    val context = LocalContext.current
    var amount by rememberSaveable(event.id) { mutableStateOf(if (candidate.amountOptions.distinct().size > 1) "" else Money.format(candidate.amountCents)) }
    var business by rememberSaveable(event.id) { mutableStateOf(candidate.businessType) }
    var type by rememberSaveable(event.id) { mutableStateOf<EntryType?>(when (candidate.direction) {
        FundsDirection.OUTGOING -> EntryType.EXPENSE
        FundsDirection.INCOMING -> EntryType.INCOME
        else -> null
    }) }
    var merchant by rememberSaveable(event.id) { mutableStateOf(candidate.merchant) }
    var category by rememberSaveable(event.id) { mutableStateOf(candidate.categorySuggestion) }
    var occurredAt by rememberSaveable(event.id) { mutableLongStateOf(candidate.occurredAt) }
    var relatedId by rememberSaveable(event.id) { mutableStateOf(event.relatedEntryId) }
    var rememberCategory by rememberSaveable(event.id) { mutableStateOf(false) }
    var recordSeparately by rememberSaveable(event.id) { mutableStateOf(false) }
    var confirmCompleted by rememberSaveable(event.id) { mutableStateOf(false) }
    var refundSearch by rememberSaveable(event.id) { mutableStateOf("") }
    var refundShown by rememberSaveable(event.id) { mutableIntStateOf(30) }
    val cents = Money.parseCents(amount)
    val suggested = entries.firstOrNull { it.id == event.suggestedEntryId }
    val duplicateRisk = event.suggestedEntryId != null || event.reasons.any { it.contains("重复") || it.contains("冲突") || it.contains("关联") && !it.contains("退款") }
    val remainingByEntry = remember(entries) { refundRemainingByEntry(entries) }
    val refundOptions = entries.filter { original ->
        !original.needsReview && original.businessType == BusinessType.EXPENSE && original.type == EntryType.EXPENSE &&
            original.id > 0 && (remainingByEntry[original.id] ?: 0L) >= (cents ?: 1L)
    }.sortedByDescending { it.occurredAt }
    val original = refundOptions.firstOrNull { it.id == relatedId }
    val completed = candidate.completion == CompletionState.COMPLETED ||
        (candidate.completion == CompletionState.UNCONFIRMED && confirmCompleted)
    val correctDirection = when (business) {
        BusinessType.EXPENSE -> type == EntryType.EXPENSE
        BusinessType.INCOME, BusinessType.REFUND -> type == EntryType.INCOME
        else -> type != null
    }
    val resolvedCategory = if (business == BusinessType.REFUND) original?.category.orEmpty() else category.trim()
    val canSave = !saving && cents != null && business != BusinessType.UNKNOWN && correctDirection && completed &&
        resolvedCategory.isNotEmpty() && (business != BusinessType.REFUND || original != null) &&
        (!duplicateRisk || recordSeparately)
    LaunchedEffect(event.decision, event.linkedEntryId, saving) {
        if (!saving && event.decision == RecognitionDecision.AUTO && event.linkedEntryId != null) onDismiss()
    }

    Dialog(onDismissRequest = { if (!saving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()
                .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("核对交易", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("返回") }
                }
                Text("${recognitionSourceLabel(candidate.provider)} · ${recognitionEvidenceLabel(candidate.evidence)}",
                    style = MaterialTheme.typography.bodySmall)
                event.reasons.forEach { Text(redactRecognitionText(it), style = MaterialTheme.typography.bodySmall) }
                Text("业务类型", style = MaterialTheme.typography.titleSmall)
                BusinessType.entries.toList().chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { value -> FilterChip(selected = business == value, enabled = !saving,
                            modifier = Modifier.weight(1f).testTag("recognition-business-$value"), onClick = {
                                business = value
                                category = when (value) {
                                    BusinessType.TRANSFER -> "转账"
                                    BusinessType.REPAYMENT -> "还款"
                                    BusinessType.REFUND -> original?.category ?: "退款"
                                    BusinessType.INCOME -> "收款"
                                    BusinessType.EXPENSE -> "其他"
                                    BusinessType.UNKNOWN -> "其他"
                                }
                                rememberCategory = false
                            }, label = { Text(recognitionBusinessLabel(value), fontSize = 12.sp) }) }
                    }
                }
                if (business == BusinessType.UNKNOWN) Text("请先确认交易的经济含义。", color = MaterialTheme.colorScheme.error)
                if (business in setOf(BusinessType.TRANSFER, BusinessType.REPAYMENT)) Text("这类资金往来不计入消费或新增收入。", style = MaterialTheme.typography.bodySmall)
                Text("资金方向", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(selected = type == EntryType.EXPENSE, enabled = !saving, onClick = { type = EntryType.EXPENSE },
                        modifier = Modifier.testTag("recognition-direction-EXPENSE"), label = { Text("资金转出") })
                    FilterChip(selected = type == EntryType.INCOME, enabled = !saving, onClick = { type = EntryType.INCOME },
                        modifier = Modifier.testTag("recognition-direction-INCOME"), label = { Text("资金转入") })
                }
                if (!correctDirection) Text("资金方向与所选业务类型不一致，请核对。", color = MaterialTheme.colorScheme.error)
                OutlinedTextField(amount, onValueChange = { if (it.length <= 32) amount = it }, label = { Text("实际金额（元）") },
                    modifier = Modifier.fillMaxWidth().testTag("recognition-amount"), prefix = { Text("¥ ") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, enabled = !saving,
                    isError = cents == null, supportingText = { if (cents == null) Text("请输入正数，最多两位小数") })
                if (candidate.amountOptions.distinct().size > 1) {
                    Text("识别到多个金额，请选择实际交易金额。", style = MaterialTheme.typography.bodySmall)
                    candidate.amountOptions.distinct().filter { it in 1..Money.MAX_CENTS }.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { option -> FilterChip(selected = cents == option, enabled = !saving,
                                modifier = Modifier.testTag("recognition-amount-option-$option"),
                                onClick = { amount = Money.format(option) }, label = { Text("¥${Money.format(option)}") }) }
                        }
                    }
                }
                if (business == BusinessType.REFUND) {
                    Text("关联原消费", style = MaterialTheme.typography.titleSmall)
                    Text("只显示已确认且可退款金额足够的消费；退款抵扣该消费分类，不计入收入。", style = MaterialTheme.typography.bodySmall)
                    if (refundOptions.isEmpty()) Text("没有可关联的原消费，请先核对原账单与退款金额。", color = MaterialTheme.colorScheme.error)
                    OutlinedTextField(refundSearch, onValueChange = { refundSearch = it.take(80); refundShown = 30 },
                        label = { Text("查找原消费商户 / 分类 / 日期") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving)
                    val visibleOptions = refundOptions.filter { refundSearch.isBlank() ||
                        "${it.merchant} ${it.category} ${recognitionDate(it.occurredAt, "yyyy-MM-dd")}".contains(refundSearch.trim(), ignoreCase = true) }
                    visibleOptions.take(refundShown).forEach { option ->
                        FilterChip(selected = relatedId == option.id, enabled = !saving,
                            modifier = Modifier.testTag("recognition-refund-original-${option.id}"),
                            onClick = { relatedId = option.id; category = option.category },
                            label = { Text("${option.merchant.ifBlank { option.category }} · ${recognitionDate(option.occurredAt)} · 可退 ¥${Money.format(remainingByEntry[option.id] ?: 0L)}") })
                    }
                    if (visibleOptions.size > refundShown) TextButton(onClick = { refundShown += 30 }, enabled = !saving) { Text("显示更多原消费") }
                }
                OutlinedTextField(merchant, onValueChange = { if (it.length <= 120) merchant = it },
                    label = { Text("商户 / 对方") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving)
                OutlinedTextField(if (business == BusinessType.REFUND) original?.category ?: "退款" else category,
                    onValueChange = { if (it.length <= 80) category = it }, label = { Text("分类") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving && business != BusinessType.REFUND)
                if (business in setOf(BusinessType.EXPENSE, BusinessType.INCOME)) {
                    val categories = if (business == BusinessType.EXPENSE) LedgerCategories.expense else LedgerCategories.income
                    categories.chunked(4).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { value -> FilterChip(selected = category == value, enabled = !saving,
                            onClick = { category = value }, label = { Text(value, fontSize = 11.sp) }) }
                    } }
                }
                Row {
                    TextButton(onClick = {
                        val calendar = Calendar.getInstance().apply { timeInMillis = occurredAt }
                        DatePickerDialog(context, { _, year, month, day ->
                            calendar.set(Calendar.YEAR, year); calendar.set(Calendar.MONTH, month); calendar.set(Calendar.DAY_OF_MONTH, day)
                            occurredAt = calendar.timeInMillis
                        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
                    }, enabled = !saving) { Text(recognitionDate(occurredAt, "yyyy-MM-dd")) }
                    TextButton(onClick = {
                        val calendar = Calendar.getInstance().apply { timeInMillis = occurredAt }
                        TimePickerDialog(context, { _, hour, minute ->
                            calendar.set(Calendar.HOUR_OF_DAY, hour); calendar.set(Calendar.MINUTE, minute); calendar.set(Calendar.SECOND, 0)
                            occurredAt = calendar.timeInMillis
                        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
                    }, enabled = !saving) { Text(recognitionDate(occurredAt, "HH:mm")) }
                }
                RecognitionCheck("记住这位商户的收入 / 消费分类", rememberCategory,
                    enabled = !saving && merchant.isNotBlank() && business in setOf(BusinessType.EXPENSE, BusinessType.INCOME)) {
                    rememberCategory = it
                }
                if (candidate.completion == CompletionState.UNCONFIRMED) RecognitionCheck("我已核对交易确已完成", confirmCompleted, !saving) {
                    confirmCompleted = it
                }
                if (candidate.completion in setOf(CompletionState.PENDING, CompletionState.FAILED, CompletionState.CANCELLED)) {
                    Text("该凭据尚未成功完成，不能直接入账。请等待或导入成功凭据。", color = MaterialTheme.colorScheme.error)
                }
                if (suggested != null) {
                    HorizontalDivider()
                    Text("已有账单：¥${Money.format(suggested.amountCents)} · ${suggested.merchant.ifBlank { suggested.category }} · ${recognitionDate(suggested.occurredAt)}")
                    OutlinedButton(onClick = { onLink(event.id, suggested.id) }, enabled = !saving && completed && cents != null &&
                        business != BusinessType.UNKNOWN && correctDirection) { Text("确认是同一笔，关联已有账单") }
                }
                if (duplicateRisk) RecognitionCheck("我已核对账本，确认这是一笔独立交易", recordSeparately, !saving) { recordSeparately = it }
                Button(onClick = {
                    val validAmount = Money.parseCents(amount) ?: return@Button
                    val validType = type ?: return@Button
                    onConfirm(event, LedgerEntry(amountCents = validAmount, type = validType,
                        businessType = business, category = resolvedCategory,
                        merchant = merchant.trim(), source = candidate.provider, occurredAt = occurredAt,
                        relatedEntryId = if (business == BusinessType.REFUND) original?.id else null,
                        editedByUser = true), rememberCategory, recordSeparately, confirmCompleted)
                }, enabled = canSave, modifier = Modifier.fillMaxWidth().testTag("recognition-confirm")) {
                    Text(if (saving) "保存中…" else "确认并保存账单")
                }
                TextButton(onClick = { onIgnore(event.id); onDismiss() }, enabled = !saving,
                    modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("忽略这条识别来源") }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun RecognitionCheck(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Checkbox,
        onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun refundRemainingByEntry(entries: List<LedgerEntry>): Map<Long, Long> {
    val refunded = entries.filter { !it.needsReview && it.businessType == BusinessType.REFUND &&
        it.type == EntryType.INCOME && it.relatedEntryId != null }.groupBy { it.relatedEntryId }
        .mapValues { (_, refunds) -> refunds.sumOf { it.amountCents } }
    return entries.filter { !it.needsReview && it.businessType == BusinessType.EXPENSE && it.type == EntryType.EXPENSE }
        .associate { it.id to (it.amountCents - (refunded[it.id] ?: 0L)).coerceAtLeast(0) }
}

private fun recognitionBusinessLabel(value: BusinessType): String = when (value) {
    BusinessType.EXPENSE -> "消费支出"
    BusinessType.INCOME -> "收入"
    BusinessType.TRANSFER -> "转账"
    BusinessType.REFUND -> "退款"
    BusinessType.REPAYMENT -> "还款"
    BusinessType.UNKNOWN -> "未知"
}
private fun recognitionDirectionLabel(value: FundsDirection): String = when (value) {
    FundsDirection.OUTGOING -> "资金转出"
    FundsDirection.INCOMING -> "资金转入"
    FundsDirection.INTERNAL -> "内部转移"
    FundsDirection.UNKNOWN -> "方向待确认"
}
private fun recognitionSourceLabel(value: EntrySource): String = when (value) {
    EntrySource.WECHAT -> "微信"
    EntrySource.ALIPAY -> "支付宝"
    EntrySource.MANUAL -> "手动"
}
private fun recognitionEvidenceLabel(value: EvidenceType): String = when (value) {
    EvidenceType.NOTIFICATION -> "付款通知"
    EvidenceType.ACCESSIBILITY -> "页面文字"
    EvidenceType.OCR -> "本地 OCR"
    EvidenceType.IMPORTED_IMAGE -> "导入截图"
    EvidenceType.SHARED_IMAGE -> "分享截图"
    EvidenceType.LEGACY -> "旧版记录"
}
private fun recognitionCompletionLabel(value: CompletionState): String = when (value) {
    CompletionState.COMPLETED -> "已完成"
    CompletionState.UNCONFIRMED -> "尚未确认完成"
    CompletionState.PENDING -> "等待完成"
    CompletionState.FAILED -> "失败"
    CompletionState.CANCELLED -> "已取消"
}
private fun recognitionDate(time: Long, pattern: String = "MM-dd HH:mm"): String =
    SimpleDateFormat(pattern, Locale.CHINA).format(Date(time))

private fun redactRecognitionText(value: String): String = value.take(240)
    .replace(Regex("(?:密码|验证码|银行卡号)[：: ]*[^，。；;\\n]*"), "敏感信息已隐藏")
    .replace(Regex("[0-9]{7,}"), "••••")
