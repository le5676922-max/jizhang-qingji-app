package com.example.jizhang.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import com.example.jizhang.automation.ScreenCaptureStatus
import com.example.jizhang.data.LedgerSettings
import com.example.jizhang.domain.EntrySource

@Composable
internal fun ScreenControls(settings: LedgerSettings, granted: Boolean, connected: Boolean, status: ScreenCaptureStatus,
                            importing: Boolean, onSettings: (LedgerSettings) -> Unit, onAccess: () -> Unit,
                            onImport: (EntrySource) -> Unit, onReview: () -> Unit, onAppInfo: () -> Unit = {}, enabled: Boolean = true,
                            sdk: Int = Build.VERSION.SDK_INT) {
    var disclosure by remember { mutableStateOf(false) }
    var ocrDisclosure by remember { mutableStateOf(false) }
    var sourcePicker by remember { mutableStateOf(false) }
    Surface(color = Color.White, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("屏幕记账", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text("转账、红包没有通知时，读取已打开的交易结果页。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 20.sp)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("识别交易结果页", fontSize = 14.sp)
                    Text(when { !settings.screenEnabled -> "默认关闭，开启后仅处理所选支付应用"; !granted -> "需要在系统设置中开启无障碍服务"; !connected -> "已授权，等待系统连接"; else -> "已连接 · 请在支付后停留于结果页" },
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
                }
                Switch(settings.screenEnabled, enabled = enabled, modifier = Modifier.testTag("screen-enabled"), onCheckedChange = {
                    if (it) disclosure = true else onSettings(settings.copy(screenEnabled = false))
                })
            }
            OutlinedButton(onClick = onAccess, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Text(if (granted) "管理无障碍服务" else "开启无障碍服务")
            }
            if (!granted) {
                Text("系统若显示“由受限设置控制”，请打开轻记的应用信息，查找“允许受限设置”（部分系统位于右上角 ⋮ 菜单），再回到无障碍页面授权。入口名称随系统而不同，只有系统设置能解除该限制。", fontSize = 12.sp, lineHeight = 20.sp)
                TextButton(onClick = onAppInfo, modifier = Modifier.testTag("screen-app-info")) { Text("打开轻记应用信息") }
            } else if (!connected) {
                Text("权限已经授权，服务尚未连接。可在系统设置关闭后重新开启；检查轻记是否被限制后台运行。", fontSize = 12.sp, lineHeight = 20.sp)
            }
            HorizontalDivider()
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("结果页截图识别（可选）", fontSize = 14.sp)
                    Text("可读取页面文字时优先读文字；截图结果先待确认。", fontSize = 11.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(settings.screenOcrEnabled, enabled = enabled && sdk >= 30, modifier = Modifier.testTag("screen-ocr-enabled"), onCheckedChange = {
                    if (it) ocrDisclosure = true else onSettings(settings.copy(screenOcrEnabled = false))
                })
            }
            Text(when {
                sdk < 30 -> "本机 Android 版本支持页面文字与手动截图导入，不支持自动截屏。"
                sdk >= 34 -> "本机支持结果窗口截图；Android 可能隐藏敏感文字，受保护页面仍不可识别。"
                else -> "本机提供整屏截图；轻记裁剪交易窗口并检查浮窗，安全窗口与密码页面不会截图。"
            }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
            if (settings.screenEnabled && status.lastCheckedAt > 0) {
                Text(status.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, lineHeight = 20.sp)
            }
            Text("只读取所选应用的结果页，不执行点击或转账。屏幕与通知可能采集同一笔时会提示核对，不直接重复计入。", fontSize = 11.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onReview, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("查看待确认账单") }
            OutlinedButton(onClick = { sourcePicker = true }, enabled = enabled && !importing, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Text(if (importing) "正在识别截图…" else "导入支付截图识别")
            }
            Text("若系统隐藏文字或禁止截图，自动识别可能不可用。可在允许截图的结果页手动截图后导入；不保存原图或完整页面文字。", fontSize = 11.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (disclosure) AlertDialog(onDismissRequest = { disclosure = false }, title = { Text("开启屏幕记账") },
        text = { Text("无障碍权限允许读取应用界面。轻记仅处理你选择的微信、支付宝交易结果页，提取金额、方向、交易时间和对方信息保存到本机。\n\n不读取密码输入，不执行点击或支付。不确定的结果保存为待确认，可随时关闭。") },
        confirmButton = { TextButton(onClick = { disclosure = false; onSettings(settings.copy(screenEnabled = true)); if (!granted) onAccess() }, enabled = enabled) { Text("同意并开启") } },
        dismissButton = { TextButton(onClick = { disclosure = false }) { Text("取消") } })
    if (ocrDisclosure) AlertDialog(onDismissRequest = { ocrDisclosure = false }, title = { Text("允许结果页截图识别") },
        text = { Text("仅在所选支付应用的已识别交易结果页尝试截取画面，用设备端中文 OCR 识别。图片和完整文字不保存到账本，识别结果需你核对后计入统计。\n\n系统禁止截图的页面不会识别。关闭屏幕记账后，也会停止自动截图。" +
            if (sdk in 30..33) "\n\n本机系统提供整屏截图。轻记会裁剪交易窗口并重复检查浮窗，但无法保证排除瞬间弹出的其他内容。可保留页面文字识别，或只导入你选择的交易图片。" else "") },
        confirmButton = { TextButton(onClick = { ocrDisclosure = false; onSettings(settings.copy(screenOcrEnabled = true)) }, enabled = enabled && sdk >= 30) { Text("同意开启") } },
        dismissButton = { TextButton(onClick = { ocrDisclosure = false }) { Text("取消") } })
    if (sourcePicker) AlertDialog(onDismissRequest = { sourcePicker = false }, title = { Text("支付截图来自哪里？") },
        text = { Text("请选择转账、红包或付款结果截图。文字识别仅在设备上处理，账单先保存为待确认。") },
        confirmButton = { TextButton(onClick = { sourcePicker = false; onImport(EntrySource.WECHAT) }, enabled = enabled) { Text("微信") } },
        dismissButton = { TextButton(onClick = { sourcePicker = false; onImport(EntrySource.ALIPAY) }, enabled = enabled) { Text("支付宝") } })
}
