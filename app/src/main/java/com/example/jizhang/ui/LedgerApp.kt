package com.example.jizhang.ui

import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.jizhang.automation.NotificationAccess
import com.example.jizhang.automation.PaymentNotificationListener
import com.example.jizhang.automation.ScreenAccess
import com.example.jizhang.automation.ScreenCaptureService
import com.example.jizhang.automation.ScreenCaptureStatus
import com.example.jizhang.automation.ScreenConnectionDiagnostics
import com.example.jizhang.automation.ScreenServiceHealth
import com.example.jizhang.automation.ScreenServiceHealthStore
import com.example.jizhang.automation.ScreenCaptureMetrics
import com.example.jizhang.automation.LocalTextRecognizer
import com.example.jizhang.data.LedgerRepository
import com.example.jizhang.data.LedgerSettings
import com.example.jizhang.data.RecognitionEvent
import com.example.jizhang.data.RecognitionDiagnostic
import com.example.jizhang.data.MerchantCategoryRule
import com.example.jizhang.recognition.CandidateFactory
import com.example.jizhang.recognition.EvidenceType
import com.example.jizhang.recognition.RecognitionDecision
import com.example.jizhang.recognition.ocr.OcrReceiptLayoutParser
import com.example.jizhang.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

private val Ink = Color(0xFF253C2F)
private val Muted = Color(0xFF798276)
private val Green = Color(0xFF345A49)
private val Orange = Color(0xFFBA7651)
private val Pale = Color(0xFFE9EEE2)
private val Round = RoundedCornerShape(24.dp)

@Composable
fun LedgerApp(repositoryOverride: LedgerRepository? = null) {
    val context = LocalContext.current
    val repository = remember(repositoryOverride) { repositoryOverride ?: LedgerRepository.get(context) }
    val entries by repository.entries.collectAsState()
    val settings by repository.settings.collectAsState()
    val storageError by repository.storageError.collectAsState()
    val restoreBlocked by repository.restoreBlocked.collectAsState()
    val listenerConnected by PaymentNotificationListener.connectionState.collectAsState()
    val captureError by repository.captureError.collectAsState()
    val screenConnected by ScreenCaptureService.connectionState.collectAsState()
    val serviceHealth by ScreenCaptureService.lifecycleHealth.collectAsState()
    val screenStatus by ScreenCaptureService.status.collectAsState()
    val recognitionEvents by repository.recognitionEvents.collectAsState()
    val deletedEntries by repository.deletedEntries.collectAsState()
    val merchantRules by repository.merchantRules.collectAsState()
    val diagnostics by repository.diagnostics.collectAsState()
    val screenMetrics by ScreenCaptureService.metrics.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var billFilter by rememberSaveable { mutableIntStateOf(0) }
    var month by rememberSaveable { mutableLongStateOf(monthStart(System.currentTimeMillis())) }
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LedgerEntry?>(null) }
    var budgetOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var recognitionOpen by rememberSaveable { mutableStateOf(false) }
    var recycleOpen by rememberSaveable { mutableStateOf(false) }
    var rulesOpen by rememberSaveable { mutableStateOf(false) }
    var granted by remember { mutableStateOf(NotificationAccess.isGranted(context)) }
    var screenConnectionDiagnostics by remember { mutableStateOf(ScreenAccess.readConnectionDiagnostics(context, screenConnected)) }
    val screenGranted = screenConnectionDiagnostics.granted == true
    val healthStore = remember(context) { ScreenServiceHealthStore(context) }
    var storedServiceHealth by remember { mutableStateOf(healthStore.read()) }
    val recentServiceHealth = if (serviceHealth.changedAt >= storedServiceHealth.changedAt && serviceHealth.changedAt > 0) serviceHealth else storedServiceHealth
    var connectionCheckedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var connectionQueryGeneration by remember { mutableLongStateOf(0L) }
    var importingScreenshot by remember { mutableStateOf(false) }
    var screenshotSource by remember { mutableStateOf(EntrySource.WECHAT) }
    var screenshotGeneration by remember { mutableLongStateOf(-1L) }
    fun checkScreenConnection() {
        val generation = ++connectionQueryGeneration
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                ScreenAccess.readConnectionDiagnostics(context, ScreenCaptureService.connectionState.value) to healthStore.read()
            }
            if (generation == connectionQueryGeneration) {
                screenConnectionDiagnostics = snapshot.first.copy(localConnected = ScreenCaptureService.connectionState.value)
                storedServiceHealth = snapshot.second
                connectionCheckedAt = System.currentTimeMillis()
            }
        }
    }
    LaunchedEffect(screenConnected) { checkScreenConnection() }
    val lifecycleOwner = context as androidx.activity.ComponentActivity
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = NotificationAccess.isGranted(context)
                checkScreenConnection()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val screenshotPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            if (saving || restoreBlocked || screenshotGeneration != repository.recognitionConfigurationGeneration()) {
                snackbar.showSnackbar("账本或识别配置已变化，请重新导入截图")
                return@launch
            }
            val importedGeneration = screenshotGeneration
            val importedSource = screenshotSource
            importingScreenshot = true
            var ownedBitmap: Bitmap? = null
            val result = try { runCatching {
                // Preserve ownership if cancellation happens while decoding returns from IO.
                withContext(NonCancellable) {
                    ownedBitmap = withContext(Dispatchers.IO) { loadPaymentScreenshot(context, uri) }
                }
                coroutineContext.ensureActive()
                val page = LocalTextRecognizer.recognizePage(checkNotNull(ownedBitmap))
                val pkg = if (importedSource == EntrySource.WECHAT) "com.tencent.mm" else "com.eg.android.AlipayGphone"
                val interpreted = OcrReceiptLayoutParser.interpret(pkg, page)
                val payment = interpreted.payment ?: error("没有找到可确认的交易结果及金额区域，请手动补录")
                val base = CandidateFactory.fromScreen(payment, System.currentTimeMillis(), EvidenceType.IMPORTED_IMAGE, userInitiated = true)
                val candidate = base.copy(amountOptions = interpreted.amountOptions, reasons = base.reasons + interpreted.reasons)
                val saved = repository.ingest(candidate) {
                    importedGeneration == repository.recognitionConfigurationGeneration() && !repository.restoreBlocked.value
                }
                repository.reportRecognitionDiagnostic(EvidenceType.IMPORTED_IMAGE, if (saved.disabled) "导入图片因账本或配置变化未提交" else "已识别用户导入图片", !saved.disabled, page.elapsedMs)
                if (!saved.disabled) recognitionOpen = true
                saved
            } } finally { ownedBitmap?.recycle(); importingScreenshot = false }
            result.onSuccess { saved -> snackbar.showSnackbar(if (saved.disabled) "账本或识别配置已变化，请重新导入截图" else if (saved.duplicate) "这条识别来源已处理" else "已加入识别中心，确认前不计入账本") }
                .onFailure { if (it is CancellationException) throw it; repository.reportRecognitionDiagnostic(EvidenceType.IMPORTED_IMAGE, "截图未形成安全候选", false, 0); snackbar.showSnackbar("截图未形成安全候选，请选择清晰的交易结果截图或手动补录") }
        }
    }
    val openReview: () -> Unit = { if (!saving && !importingScreenshot && !restoreBlocked) recognitionOpen = true }
    fun changeSettings(updated: LedgerSettings): Boolean {
        if (saving || importingScreenshot || restoreBlocked) return false
        return runCatching { repository.updateSettings(updated) }.isSuccess.also { success ->
            if (!success) scope.launch { snackbar.showSnackbar("设置未保存，账本仍在初始化或恢复，请稍后重试") }
        }
    }
    fun operation(message: String, closeCenter: Boolean = false, action: suspend () -> Unit) {
        if (saving || importingScreenshot || restoreBlocked) return
        saving = true
        scope.launch {
            var outcome = message
            try {
                action()
                if (closeCenter) recognitionOpen = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                outcome = "操作未完成，请重新核对识别来源、账单和退款余额后重试"
            } finally {
                saving = false
            }
            snackbar.showSnackbar(outcome)
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val csv = repository.exportCsv()
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: error("无法打开导出文件")
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(csv) }
                }
            }.onSuccess { snackbar.showSnackbar("账单已导出") }
                .onFailure { snackbar.showSnackbar("导出失败，请选择可写入的位置") }
        }
    }
    val visibleEntries = remember(entries, recognitionEvents) {
        val legacyPendingIds = recognitionEvents.filter { it.decision == RecognitionDecision.REVIEW && it.association == "LEGACY" }.mapNotNull { it.linkedEntryId }.toSet()
        entries.filterNot { it.needsReview && it.id in legacyPendingIds }
    }
    val monthEntries = remember(visibleEntries, month) {
        val end = shiftMonth(month, 1)
        visibleEntries.filter { it.occurredAt >= month && it.occurredAt < end }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (tab != 2) FloatingActionButton(
                onClick = { if (!restoreBlocked && !saving && !importingScreenshot) { editing = null; editorOpen = true } },
                containerColor = Green, contentColor = Color.White, shape = RoundedCornerShape(20.dp)
            ) { Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("add", Color.White, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("记一笔")
            } }
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                listOf("概览", "账单", "自动记账").forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { Glyph(listOf("chart", "ledger", "auto")[index], if (tab == index) Green else Muted, Modifier.size(23.dp)) },
                        label = { Text(label) }, colors = NavigationBarItemDefaults.colors(indicatorColor = Pale))
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize().widthIn(max = 780.dp).align(Alignment.TopCenter)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Green), contentAlignment = Alignment.Center) {
                        Glyph("ledger", Color.White, Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("轻记", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Spacer(Modifier.weight(1f))
                    Surface(color = Pale, shape = CircleShape) {
                        Text("本地账本", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = Green, fontSize = 11.sp)
                    }
                }
                storageError?.let { Text(it, color = Orange, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
                if (restoreBlocked) Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("账本正在初始化或等待恢复，暂时不能修改。", modifier = Modifier.weight(1f), color = Orange, fontSize = 12.sp)
                    TextButton(onClick = {
                        if (!saving) { saving = true; scope.launch {
                            val recovered = try { repository.retryPendingRestore(); !repository.restoreBlocked.value } catch (_: Exception) { false }
                            finally { saving = false }
                            if (!recovered) snackbar.showSnackbar("恢复未完成，请检查设备存储后重试")
                        } }
                    }, enabled = !saving, modifier = Modifier.testTag("retry-pending-restore")) { Text("重试") }
                }
                when (tab) {
                    0 -> Overview(monthEntries, entries, month, settings, granted, listenerConnected, screenGranted && screenConnected, recognitionEvents.count { it.decision == RecognitionDecision.REVIEW },
                        onMonth = { month = shiftMonth(month, it) }, onBudget = { if (!restoreBlocked && !saving && !importingScreenshot) budgetOpen = true },
                        onAll = { billFilter = 0; tab = 1 }, onReview = openReview, onAutomation = { tab = 2 }, onEntry = { if (!saving && !importingScreenshot && !restoreBlocked) { editing = it; editorOpen = true } })
                    1 -> Bills(monthEntries, month, billFilter, onFilter = { billFilter = it }, onMonth = { month = shiftMonth(month, it) }, onEntry = { if (!saving && !importingScreenshot && !restoreBlocked) { editing = it; editorOpen = true } }, onReview = openReview,
                        onExport = { if (!saving && !importingScreenshot && !restoreBlocked) exportLauncher.launch("轻记账单-${dateText(System.currentTimeMillis(), "yyyyMMdd")}.csv") })
                    2 -> Automation(settings, granted, listenerConnected, captureError, screenGranted, screenConnected, screenStatus, importingScreenshot,
                        screenConnectionDiagnostics = screenConnectionDiagnostics.copy(localConnected = screenConnected), serviceHealth = recentServiceHealth,
                        onCheckScreenConnection = ::checkScreenConnection,
                        onCopyScreenDiagnostics = {
                            val diagnostic = screenConnectionDiagnostics.copy(localConnected = screenConnected)
                            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "未知"
                            val report = "轻记连接诊断\n应用版本：$version\n设备：${android.os.Build.MANUFACTURER.take(80)} ${android.os.Build.MODEL.take(80)}\nAndroid API：${android.os.Build.VERSION.SDK_INT}\n系统授权：${diagnostic.granted}\n系统服务列表：${diagnostic.systemBound}\n轻记连接回调：${diagnostic.localConnected}\n最近生命周期：${recentServiceHealth.phase.label}\n生命周期时间：${recentServiceHealth.changedAt}\n上次连接时间：${recentServiceHealth.lastConnectedAt}\n配置兼容提示：${recentServiceHealth.configurationWarning}\n生命周期记录可读：${recentServiceHealth.readable}\n检查时间：${dateText(connectionCheckedAt, "yyyy-MM-dd HH:mm:ss")}"
                            runCatching {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("轻记连接诊断", report))
                            }.onSuccess { scope.launch { snackbar.showSnackbar("连接诊断已复制，不包含账单或页面内容") } }
                                .onFailure { scope.launch { snackbar.showSnackbar("无法复制诊断，请查看连接排查中的状态") } }
                        },
                        events = recognitionEvents, diagnostics = diagnostics, metrics = screenMetrics, controlsEnabled = !saving && !importingScreenshot && !restoreBlocked,
                        onAppInfo = { runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }.onFailure { scope.launch { snackbar.showSnackbar("请到系统设置的应用列表打开轻记详情") } } },
                        onBackgroundSettings = {
                            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                                .recoverCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                                .onFailure { scope.launch { snackbar.showSnackbar("请在系统设置中检查轻记的后台运行与电池限制") } }
                        },
                        onRecycle = { if (!saving && !importingScreenshot && !restoreBlocked) recycleOpen = true }, onRules = { if (!saving && !importingScreenshot && !restoreBlocked) rulesOpen = true },
                        backup = { BackupControls(repository, onBusyChanged = { saving = it }, onRestored = {
                            recognitionOpen = false; editorOpen = false; editing = null; recycleOpen = false; rulesOpen = false; budgetOpen = false
                            month = monthStart(System.currentTimeMillis()); billFilter = 0
                        }, onMessage = { scope.launch { snackbar.showSnackbar(it) } }, busy = saving || importingScreenshot) },
                        onScreenAccess = { runCatching { context.startActivity(ScreenAccess.settingsIntent(context)) }.onFailure { scope.launch { snackbar.showSnackbar("请在系统无障碍设置中开启“轻记 · 屏幕记账”") } } },
                        onImportScreenshot = { if (!saving && !importingScreenshot && !restoreBlocked) { screenshotSource = it; screenshotGeneration = repository.recognitionConfigurationGeneration(); screenshotPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } },
                        onReview = openReview, onAcknowledge = repository::acknowledgeCaptureError, onSettings = { changeSettings(it) }, onAccess = {
                        try { context.startActivity(NotificationAccess.settingsIntent(context)) }
                        catch (_: ActivityNotFoundException) {
                            runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
                                .onFailure { scope.launch { snackbar.showSnackbar("请在系统设置中搜索“通知使用权”") } }
                        }
                    })
                }
            }
        }
    }
    if (editorOpen) EntryEditor(editing, entries, recognitionEvents, saving || importingScreenshot || restoreBlocked, onDismiss = { if (!saving) editorOpen = false }, onSave = { entry, rememberCategory ->
        if (saving || importingScreenshot || restoreBlocked) return@EntryEditor
        saving = true
        scope.launch {
            val result = runCatching { if (entry.id == 0L) repository.add(entry, rememberCategory) else { repository.update(entry, rememberCategory); true } }
            saving = false
            result
                .onSuccess { editorOpen = false; snackbar.showSnackbar(if (editing?.needsReview == true) "账单已确认" else "账单已保存") }
                .onFailure { snackbar.showSnackbar("保存失败，请稍后再试") }
        }
    }, onDelete = { id ->
        if (saving || importingScreenshot || restoreBlocked) return@EntryEditor
        val deletionGeneration = repository.recognitionConfigurationGeneration()
        saving = true; scope.launch {
        val result = runCatching { repository.delete(id) }
        saving = false
        result.onSuccess {
            editorOpen = false
            if (snackbar.showSnackbar("账单已移入回收站", actionLabel = "撤销", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                if (deletionGeneration == repository.recognitionConfigurationGeneration() && !repository.restoreBlocked.value) {
                    runCatching { repository.restoreDeleted(id) }.onFailure { snackbar.showSnackbar("恢复失败，请到回收站核对退款关联") }
                } else snackbar.showSnackbar("账本已变化，请到回收站重新核对")
            }
        }
            .onFailure { snackbar.showSnackbar("删除失败，请稍后再试") }
    } })
    if (recognitionOpen) RecognitionCenter(recognitionEvents, entries, saving || importingScreenshot || restoreBlocked,
        onConfirm = { event, entry, rememberCategory, separately, completed ->
            operation("账单已确认", closeCenter = true) {
                repository.confirmEvent(event.id, entry, rememberCategory, separately, completed,
                    expectedFingerprint = event.candidate.eventFingerprint)
                month = monthStart(entry.occurredAt)
            }
        },
        onLink = { event, entryId -> operation("来源已关联已有账单", closeCenter = true) {
            repository.linkEvent(event.id, entryId, expectedFingerprint = event.candidate.eventFingerprint)
        } },
        onIgnore = { event -> operation("已忽略这条识别来源") { repository.ignoreEvent(event.id, expectedFingerprint = event.candidate.eventFingerprint) } },
        onUndoIgnore = { event -> operation("已恢复待确认") { repository.undoIgnoreEvent(event.id, expectedFingerprint = event.candidate.eventFingerprint) } },
        onUnlink = { event -> operation("来源关联已撤销") { repository.unlinkEvent(event.id, expectedFingerprint = event.candidate.eventFingerprint) } },
        onEditEntry = { id -> entries.firstOrNull { it.id == id }?.let { editing = it; editorOpen = true } },
        onDismiss = { recognitionOpen = false })
    if (recycleOpen) RecycleBin(deletedEntries, saving || importingScreenshot || restoreBlocked, onRestore = { id -> operation("账单已恢复") { repository.restoreDeleted(id) } }, onDismiss = { recycleOpen = false })
    if (rulesOpen) CategoryRules(merchantRules, saving || importingScreenshot || restoreBlocked, onDelete = { rule -> operation("商户分类规则已删除") { repository.deleteMerchantRule(rule.source, rule.merchantKey, rule.type) } }, onDismiss = { rulesOpen = false })
    if (budgetOpen) BudgetEditor(settings.monthlyBudgetCents, enabled = !saving && !importingScreenshot && !restoreBlocked, onDismiss = { budgetOpen = false }, onSave = {
        if (changeSettings(settings.copy(monthlyBudgetCents = it))) budgetOpen = false
    })
}

@Composable
private fun Overview(entries: List<LedgerEntry>, allEntries: List<LedgerEntry>, month: Long, settings: LedgerSettings, granted: Boolean, connected: Boolean, screenConnected: Boolean, reviewCount: Int,
                     onMonth: (Int) -> Unit, onBudget: () -> Unit, onAll: () -> Unit, onReview: () -> Unit, onAutomation: () -> Unit, onEntry: (LedgerEntry) -> Unit) {
    val totals = LedgerStatistics.calculate(entries, allEntries)
    val expense = totals.expenseCents
    val income = totals.incomeCents
    LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("把生活，记清楚。", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("每一笔小事，都值得好好记录", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 5.dp))
        }
        item { MonthPicker(month, onMonth) }
        item {
            Surface(color = Green, shape = Round) {
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("本月支出", color = Color(0xFFD0DDD0), fontSize = 13.sp)
                        Spacer(Modifier.weight(1f)); Glyph("leaf", Color(0xFFB8CD9F), Modifier.size(26.dp))
                    }
                    Text("¥ ${Money.format(expense)}", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp, bottom = 23.dp).testTag("monthly-expense"), maxLines = 1)
                    HorizontalDivider(color = Color.White.copy(alpha = .15f))
                    Row(Modifier.padding(top = 18.dp)) {
                        Column(Modifier.weight(1f)) { Text("本月收入", color = Color(0xFFD0DDD0), fontSize = 11.sp); Text("¥ ${Money.format(income)}", color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(top = 6.dp).testTag("monthly-income")) }
                        Column(Modifier.weight(1f)) { Text("本月结余", color = Color(0xFFD0DDD0), fontSize = 11.sp); Text("¥ ${Money.format(income - expense)}", color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(top = 6.dp)) }
                    }
                }
            }
        }
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("月度预算", fontWeight = FontWeight.SemiBold, color = Ink); Spacer(Modifier.weight(1f))
                    TextButton(onClick = onBudget, contentPadding = PaddingValues(0.dp)) { Text("设置预算", fontSize = 12.sp) }
                }
                val budget = settings.monthlyBudgetCents
                val progress = if (budget > 0) (expense.toFloat() / budget).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape), color = if (expense > budget) Orange else Green, trackColor = Pale)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(if (expense > budget) "超出 ¥ ${Money.format(expense - budget)}" else "还可用 ¥ ${Money.format(budget - expense)}", color = if (expense > budget) Orange else Muted, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f)); Text("预算 ¥ ${Money.format(budget)}", color = Muted, fontSize = 12.sp)
                }
            }
        }
        item {
            Surface(onClick = onAutomation, shape = RoundedCornerShape(18.dp), color = Pale) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("auto", Green, Modifier.size(26.dp)); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (settings.screenEnabled && screenConnected) "屏幕记账已开启" else if (settings.autoEnabled && granted && connected) "通知记账已开启" else if (settings.screenEnabled || settings.autoEnabled && granted) "记账服务等待连接" else "让记账，再轻松一点", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        Text(if (settings.screenEnabled && screenConnected) "付款后停留于交易结果页，自动识别" else if (settings.autoEnabled && granted && connected) "识别微信与支付宝的新交易通知" else "转账、红包没有通知？试试屏幕记账", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
                    }; Text("›", color = Green, fontSize = 25.sp)
                }
            }
        }
        if (reviewCount > 0) item { TextButton(onClick = onReview) { Text("有 $reviewCount 笔待确认，点击核对（暂不计入统计）", color = Orange, fontSize = 12.sp) } }
        item {
            val categories = LedgerStatistics.expenseByCategory(entries, allEntries).toList().sortedByDescending { it.second }
            val categoryScale = categories.maxOfOrNull { kotlin.math.abs(it.second) }?.coerceAtLeast(1L) ?: 1L
            Panel {
                Text("钱花在哪里", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                if (categories.isEmpty()) Text("记录第一笔支出后，这里会显示分类统计。", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 18.dp))
                categories.take(5).forEach { (category, amount) ->
                    Row(Modifier.fillMaxWidth().padding(top = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(category, color = Ink, fontSize = 13.sp, modifier = Modifier.width(44.dp))
                        Box(Modifier.weight(1f).padding(horizontal = 12.dp).height(6.dp).clip(CircleShape).background(Pale)) {
                            Box(Modifier.fillMaxWidth((kotlin.math.abs(amount).toFloat() / categoryScale).coerceIn(0f, 1f)).fillMaxHeight().background(if (amount < 0) Orange else Green.copy(alpha = .75f)))
                        }; Text("¥ ${Money.format(amount)}", color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text("最近账单", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink); Spacer(Modifier.weight(1f)); TextButton(onClick = onAll) { Text("查看全部 ›", fontSize = 12.sp) } } }
        if (entries.isEmpty()) item { EmptyState("账本，从这一笔开始", "点击“记一笔”，记录今天的收支。") }
        items(entries.take(5), key = { it.id }) { EntryRow(it, onEntry) }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun Bills(entries: List<LedgerEntry>, month: Long, filter: Int, onFilter: (Int) -> Unit, onMonth: (Int) -> Unit, onEntry: (LedgerEntry) -> Unit, onReview: () -> Unit, onExport: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = entries.filter { entry ->
        (filter == 0 || filter == 1 && entry.businessType == BusinessType.EXPENSE || filter == 2 && entry.businessType == BusinessType.INCOME ||
            filter == 3 && entry.businessType in setOf(BusinessType.TRANSFER, BusinessType.REPAYMENT, BusinessType.REFUND)) &&
            (query.isBlank() || "${entry.merchant} ${entry.category} ${entry.note} ${sourceLabel(entry.source)}".contains(query, ignoreCase = true))
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("我的账单", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Ink); Spacer(Modifier.weight(1f))
            TextButton(onClick = onExport) { Glyph("export", Green, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("导出") }
        }
        Text("有来有往，心里有数", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 18.dp))
        MonthPicker(month, onMonth)
        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("搜索商户、分类或备注", fontSize = 13.sp) },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp), shape = RoundedCornerShape(16.dp))
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "支出", "收入", "往来 / 退款").forEachIndexed { index, label ->
                FilterChip(selected = filter == index, onClick = { onFilter(index) }, label = { Text(label, fontSize = 12.sp) })
            }
        }
        TextButton(onClick = onReview, modifier = Modifier.testTag("open-recognition-center")) { Text("查看待确认交易与识别来源") }
        Text("${filtered.size} 笔记录 · 导出包含全部月份", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 14.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (filtered.isEmpty()) item { EmptyState(if (entries.isEmpty()) "这个月，还没有账单" else "没有符合条件的账单", "可切换月份、筛选条件，或手动记一笔。") }
            val grouped = filtered.groupBy { dateText(it.occurredAt, "MM月dd日 EEEE") }
            grouped.forEach { (date, rows) ->
                item { Text(date, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) }
                items(rows, key = { it.id }) { EntryRow(it, onEntry) }
            }
        }
    }
}

@Composable
private fun Automation(settings: LedgerSettings, granted: Boolean, connected: Boolean, captureError: String?, screenGranted: Boolean, screenConnected: Boolean, screenStatus: ScreenCaptureStatus, importing: Boolean,
                       screenConnectionDiagnostics: ScreenConnectionDiagnostics, serviceHealth: ScreenServiceHealth,
                       onCheckScreenConnection: () -> Unit, onCopyScreenDiagnostics: () -> Unit,
                       events: List<RecognitionEvent>, diagnostics: List<RecognitionDiagnostic>, metrics: ScreenCaptureMetrics,
                       controlsEnabled: Boolean,
                       onAppInfo: () -> Unit, onBackgroundSettings: () -> Unit, onRecycle: () -> Unit, onRules: () -> Unit, backup: @Composable () -> Unit,
                       onScreenAccess: () -> Unit, onImportScreenshot: (EntrySource) -> Unit, onReview: () -> Unit,
                       onAcknowledge: () -> Unit, onSettings: (LedgerSettings) -> Unit, onAccess: () -> Unit) {
    var testOpen by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column { Text("自动记账", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Ink); Text("少一点操作，多一点生活", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp)) }
        ScreenControls(settings, screenGranted, screenConnected, screenStatus, importing, onSettings, onScreenAccess, onImportScreenshot, onReview, onAppInfo, enabled = controlsEnabled,
            connectionDiagnostics = screenConnectionDiagnostics, serviceHealth = serviceHealth, onCheckConnection = onCheckScreenConnection,
            onBackgroundSettings = onBackgroundSettings, onCopyDiagnostics = onCopyScreenDiagnostics)
        DeviceCompatibilityCard(onBackgroundSettings)
        RecognitionDiagnostics(events, diagnostics, metrics)
        Surface(color = Green, shape = Round) {
            Column(Modifier.fillMaxWidth().padding(22.dp)) {
                Glyph("auto", Color(0xFFBDD1A9), Modifier.size(30.dp))
                Text(when { !granted -> "连接你的交易通知"; !settings.autoEnabled -> "准备好了，随时开启"; !connected -> "已授权，等待系统连接"; else -> "已开启，等待新交易通知" }, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
                Text("通知记账可作为补充。没有通知的转账、红包，可尝试上方的屏幕记账或导入结果截图。", color = Color(0xFFDBE5DA), fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 10.dp))
                FilledTonalButton(onClick = onAccess, modifier = Modifier.padding(top = 14.dp), colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFFE9EEDC), contentColor = Green)) {
                    Text(if (granted) "管理通知访问" else "开启通知访问")
                }
            }
        }
        captureError?.let {
            Panel {
                Text(it, color = Orange, fontSize = 12.sp, lineHeight = 20.sp)
                TextButton(onClick = onAcknowledge) { Text("已核对，清除提示", fontSize = 12.sp) }
            }
        }
        if (granted && settings.autoEnabled && !connected) Text("系统尚未连接监听服务。可进入“管理通知访问”重新授权；若开启了省电限制，请允许轻记在后台运行。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("自动记录收支", color = Ink, fontWeight = FontWeight.SemiBold); Text(if (granted) "通知访问已授权" else "还需在系统设置中授权", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
                Switch(checked = settings.autoEnabled, enabled = controlsEnabled, onCheckedChange = { onSettings(settings.copy(autoEnabled = it)) })
            }
            HorizontalDivider(Modifier.padding(vertical = 15.dp), color = Pale)
            SourceToggle("微信支付", "付款、收款到账通知", settings.wechatEnabled, "微", enabled = controlsEnabled) { onSettings(settings.copy(wechatEnabled = it)) }
            Spacer(Modifier.height(14.dp))
            SourceToggle("支付宝", "付款成功、到账与退款通知", settings.alipayEnabled, "支", enabled = controlsEnabled) { onSettings(settings.copy(alipayEnabled = it)) }
        }
        Panel {
            Text("三步，让它开始工作", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            SetupStep("01", "授权通知访问", "在系统通知使用权页面允许“轻记”。")
            SetupStep("02", "打开自动记录开关", "只识别所选来源，忽略聊天和非交易通知。")
            SetupStep("03", "核对识别结果", "可靠消费进入账本；转账、还款、退款或不确定结果进入识别中心。")
        }
        backup()
        Panel {
            Text("账本管理", fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onRecycle, enabled = controlsEnabled, modifier = Modifier.testTag("open-recycle-bin")) { Text("回收站与恢复账单") }
            TextButton(onClick = onRules, enabled = controlsEnabled, modifier = Modifier.testTag("open-category-rules")) { Text("商户分类规则") }
        }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) { Glyph("shield", Green, Modifier.size(22.dp)); Spacer(Modifier.width(8.dp)); Text("你的账单，只在你的设备", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 14.sp) }
            Text("无需登录，不上传账单，不保存完整通知、页面文字或截图。仅处理勾选的两个应用；可随时关闭开关或撤销授权。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 12.dp))
            Text("部分 Android 版本会隐藏敏感通知；系统省电策略或应用通知设置也可能影响识别。已发生且未收到通知的交易不会自动补回。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 10.dp))
        }
        OutlinedButton(onClick = { testOpen = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("试试通知识别") }
    }
    if (testOpen) ParserLab { testOpen = false }
}

@Composable
private fun RecognitionDiagnostics(events: List<RecognitionEvent>, diagnostics: List<RecognitionDiagnostic>, metrics: ScreenCaptureMetrics) {
    Panel {
        Text("智能识别状态", fontWeight = FontWeight.SemiBold)
        Text("已处理 ${events.count { it.decision == RecognitionDecision.AUTO }} · 待确认 ${events.count { it.decision == RecognitionDecision.REVIEW }} · 已忽略 ${events.count { it.decision == RecognitionDecision.IGNORE }}",
            modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
        Text("数量按识别来源事件统计；同一账单可能关联多条凭据。", color = Muted, style = MaterialTheme.typography.bodySmall)
        listOf(EvidenceType.NOTIFICATION, EvidenceType.ACCESSIBILITY, EvidenceType.OCR, EvidenceType.IMPORTED_IMAGE).forEach { channel ->
            val diagnostic = diagnostics.firstOrNull { it.channel == channel.name }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text(evidenceLabel(channel), fontWeight = FontWeight.Medium)
            Text("最近成功：${diagnostic?.lastSuccessAt?.takeIf { it > 0 }?.let { dateText(it, "MM-dd HH:mm:ss") } ?: "尚无记录"}", style = MaterialTheme.typography.bodySmall)
            if (diagnostic != null && diagnostic.lastFailureAt > 0) Text(
                "最近未识别：${dateText(diagnostic.lastFailureAt, "MM-dd HH:mm:ss")} · ${diagnostic.lastFailureReason.take(160)}",
                color = Orange, style = MaterialTheme.typography.bodySmall)
            if (diagnostic != null && diagnostic.lastDurationMs > 0) Text("最近处理耗时 ${diagnostic.lastDurationMs} ms", style = MaterialTheme.typography.bodySmall)
        }
        if (metrics.visitedNodes > 0) Text("最近页面检查：${metrics.visitedNodes} 个节点 · ${metrics.retainedFields} 个字段 · ${metrics.durationMs} ms",
            color = Muted, style = MaterialTheme.typography.bodySmall)
        if (metrics.ocrDurationMs > 0) Text("最近 OCR ${metrics.ocrDurationMs} ms · 队列等待 ${metrics.ocrQueueWaitMs} ms", color = Muted, style = MaterialTheme.typography.bodySmall)
        Text("已连接表示授权和服务可用；页面隐藏或受保护时仍可能无法识别。", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SourceToggle(title: String, subtitle: String, checked: Boolean, initial: String, enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(if (initial == "微") Pale else Color(0xFFEAF0F5)), contentAlignment = Alignment.Center) {
            Text(initial, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (initial == "微") Green else Color(0xFF4D799A))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(title, color = Ink, fontSize = 14.sp); Text(subtitle, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChecked)
    }
}

@Composable
private fun SetupStep(number: String, title: String, description: String) {
    Row(Modifier.padding(top = 20.dp)) {
        Text(number, color = Green.copy(alpha = .65f), fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp))
        Column { Text(title, color = Ink, fontSize = 14.sp); Text(description, color = Muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color.White, shape = Round) { Column(Modifier.fillMaxWidth().padding(20.dp), content = content) }
}

@Composable
private fun MonthPicker(month: Long, onMonth: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(dateText(month, "yyyy年 MM月"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = { onMonth(-1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(32.dp), shape = CircleShape) { Text("‹", fontSize = 20.sp) }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { onMonth(1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(32.dp), shape = CircleShape) { Text("›", fontSize = 20.sp) }
    }
}

@Composable
private fun EmptyState(title: String, description: String) {
    Surface(color = Color.White, shape = Round) {
        Column(Modifier.fillMaxWidth().padding(vertical = 30.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(62.dp).clip(CircleShape).background(Pale), contentAlignment = Alignment.Center) { Glyph("ledger", Green, Modifier.size(30.dp)) }
            Text(title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp))
            Text(description, color = Muted, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 7.dp))
        }
    }
}

@Composable
private fun EntryRow(entry: LedgerEntry, onClick: (LedgerEntry) -> Unit) {
    Surface(onClick = { onClick(entry) }, modifier = Modifier.testTag("ledger-entry-${entry.id}"), color = Color.White, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(if (entry.type == EntryType.INCOME) Pale else Color(0xFFF3EBE0)), contentAlignment = Alignment.Center) {
                Text(entry.category.take(1), color = if (entry.type == EntryType.INCOME) Green else Orange, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(entry.merchant.ifBlank { entry.category }, color = Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${businessLabel(entry.businessType)} · ${entry.category} · ${sourceLabel(entry.source)}${if (entry.editedByUser) " · 人工核对" else ""} · ${dateText(entry.occurredAt, "HH:mm")}", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp), maxLines = 2)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${if (entry.type == EntryType.EXPENSE) "−" else "+"}${Money.format(entry.amountCents)}", color = if (entry.type == EntryType.EXPENSE) Ink else Green, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                if (entry.needsReview) Text("待确认", color = Orange, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun EntryEditor(entry: LedgerEntry?, allEntries: List<LedgerEntry>, events: List<RecognitionEvent>, saving: Boolean,
                        onDismiss: () -> Unit, onSave: (LedgerEntry, Boolean) -> Unit, onDelete: (Long) -> Unit) {
    val context = LocalContext.current
    var type by remember { mutableStateOf(entry?.type ?: EntryType.EXPENSE) }
    var business by remember { mutableStateOf(entry?.businessType ?: BusinessType.EXPENSE) }
    var amount by remember { mutableStateOf(entry?.let { Money.format(it.amountCents) } ?: "") }
    var merchant by remember { mutableStateOf(entry?.merchant ?: "") }
    var category by remember { mutableStateOf(entry?.category ?: "餐饮") }
    var note by remember { mutableStateOf(entry?.note ?: "") }
    var occurredAt by remember { mutableLongStateOf(entry?.occurredAt ?: System.currentTimeMillis()) }
    var error by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var rememberCategory by remember { mutableStateOf(false) }
    var relatedId by remember { mutableStateOf(entry?.relatedEntryId) }
    var refundSearch by remember { mutableStateOf("") }
    val cents = Money.parseCents(amount)
    val refunds = allEntries.filter { it.id != entry?.id && !it.needsReview && it.businessType == BusinessType.REFUND &&
        it.type == EntryType.INCOME && it.relatedEntryId != null }.groupBy { it.relatedEntryId }.mapValues { (_, rows) -> rows.sumOf { it.amountCents } }
    val originals = allEntries.filter { !it.needsReview && it.businessType == BusinessType.EXPENSE && it.type == EntryType.EXPENSE && it.id != entry?.id }
    val remaining = originals.associate { it.id to (it.amountCents - (refunds[it.id] ?: 0L)).coerceAtLeast(0) }
    val original = originals.firstOrNull { it.id == relatedId }
    val refundValid = original != null && cents != null && cents <= (remaining[original.id] ?: 0L)
    val existingRefunds = entry?.id?.let { refunds[it] } ?: 0L
    fun selectBusiness(value: BusinessType) {
        business = value
        when (value) {
            BusinessType.EXPENSE -> { type = EntryType.EXPENSE; category = "餐饮" }
            BusinessType.INCOME -> { type = EntryType.INCOME; category = "收款" }
            BusinessType.REFUND -> { type = EntryType.INCOME; category = "退款" }
            BusinessType.TRANSFER -> category = "转账"
            BusinessType.REPAYMENT -> { type = EntryType.EXPENSE; category = "还款" }
            BusinessType.UNKNOWN -> Unit
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = Round, color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (entry == null) "记一笔" else if (entry.needsReview) "确认账单" else "编辑账单", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f)); TextButton(onClick = onDismiss, contentPadding = PaddingValues(0.dp)) { Text("关闭") }
                }
                Text("这笔资金的用途", color = Muted, fontSize = 12.sp)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(BusinessType.EXPENSE, BusinessType.INCOME, BusinessType.TRANSFER, BusinessType.REFUND, BusinessType.REPAYMENT).forEach { value ->
                        FilterChip(selected = business == value, enabled = !saving && (existingRefunds == 0L || value == BusinessType.EXPENSE),
                            modifier = Modifier.testTag("entry-business-${value.name}"), onClick = { selectBusiness(value) }, label = { Text(businessLabel(value)) })
                    }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(EntryType.EXPENSE to "资金转出", EntryType.INCOME to "资金转入").forEach { (value, label) ->
                        val allowed = (business != BusinessType.REFUND || value == EntryType.INCOME) &&
                            (business != BusinessType.REPAYMENT || value == EntryType.EXPENSE) && existingRefunds == 0L
                        FilterChip(selected = type == value, enabled = !saving && allowed,
                            modifier = Modifier.testTag("entry-direction-${value.name}"), onClick = {
                                type = value
                                if (business in setOf(BusinessType.EXPENSE, BusinessType.INCOME)) selectBusiness(if (value == EntryType.EXPENSE) BusinessType.EXPENSE else BusinessType.INCOME)
                            }, label = { Text(label) })
                    }
                }
                if (business in setOf(BusinessType.TRANSFER, BusinessType.REPAYMENT)) Text("资金往来与还款不重复计入消费或收入统计。", color = Muted, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(amount, onValueChange = { amount = it; error = false }, label = { Text("金额（元）") }, prefix = { Text("¥ ") }, singleLine = true,
                    isError = error, supportingText = { if (error) Text("请输入大于 0 的金额，最多两位小数") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                if (business == BusinessType.REFUND) {
                    Text("关联原消费", fontWeight = FontWeight.Medium)
                    Text("退款抵扣原消费，不算新增收入。请选择可退余额足够的账单。", color = Muted, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(refundSearch, onValueChange = { refundSearch = it.take(80) }, label = { Text("搜索原消费") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    originals.filter { (remaining[it.id] ?: 0) >= (cents ?: 1L) &&
                        (refundSearch.isBlank() || "${it.merchant} ${it.category}".contains(refundSearch.trim(), true)) }.take(30).forEach { candidate ->
                        FilterChip(selected = relatedId == candidate.id, enabled = !saving,
                            modifier = Modifier.testTag("entry-refund-original-${candidate.id}"), onClick = { relatedId = candidate.id; category = candidate.category },
                            label = { Text("${candidate.merchant.ifBlank { candidate.category }} · 可退 ¥${Money.format(remaining[candidate.id] ?: 0L)}") })
                    }
                    Text(if (original != null) "退款分类：${original.category}" else "请先选择原消费", color = if (refundValid) Muted else Orange)
                } else {
                    OutlinedTextField(category, onValueChange = { category = it.take(80) }, label = { Text("分类") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (business in setOf(BusinessType.EXPENSE, BusinessType.INCOME)) {
                        val categories = if (business == BusinessType.EXPENSE) LedgerCategories.expense else LedgerCategories.income
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            categories.forEach { value -> FilterChip(selected = category == value, onClick = { category = value }, label = { Text(value, fontSize = 11.sp) }) }
                        }
                    }
                }
                if (existingRefunds > 0) Text("已有 ¥${Money.format(existingRefunds)} 退款关联，原消费金额不能低于该金额。", color = Orange, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(merchant, onValueChange = { if (it.length <= 120) merchant = it }, label = { Text("商户 / 对方（选填）") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(note, onValueChange = { if (it.length <= 500) note = it }, label = { Text("备注（选填）") }, maxLines = 3, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                TextButton(onClick = {
                    val calendar = Calendar.getInstance().apply { timeInMillis = occurredAt }
                    DatePickerDialog(context, { _, year, month, day ->
                        calendar.set(Calendar.YEAR, year); calendar.set(Calendar.MONTH, month); calendar.set(Calendar.DAY_OF_MONTH, day)
                        occurredAt = calendar.timeInMillis
                    }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
                }) { Text("日期  ${dateText(occurredAt, "yyyy年MM月dd日")}  ›") }
                if (business in setOf(BusinessType.EXPENSE, BusinessType.INCOME)) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(rememberCategory, onCheckedChange = { rememberCategory = it }, enabled = !saving && merchant.isNotBlank(), modifier = Modifier.testTag("entry-remember-category"))
                    Text("记住这位商户的分类", style = MaterialTheme.typography.bodySmall)
                }
                if (entry != null) {
                    Text("来源：${sourceLabel(entry.source)}${if (entry.editedByUser) " · 已经人工修改" else ""}", color = Muted, fontSize = 11.sp)
                    events.filter { it.linkedEntryId == entry.id }.forEach { source -> Text("${evidenceLabel(source.candidate.evidence)} · ${sourceLabel(source.candidate.provider)} · ${dateText(source.candidate.observedAt, "MM-dd HH:mm")}", color = Muted, fontSize = 11.sp) }
                }
                Button(onClick = {
                    val validAmount = Money.parseCents(amount)
                    if (validAmount == null) error = true else if (business != BusinessType.UNKNOWN && category.isNotBlank() &&
                        (business != BusinessType.REFUND || refundValid) && validAmount >= existingRefunds) onSave(
                        LedgerEntry(id = entry?.id ?: 0L, amountCents = validAmount, type = type,
                            category = if (business == BusinessType.REFUND) original!!.category else category.trim(), businessType = business,
                            relatedEntryId = if (business == BusinessType.REFUND) original?.id else null, editedByUser = true,
                            merchant = merchant.trim(), note = note.trim(), occurredAt = occurredAt, source = entry?.source ?: EntrySource.MANUAL,
                            fingerprint = entry?.fingerprint, needsReview = false), rememberCategory && merchant.isNotBlank() && business in setOf(BusinessType.EXPENSE, BusinessType.INCOME))
                }, enabled = !saving && business != BusinessType.UNKNOWN && (business != BusinessType.REFUND || refundValid),
                    modifier = Modifier.fillMaxWidth().testTag("entry-save"), shape = RoundedCornerShape(14.dp)) { Text(if (saving) "保存中…" else if (entry?.needsReview == true) "确认并保存" else "保存账单") }
                if (entry != null) TextButton(onClick = { deleting = true }, enabled = !saving, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("删除这笔账单", color = Orange) }
            }
        }
    }
    if (deleting && entry != null) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除这笔账单？") }, text = { Text("账单会移入回收站并从统计中移除；可在回收站恢复。已有识别来源会保留，通知更新不会把账单重新计入。") },
        confirmButton = { TextButton(onClick = { deleting = false; onDelete(entry.id) }) { Text("删除", color = Orange) } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
}

@Composable
private fun BudgetEditor(budget: Long, enabled: Boolean = true, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    var value by remember { mutableStateOf(Money.format(budget)) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("设置月度预算") }, text = {
        OutlinedTextField(value, onValueChange = { value = it; error = false }, label = { Text("每月支出上限（元）") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = error, supportingText = { if (error) Text("请输入大于 0 的金额") })
    }, confirmButton = { TextButton(onClick = { val cents = Money.parseCents(value); if (cents == null) error = true else onSave(cents) }, enabled = enabled) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun RecycleBin(entries: List<LedgerEntry>, saving: Boolean, onRestore: (Long) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = { if (!saving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("账单回收站", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("关闭") }
                }
                Text("恢复会重新计入统计，并检查退款金额及原消费关联。识别来源与修改记录会保留。", color = Muted, style = MaterialTheme.typography.bodySmall)
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                LazyColumn(Modifier.weight(1f).testTag("recycle-bin"), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                    if (entries.isEmpty()) item { Text("回收站为空", color = Muted) }
                    items(entries.sortedByDescending { it.occurredAt }, key = { it.id }) { entry ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(entry.merchant.ifBlank { entry.category }, fontWeight = FontWeight.SemiBold)
                            Text("${businessLabel(entry.businessType)} · ${sourceLabel(entry.source)} · ¥${Money.format(entry.amountCents)}")
                            Text(dateText(entry.occurredAt, "yyyy-MM-dd HH:mm"), color = Muted, style = MaterialTheme.typography.bodySmall)
                            if (entry.relatedEntryId != null) Text("关联原消费 #${entry.relatedEntryId}", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { onRestore(entry.id) }, enabled = !saving, modifier = Modifier.testTag("restore-entry-${entry.id}")) { Text("恢复这笔账单") }
                        } }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CategoryRules(rules: List<MerchantCategoryRule>, saving: Boolean, onDelete: (MerchantCategoryRule) -> Unit, onDismiss: () -> Unit) {
    var deleting by remember { mutableStateOf<MerchantCategoryRule?>(null) }
    Dialog(onDismissRequest = { if (!saving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("商户分类规则", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("关闭") }
                }
                Text("规则只用于同一来源、同一商户的收入或消费分类；不会改变资金方向、金额或业务类型。", color = Muted, style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f).testTag("category-rules"), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                    if (rules.isEmpty()) item { Text("尚未记住商户分类", color = Muted) }
                    items(rules.sortedByDescending { it.updatedAt }, key = { "${it.source}:${it.type}:${it.merchantKey}" }) { rule ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(rule.merchantKey, fontWeight = FontWeight.SemiBold)
                            Text("${sourceLabel(rule.source)} · ${if (rule.type == EntryType.EXPENSE) "支出" else "收入"} → ${rule.category}")
                            TextButton(onClick = { deleting = rule }, enabled = !saving, modifier = Modifier.testTag("delete-rule-${rule.merchantKey}")) { Text("删除规则") }
                        } }
                    }
                }
            }
        }
    }
    deleting?.let { rule -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这条分类规则？") },
        text = { Text("今后的识别不再套用这条规则，已记录账单的分类不受影响。") },
        confirmButton = { TextButton(onClick = { deleting = null; onDelete(rule) }, enabled = !saving, modifier = Modifier.testTag("confirm-delete-rule")) { Text("删除规则") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
private fun ParserLab(onDismiss: () -> Unit) {
    var wechat by remember { mutableStateOf(true) }
    var title by remember { mutableStateOf("微信支付") }
    var text by remember { mutableStateOf("支付成功，向星巴克支付28.00元") }
    var tested by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ParsedPayment?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("试试通知识别") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("仅预览解析结果，不会写入账本。可粘贴实际通知内容测试。", color = Muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = wechat, onClick = { wechat = true; title = "微信支付"; text = "支付成功，向星巴克支付28.00元"; tested = false }, label = { Text("微信") })
                FilterChip(selected = !wechat, onClick = { wechat = false; title = "支付宝"; text = "支付成功，向便利店付款18.50元"; tested = false }, label = { Text("支付宝") })
            }
            OutlinedTextField(title, onValueChange = { title = it; tested = false }, label = { Text("通知标题") }, singleLine = true)
            OutlinedTextField(text, onValueChange = { text = it; tested = false }, label = { Text("通知内容") }, minLines = 3, maxLines = 6)
            if (tested) Text(result?.let { "${if (it.type == EntryType.EXPENSE) "支出" else "收入"} ¥ ${Money.format(it.amountCents)}\n${it.category} · ${it.merchant.ifBlank { "未识别商户" }}${if (it.needsReview) " · 待确认" else ""}" }
                ?: "未识别：需要明确的交易方向和金额，且不能包含失败、待付款或聊天内容。", color = if (result == null) Orange else Green, fontSize = 13.sp)
        }
    }, confirmButton = { TextButton(onClick = { result = PaymentNotificationParser.parse(if (wechat) "com.tencent.mm" else "com.eg.android.AlipayGphone", title, text); tested = true }) { Text("识别") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable
private fun Glyph(name: String, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height; val stroke = w * .075f
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, Offset(w*x, h*y), Offset(w*x2, h*y2), stroke, StrokeCap.Round)
        when (name) {
            "add" -> { line(.5f,.15f,.5f,.85f); line(.15f,.5f,.85f,.5f) }
            "chart" -> { line(.18f,.8f,.18f,.55f); line(.5f,.8f,.5f,.2f); line(.82f,.8f,.82f,.4f) }
            "auto" -> { drawCircle(color, w*.37f, Offset(w*.5f,h*.5f), style=Stroke(stroke)); line(.55f,.15f,.35f,.52f); line(.35f,.52f,.62f,.52f); line(.62f,.52f,.44f,.85f) }
            "export" -> { line(.5f,.1f,.5f,.6f); line(.3f,.4f,.5f,.6f); line(.7f,.4f,.5f,.6f); line(.15f,.6f,.15f,.85f); line(.15f,.85f,.85f,.85f); line(.85f,.85f,.85f,.6f) }
            "leaf" -> { drawOval(color, Offset(w*.2f,h*.05f), Size(w*.65f,h*.75f), style=Stroke(stroke)); line(.2f,.9f,.65f,.35f) }
            "shield" -> { drawRoundRect(color, Offset(w*.18f,h*.1f), Size(w*.64f,h*.8f), CornerRadius(w*.28f), style=Stroke(stroke)); line(.32f,.5f,.45f,.63f); line(.45f,.63f,.7f,.36f) }
            else -> { drawRoundRect(color, Offset(w*.18f,h*.08f), Size(w*.65f,h*.84f), CornerRadius(w*.12f), style=Stroke(stroke)); line(.32f,.33f,.68f,.33f); line(.32f,.52f,.68f,.52f); line(.32f,.71f,.52f,.71f) }
        }
    }
}

private fun sourceLabel(source: EntrySource) = when (source) { EntrySource.MANUAL -> "手动"; EntrySource.WECHAT -> "微信"; EntrySource.ALIPAY -> "支付宝" }
private fun businessLabel(kind: BusinessType) = when (kind) {
    BusinessType.EXPENSE -> "支出"; BusinessType.INCOME -> "收入"; BusinessType.TRANSFER -> "转账"
    BusinessType.REFUND -> "退款"; BusinessType.REPAYMENT -> "还款"; BusinessType.UNKNOWN -> "待核对"
}
private fun evidenceLabel(kind: EvidenceType) = when (kind) {
    EvidenceType.NOTIFICATION -> "通知"; EvidenceType.ACCESSIBILITY -> "页面文字"
    EvidenceType.OCR -> "本地截图识别"; EvidenceType.IMPORTED_IMAGE -> "导入图片"
    EvidenceType.SHARED_IMAGE -> "用户分享图片"; EvidenceType.LEGACY -> "旧版识别来源"
}
private fun dateText(time: Long, format: String) = SimpleDateFormat(format, Locale.CHINA).format(Date(time))
private fun monthStart(time: Long) = Calendar.getInstance().apply {
    timeInMillis = time; set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis
private fun shiftMonth(time: Long, offset: Int) = Calendar.getInstance().apply { timeInMillis = time; add(Calendar.MONTH, offset) }.timeInMillis

internal fun loadPaymentScreenshot(context: Context, uri: Uri): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val probe = context.contentResolver.openInputStream(uri) ?: error("无法读取所选截图")
    probe.use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "文件不是可读取的图片" }
    var sample = 1
    while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > 6_000_000 ||
        maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
    return context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
    } ?: error("截图解码失败")
}
