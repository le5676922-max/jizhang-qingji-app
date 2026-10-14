package com.example.jizhang.ui

import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.jizhang.automation.NotificationAccess
import com.example.jizhang.automation.PaymentNotificationListener
import com.example.jizhang.automation.ScreenAccess
import com.example.jizhang.automation.ScreenCaptureService
import com.example.jizhang.automation.ScreenCaptureStatus
import com.example.jizhang.automation.LocalTextRecognizer
import com.example.jizhang.data.LedgerRepository
import com.example.jizhang.data.LedgerSettings
import com.example.jizhang.data.ScreenSaveResult
import com.example.jizhang.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
    val listenerConnected by PaymentNotificationListener.connectionState.collectAsState()
    val captureError by repository.captureError.collectAsState()
    val screenConnected by ScreenCaptureService.connectionState.collectAsState()
    val screenStatus by ScreenCaptureService.status.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var billFilter by rememberSaveable { mutableIntStateOf(0) }
    var month by rememberSaveable { mutableLongStateOf(monthStart(System.currentTimeMillis())) }
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LedgerEntry?>(null) }
    var budgetOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(NotificationAccess.isGranted(context)) }
    var screenGranted by remember { mutableStateOf(ScreenAccess.isGranted(context)) }
    var importingScreenshot by remember { mutableStateOf(false) }
    var screenshotSource by remember { mutableStateOf(EntrySource.WECHAT) }
    val lifecycleOwner = context as androidx.activity.ComponentActivity
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = NotificationAccess.isGranted(context)
                screenGranted = ScreenAccess.isGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val screenshotPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            importingScreenshot = true
            val result = runCatching {
                val bitmap = withContext(Dispatchers.IO) { loadPaymentScreenshot(context, uri) }
                val text = try { LocalTextRecognizer.recognize(bitmap) } finally { bitmap.recycle() }
                val pkg = if (screenshotSource == EntrySource.WECHAT) "com.tencent.mm" else "com.eg.android.AlipayGphone"
                val candidate = ScreenPaymentParser.parse(pkg, text, fromOcr = true) ?: error("没有找到明确的交易结果、方向和金额")
                val saved = repository.addScreenPayment(candidate, System.currentTimeMillis(), userInitiated = true)
                if (saved != ScreenSaveResult.DUPLICATE && saved != ScreenSaveResult.SOURCE_DISABLED) {
                    month = monthStart(candidate.occurredAt ?: System.currentTimeMillis()); billFilter = 3; tab = 1
                }
                saved
            }
            importingScreenshot = false
            result.onSuccess { saved -> snackbar.showSnackbar(if (saved == ScreenSaveResult.DUPLICATE) "这笔交易已经识别过" else "已保存为待确认，请核对金额后保存") }
                .onFailure { snackbar.showSnackbar(it.message ?: "截图识别失败，请选择清晰的支付结果截图") }
        }
    }
    val openReview: () -> Unit = {
        entries.firstOrNull { it.needsReview }?.let { month = monthStart(it.occurredAt) }
        billFilter = 3; tab = 1
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
    val monthEntries = remember(entries, month) {
        val end = shiftMonth(month, 1)
        entries.filter { it.occurredAt >= month && it.occurredAt < end }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (tab != 2) FloatingActionButton(
                onClick = { editing = null; editorOpen = true },
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
                when (tab) {
                    0 -> Overview(monthEntries, month, settings, granted, listenerConnected, screenGranted && screenConnected, entries.count { it.needsReview },
                        onMonth = { month = shiftMonth(month, it) }, onBudget = { budgetOpen = true },
                        onAll = { billFilter = 0; tab = 1 }, onReview = openReview, onAutomation = { tab = 2 }, onEntry = { editing = it; editorOpen = true })
                    1 -> Bills(monthEntries, month, billFilter, onFilter = { billFilter = it }, onMonth = { month = shiftMonth(month, it) }, onEntry = { editing = it; editorOpen = true },
                        onExport = { exportLauncher.launch("轻记账单-${dateText(System.currentTimeMillis(), "yyyyMMdd")}.csv") })
                    2 -> Automation(settings, granted, listenerConnected, captureError, screenGranted, screenConnected, screenStatus, importingScreenshot,
                        onScreenAccess = { runCatching { context.startActivity(ScreenAccess.settingsIntent(context)) }.onFailure { scope.launch { snackbar.showSnackbar("请在系统无障碍设置中开启“轻记 · 屏幕记账”") } } },
                        onImportScreenshot = { screenshotSource = it; screenshotPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onReview = openReview, onAcknowledge = repository::acknowledgeCaptureError, onSettings = repository::updateSettings, onAccess = {
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
    if (editorOpen) EntryEditor(editing, saving, onDismiss = { if (!saving) editorOpen = false }, onSave = { entry ->
        saving = true
        scope.launch {
            val result = runCatching { if (entry.id == 0L) repository.add(entry) else { repository.update(entry); true } }
            saving = false
            result
                .onSuccess { editorOpen = false; snackbar.showSnackbar(if (editing?.needsReview == true) "账单已确认" else "账单已保存") }
                .onFailure { snackbar.showSnackbar("保存失败，请稍后再试") }
        }
    }, onDelete = { id -> saving = true; scope.launch {
        val result = runCatching { repository.delete(id) }
        saving = false
        result.onSuccess { editorOpen = false; snackbar.showSnackbar("账单已删除") }
            .onFailure { snackbar.showSnackbar("删除失败，请稍后再试") }
    } })
    if (budgetOpen) BudgetEditor(settings.monthlyBudgetCents, onDismiss = { budgetOpen = false }, onSave = {
        repository.updateSettings(settings.copy(monthlyBudgetCents = it)); budgetOpen = false
    })
}

@Composable
private fun Overview(entries: List<LedgerEntry>, month: Long, settings: LedgerSettings, granted: Boolean, connected: Boolean, screenConnected: Boolean, reviewCount: Int,
                     onMonth: (Int) -> Unit, onBudget: () -> Unit, onAll: () -> Unit, onReview: () -> Unit, onAutomation: () -> Unit, onEntry: (LedgerEntry) -> Unit) {
    val confirmed = entries.filterNot { it.needsReview }
    val expense = confirmed.filter { it.type == EntryType.EXPENSE }.sumOf { it.amountCents }
    val income = confirmed.filter { it.type == EntryType.INCOME }.sumOf { it.amountCents }
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
                        Column(Modifier.weight(1f)) { Text("本月收入", color = Color(0xFFD0DDD0), fontSize = 11.sp); Text("¥ ${Money.format(income)}", color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(top = 6.dp)) }
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
            val categories = confirmed.filter { it.type == EntryType.EXPENSE }.groupBy { it.category }.mapValues { (_, rows) -> rows.sumOf { it.amountCents } }.toList().sortedByDescending { it.second }
            Panel {
                Text("钱花在哪里", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                if (categories.isEmpty()) Text("记录第一笔支出后，这里会显示分类统计。", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 18.dp))
                categories.take(5).forEach { (category, amount) ->
                    Row(Modifier.fillMaxWidth().padding(top = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(category, color = Ink, fontSize = 13.sp, modifier = Modifier.width(44.dp))
                        Box(Modifier.weight(1f).padding(horizontal = 12.dp).height(6.dp).clip(CircleShape).background(Pale)) {
                            Box(Modifier.fillMaxWidth((amount.toFloat() / expense).coerceIn(0f, 1f)).fillMaxHeight().background(Green.copy(alpha = .75f)))
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
private fun Bills(entries: List<LedgerEntry>, month: Long, filter: Int, onFilter: (Int) -> Unit, onMonth: (Int) -> Unit, onEntry: (LedgerEntry) -> Unit, onExport: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = entries.filter { entry ->
        (filter == 0 || filter == 1 && entry.type == EntryType.EXPENSE || filter == 2 && entry.type == EntryType.INCOME || filter == 3 && entry.needsReview) &&
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
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "支出", "收入", "待确认").forEachIndexed { index, label ->
                FilterChip(selected = filter == index, onClick = { onFilter(index) }, label = { Text(label, fontSize = 12.sp) })
            }
        }
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
                       onScreenAccess: () -> Unit, onImportScreenshot: (EntrySource) -> Unit, onReview: () -> Unit,
                       onAcknowledge: () -> Unit, onSettings: (LedgerSettings) -> Unit, onAccess: () -> Unit) {
    var testOpen by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column { Text("自动记账", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Ink); Text("少一点操作，多一点生活", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp)) }
        ScreenControls(settings, screenGranted, screenConnected, screenStatus, importing, onSettings, onScreenAccess, onImportScreenshot, onReview)
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
                Switch(checked = settings.autoEnabled, onCheckedChange = { onSettings(settings.copy(autoEnabled = it)) })
            }
            HorizontalDivider(Modifier.padding(vertical = 15.dp), color = Pale)
            SourceToggle("微信支付", "付款、收款到账通知", settings.wechatEnabled, "微") { onSettings(settings.copy(wechatEnabled = it)) }
            Spacer(Modifier.height(14.dp))
            SourceToggle("支付宝", "付款成功、到账与退款通知", settings.alipayEnabled, "支") { onSettings(settings.copy(alipayEnabled = it)) }
        }
        Panel {
            Text("三步，让它开始工作", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            SetupStep("01", "授权通知访问", "在系统通知使用权页面允许“轻记”。")
            SetupStep("02", "打开自动记录开关", "只识别所选来源，忽略聊天和非交易通知。")
            SetupStep("03", "收到下一条交易通知", "明确账单自动保存；模糊账单如被识别，会标为待确认。")
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
private fun SourceToggle(title: String, subtitle: String, checked: Boolean, initial: String, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(if (initial == "微") Pale else Color(0xFFEAF0F5)), contentAlignment = Alignment.Center) {
            Text(initial, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (initial == "微") Green else Color(0xFF4D799A))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(title, color = Ink, fontSize = 14.sp); Text(subtitle, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
        Switch(checked = checked, onCheckedChange = onChecked)
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
    Surface(onClick = { onClick(entry) }, color = Color.White, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(if (entry.type == EntryType.INCOME) Pale else Color(0xFFF3EBE0)), contentAlignment = Alignment.Center) {
                Text(entry.category.take(1), color = if (entry.type == EntryType.INCOME) Green else Orange, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(entry.merchant.ifBlank { entry.category }, color = Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${entry.category} · ${sourceLabel(entry.source)} · ${dateText(entry.occurredAt, "HH:mm")}", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp), maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${if (entry.type == EntryType.EXPENSE) "−" else "+"}${Money.format(entry.amountCents)}", color = if (entry.type == EntryType.EXPENSE) Ink else Green, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                if (entry.needsReview) Text("待确认", color = Orange, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun EntryEditor(entry: LedgerEntry?, saving: Boolean, onDismiss: () -> Unit, onSave: (LedgerEntry) -> Unit, onDelete: (Long) -> Unit) {
    val context = LocalContext.current
    var type by remember { mutableStateOf(entry?.type ?: EntryType.EXPENSE) }
    var amount by remember { mutableStateOf(entry?.let { Money.format(it.amountCents) } ?: "") }
    var merchant by remember { mutableStateOf(entry?.merchant ?: "") }
    var category by remember { mutableStateOf(entry?.category ?: "餐饮") }
    var note by remember { mutableStateOf(entry?.note ?: "") }
    var occurredAt by remember { mutableLongStateOf(entry?.occurredAt ?: System.currentTimeMillis()) }
    var error by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = Round, color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (entry == null) "记一笔" else if (entry.needsReview) "确认账单" else "编辑账单", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f)); TextButton(onClick = onDismiss, contentPadding = PaddingValues(0.dp)) { Text("关闭") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(EntryType.EXPENSE to "支出", EntryType.INCOME to "收入").forEach { (value, label) ->
                        FilterChip(selected = type == value, onClick = { type = value; category = if (value == EntryType.EXPENSE) "餐饮" else "收款" }, label = { Text(label) })
                    }
                }
                OutlinedTextField(amount, onValueChange = { amount = it; error = false }, label = { Text("金额（元）") }, prefix = { Text("¥ ") }, singleLine = true,
                    isError = error, supportingText = { if (error) Text("请输入大于 0 的金额，最多两位小数") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                Text("分类", color = Muted, fontSize = 12.sp)
                val categories = if (type == EntryType.EXPENSE) LedgerCategories.expense else LedgerCategories.income
                categories.chunked(4).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { value -> FilterChip(selected = category == value, onClick = { category = value }, label = { Text(value, fontSize = 11.sp) }) }
                } }
                OutlinedTextField(merchant, onValueChange = { if (it.length <= 120) merchant = it }, label = { Text("商户 / 对方（选填）") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(note, onValueChange = { if (it.length <= 500) note = it }, label = { Text("备注（选填）") }, maxLines = 3, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                TextButton(onClick = {
                    val calendar = Calendar.getInstance().apply { timeInMillis = occurredAt }
                    DatePickerDialog(context, { _, year, month, day ->
                        calendar.set(Calendar.YEAR, year); calendar.set(Calendar.MONTH, month); calendar.set(Calendar.DAY_OF_MONTH, day)
                        occurredAt = calendar.timeInMillis
                    }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
                }) { Text("日期  ${dateText(occurredAt, "yyyy年MM月dd日")}  ›") }
                if (entry != null && entry.source != EntrySource.MANUAL) Text("来源：${sourceLabel(entry.source)} · 保存后计入统计", color = Muted, fontSize = 11.sp)
                Button(onClick = {
                    val cents = Money.parseCents(amount)
                    if (cents == null) error = true else onSave(LedgerEntry(id = entry?.id ?: 0L, amountCents = cents, type = type, category = category,
                        merchant = merchant.trim(), note = note.trim(), occurredAt = occurredAt, source = entry?.source ?: EntrySource.MANUAL, fingerprint = entry?.fingerprint, needsReview = false))
                }, enabled = !saving, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text(if (saving) "保存中…" else if (entry?.needsReview == true) "确认并保存" else "保存账单") }
                if (entry != null) TextButton(onClick = { deleting = true }, enabled = !saving, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("删除这笔账单", color = Orange) }
            }
        }
    }
    if (deleting && entry != null) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除这笔账单？") }, text = { Text("删除后金额会从统计中移除，此操作无法撤销。") },
        confirmButton = { TextButton(onClick = { deleting = false; onDelete(entry.id) }) { Text("删除", color = Orange) } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
}

@Composable
private fun BudgetEditor(budget: Long, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    var value by remember { mutableStateOf(Money.format(budget)) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("设置月度预算") }, text = {
        OutlinedTextField(value, onValueChange = { value = it; error = false }, label = { Text("每月支出上限（元）") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = error, supportingText = { if (error) Text("请输入大于 0 的金额") })
    }, confirmButton = { TextButton(onClick = { val cents = Money.parseCents(value); if (cents == null) error = true else onSave(cents) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
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
