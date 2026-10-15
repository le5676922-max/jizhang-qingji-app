package com.example.jizhang.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.jizhang.agent.AgentReply
import com.example.jizhang.agent.deepseek.DeepSeekModels
import com.example.jizhang.agent.deepseek.DeepSeekSettingsStore
import com.example.jizhang.domain.BusinessType
import com.example.jizhang.domain.EntryType
import com.example.jizhang.domain.LedgerEntry
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** The UI never reads an existing API key. Tests inject this boundary without secrets or networking. */
internal data class AgentUiConfiguration(val enabled: Boolean, val model: String, val hasKey: Boolean)

internal interface AgentSettingsAccess {
    fun configuration(): AgentUiConfiguration
    fun save(apiKey: String?, model: String, enabled: Boolean)
    fun clear()
}

private data class AgentTurn(
    val id: Long,
    val prompt: String,
    val reply: AgentReply? = null,
    val failure: String? = null,
    val draftOpened: Boolean = false,
)

@Composable
fun AgentPanel(
    writingBlocked: Boolean = false,
    onAsk: suspend (prompt: String, allowSummarySharing: Boolean) -> AgentReply,
    onDraft: (LedgerEntry) -> Unit,
    onReview: () -> Unit,
    onConnection: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val access = remember(context) {
        // Defer preference/security-store initialization to the IO operation below as well.
        val store by lazy { DeepSeekSettingsStore(context) }
        object : AgentSettingsAccess {
            override fun configuration(): AgentUiConfiguration {
                val current = store.configuration()
                return AgentUiConfiguration(current.enabled, current.model, current.hasKey)
            }
            override fun save(apiKey: String?, model: String, enabled: Boolean) = store.save(apiKey, model, enabled)
            override fun clear() = store.clear()
        }
    }
    AgentPanelContent(access, writingBlocked, onAsk, onDraft, onReview, onConnection)
}

/** Conversation, drafts, consent and key input intentionally do not survive navigation or recreation. */
@Composable
internal fun AgentPanelContent(
    settingsAccess: AgentSettingsAccess,
    writingBlocked: Boolean = false,
    onAsk: suspend (prompt: String, allowSummarySharing: Boolean) -> AgentReply,
    onDraft: (LedgerEntry) -> Unit,
    onReview: () -> Unit,
    onConnection: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val turns = remember { mutableStateListOf<AgentTurn>() }
    var configuration by remember(settingsAccess) { mutableStateOf<AgentUiConfiguration?>(null) }
    var configurationError by remember(settingsAccess) { mutableStateOf<String?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var allowSummarySharing by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf<Job?>(null) }
    var requestEpoch by remember { mutableLongStateOf(0) }
    var nextTurnId by remember { mutableLongStateOf(0) }
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(settingsAccess) {
        try {
            configuration = withContext(Dispatchers.IO) { settingsAccess.configuration() }
            configurationError = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { configurationError = "无法读取 DeepSeek 设置，请打开设置后重试。" }
    }

    DisposableEffect(settingsAccess) {
        onDispose {
            requestEpoch++
            request?.cancel()
            request = null
        }
    }

    fun send() {
        val submitted = prompt.trim()
        if (working || settingsOpen || submitted.isEmpty() || configuration == null || configurationError != null) return
        val shareForThisRequest = allowSummarySharing
        // Every request needs its own affirmative sharing choice.
        allowSummarySharing = false
        prompt = ""
        val turnId = ++nextTurnId
        val epoch = ++requestEpoch
        if (turns.size >= 12) turns.removeAt(0)
        turns += AgentTurn(turnId, submitted)
        working = true
        request = scope.launch {
            try {
                val reply = onAsk(submitted, shareForThisRequest)
                coroutineContext.ensureActive()
                if (epoch == requestEpoch) {
                    val index = turns.indexOfFirst { it.id == turnId }
                    if (index >= 0) turns[index] = turns[index].copy(reply = reply)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (epoch == requestEpoch) {
                    val index = turns.indexOfFirst { it.id == turnId }
                    if (index >= 0) turns[index] = turns[index].copy(failure = "请求未完成，请检查网络或 DeepSeek 设置后重试。")
                }
            } finally {
                if (epoch == requestEpoch) { working = false; request = null }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("记账助手", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("查询本地账本、整理记账草稿，或向 DeepSeek 提问。草稿需核对后保存。", style = MaterialTheme.typography.bodyMedium)
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val current = configuration
                Text(when {
                    configurationError != null -> "DeepSeek 设置读取失败"
                    current == null -> "DeepSeek 设置加载中"
                    current.enabled && current.hasKey -> "DeepSeek 已启用 · ${current.model}"
                    current.hasKey -> "已保存 API Key · DeepSeek 未启用"
                    else -> "尚未配置 DeepSeek"
                }, Modifier.testTag("agent-configuration"))
                configurationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(when {
                    current == null || configurationError != null -> "设置状态确认后才能发送；加载或读取失败时不会提交输入。"
                    current.enabled && current.hasKey -> "发送本次输入，由你的 DeepSeek 账户计费。默认不附账本；聊天仅保留在本次页面。"
                    else -> "本地查询不联网。自然语言记账需先配置并启用 DeepSeek；聊天仅保留在本次页面。"
                },
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { settingsOpen = true }, enabled = !working && (current != null || configurationError != null),
                    modifier = Modifier.testTag("agent-settings")) { Text("DeepSeek 设置") }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("本月收支", "昨天午餐28.50元", "为什么还要确认", "检查连接").forEachIndexed { index, text ->
                SuggestionChip(onClick = { prompt = text }, enabled = !working,
                    label = { Text(text) }, modifier = Modifier.testTag("agent-example-$index"))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onReview, enabled = !working) { Text("打开待确认账单") }
            TextButton(onClick = onConnection, enabled = !working) { Text("查看识别连接") }
        }
        if (writingBlocked) Text("账本暂时不可写，可以查询与生成草稿，恢复后再核对记账。", color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("agent-writing-blocked"))
        turns.forEach { turn ->
            key(turn.id) {
                OutlinedCard(Modifier.fillMaxWidth().testTag("agent-turn-${turn.id}")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("你：${turn.prompt}", fontWeight = FontWeight.Medium)
                        turn.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        turn.reply?.let { reply ->
                            Text(if (reply.usedCloud) "DeepSeek 建议 · 数字以本地核算为准" else "本地助手", style = MaterialTheme.typography.labelMedium)
                            Text(reply.message.take(6000))
                            if (reply.toolSteps.isNotEmpty()) Text(reply.toolSteps.take(8).joinToString("\n") { "· ${it.take(160)}" },
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("agent-tools-${turn.id}"))
                            reply.localReport?.takeIf { it.isNotBlank() }?.let {
                                Text(it.take(4000), Modifier.testTag("agent-report-${turn.id}"))
                            }
                            reply.draft?.let { draft ->
                                HorizontalDivider()
                                Text("待核对草稿 · 尚未入账", fontWeight = FontWeight.Medium)
                                Text("${draftType(draft)} ¥${BigDecimal.valueOf(draft.amountCents, 2).toPlainString()} · ${draft.category}")
                                Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(draft.occurredAt)))
                                if (draft.merchant.isNotBlank()) Text("对象：${draft.merchant.take(200)}")
                                if (draft.note.isNotBlank()) Text("备注：${draft.note.take(500)}")
                                Button(onClick = {
                                    if (!writingBlocked && !turn.draftOpened) {
                                        val index = turns.indexOfFirst { it.id == turn.id }
                                        if (index >= 0) {
                                            turns[index] = turns[index].copy(draftOpened = true)
                                            onDraft(draft)
                                        }
                                    }
                                }, enabled = !writingBlocked && !turn.draftOpened && !working,
                                    modifier = Modifier.testTag("agent-draft-${turn.id}")) {
                                    Text(if (turn.draftOpened) "已打开核对" else "核对并记账")
                                }
                            }
                        }
                        if (turn.reply == null && turn.failure == null) Text("正在处理…")
                    }
                }
            }
        }
        OutlinedTextField(value = prompt, onValueChange = { prompt = it.take(1000) }, enabled = !working,
            label = { Text("告诉助手要做什么") }, minLines = 2, maxLines = 6,
            supportingText = { Text("${prompt.length}/1000 · 仅发送本次输入，不附聊天历史") },
            modifier = Modifier.fillMaxWidth().testTag("agent-input"))
        Row(Modifier.fillMaxWidth()) {
            Checkbox(checked = allowSummarySharing, onCheckedChange = { allowSummarySharing = it }, enabled = !working,
                modifier = Modifier.testTag("agent-share-summary"))
            Text("仅本次允许把收支汇总、分类及待确认数量和原因计数发给 DeepSeek。不包含逐笔账单、截图或聊天记录。",
                modifier = Modifier.weight(1f).padding(top = 10.dp), style = MaterialTheme.typography.bodySmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = ::send, enabled = !working && prompt.isNotBlank() && configuration != null && configurationError == null,
                modifier = Modifier.testTag("agent-send")) {
                Text(when {
                    configurationError != null -> "设置读取失败"
                    configuration == null -> "设置加载中"
                    configuration?.enabled == true && configuration?.hasKey == true -> "发送给 DeepSeek"
                    else -> "本地查询"
                })
            }
            if (working) OutlinedButton(onClick = {
                requestEpoch++
                request?.cancel(); request = null; working = false
                val index = turns.indexOfLast { it.reply == null && it.failure == null }
                if (index >= 0) turns[index] = turns[index].copy(failure = "已取消本次请求。")
            }, modifier = Modifier.testTag("agent-cancel")) { Text("取消请求") }
        }
    }
    if (settingsOpen) AgentSettingsDialog(settingsAccess, configuration,
        onSaved = { configuration = it; configurationError = null }, onDismiss = { settingsOpen = false })
}

@Composable
internal fun AgentSettingsDialog(
    settingsAccess: AgentSettingsAccess,
    initial: AgentUiConfiguration?,
    onSaved: (AgentUiConfiguration) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(initial?.model ?: DeepSeekModels.DEFAULT) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: false) }
    var hasKey by remember { mutableStateOf(initial?.hasKey ?: false) }
    var configurationKnown by remember { mutableStateOf(initial != null) }
    var modelExpanded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    fun dismiss() { if (!saving) { apiKey = ""; onDismiss() } }
    Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(max = 640.dp),
            shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("DeepSeek 设置", style = MaterialTheme.typography.titleLarge)
                Text("请求会发送你的本次输入，API 费用由你的 DeepSeek 账户承担。收支汇总、分类及待确认数量和原因计数仅在每次单独勾选后发送。",
                    style = MaterialTheme.typography.bodySmall)
                Text("API Key 由 Android 系统密钥库加密保存，不写入聊天，也不包含在账本备份中。",
                    style = MaterialTheme.typography.bodySmall)
                Text("本地 OCR 使用的 ML Kit 可能联网发送 SDK 性能与设备诊断信息，关闭 DeepSeek 不会关闭该通信。",
                    style = MaterialTheme.typography.bodySmall)
                Text(when {
                    !configurationKnown -> "设置状态未知，无法确认是否已有 API Key。启用前请填写新 Key 覆盖保存，或清除旧设置；原 Key 不会显示。"
                    hasKey -> "API Key 已保存（不显示原文）"
                    else -> "尚未保存 API Key"
                }, Modifier.testTag("agent-key-status"))
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it.take(512); error = null }, enabled = !saving,
                    label = { Text(when {
                        !configurationKnown -> "新 API Key（覆盖保存）"
                        hasKey -> "新 API Key（留空保留）"
                        else -> "DeepSeek API Key"
                    }) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("agent-api-key"))
                Box {
                    OutlinedButton(onClick = { modelExpanded = true }, enabled = !saving, modifier = Modifier.testTag("agent-model")) { Text("模型：$model") }
                    DropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                        DeepSeekModels.SUPPORTED.forEach { supported ->
                            DropdownMenuItem(text = { Text(supported) }, onClick = { model = supported; modelExpanded = false })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(checked = enabled, onCheckedChange = { enabled = it }, enabled = !saving,
                        modifier = Modifier.testTag("agent-enable"))
                    Text("启用 DeepSeek", Modifier.weight(1f).padding(top = 12.dp))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("agent-settings-error")) }
                notice?.let { Text(it, Modifier.testTag("agent-settings-notice")) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = {
                        if (enabled && (!configurationKnown || !hasKey) && apiKey.isBlank()) {
                            error = "启用前请填写 API Key。"
                        } else if (!saving) {
                            val submittedKey = apiKey.trim().takeIf { it.isNotEmpty() }
                            apiKey = ""; error = null; saving = true
                            scope.launch {
                                try {
                                    val updated = withContext(Dispatchers.IO) {
                                        settingsAccess.save(submittedKey, model, enabled)
                                        settingsAccess.configuration()
                                    }
                                    coroutineContext.ensureActive()
                                    onSaved(updated); onDismiss()
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { error = "保存失败，请检查系统安全存储后重试。" }
                                finally { saving = false }
                            }
                        }
                    }, enabled = !saving, modifier = Modifier.testTag("agent-settings-save")) { Text(if (saving) "处理中…" else "保存设置") }
                    OutlinedButton(onClick = ::dismiss, enabled = !saving) { Text("取消") }
                    TextButton(onClick = {
                        if (!saving) {
                            apiKey = ""; error = null; notice = null; saving = true
                            scope.launch {
                                try {
                                    val updated = withContext(Dispatchers.IO) { settingsAccess.clear(); settingsAccess.configuration() }
                                    coroutineContext.ensureActive()
                                    configurationKnown = true; hasKey = updated.hasKey; enabled = updated.enabled; model = updated.model
                                    onSaved(updated); notice = "已清除 API Key 并关闭 DeepSeek。"
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { error = "清除失败，请检查系统安全存储后重试。" }
                                finally { saving = false }
                            }
                        }
                    }, enabled = (hasKey || !configurationKnown) && !saving, modifier = Modifier.testTag("agent-settings-clear")) { Text("清除 API Key") }
                }
            }
        }
    }
}

private fun draftType(draft: LedgerEntry): String = when (draft.businessType) {
    BusinessType.EXPENSE -> "支出"
    BusinessType.INCOME -> "收入"
    BusinessType.TRANSFER -> "转账"
    BusinessType.REFUND -> "退款"
    BusinessType.REPAYMENT -> "还款"
    BusinessType.UNKNOWN -> if (draft.type == EntryType.INCOME) "收入（类型待核对）" else "支出（类型待核对）"
}
