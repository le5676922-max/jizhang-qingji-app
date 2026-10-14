package com.example.jizhang.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.example.jizhang.automation.ScreenCaptureStatus
import com.example.jizhang.data.LedgerSettings
import com.example.jizhang.domain.EntrySource

@Composable
internal fun ScreenControls(settings: LedgerSettings, granted: Boolean, connected: Boolean, status: ScreenCaptureStatus,
                            importing: Boolean, onSettings: (LedgerSettings) -> Unit, onAccess: () -> Unit,
                            onImport: (EntrySource) -> Unit, onReview: () -> Unit) {
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
                Switch(settings.screenEnabled, onCheckedChange = {
                    if (it) disclosure = true else onSettings(settings.copy(screenEnabled = false))
                })
            }
            OutlinedButton(onClick = onAccess, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Text(if (granted) "管理无障碍服务" else "开启无障碍服务")
            }
            HorizontalDivider()
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("结果页截图识别（可选）", fontSize = 14.sp)
                    Text("可读取页面文字时优先读文字；截图结果先待确认。", fontSize = 11.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(settings.screenOcrEnabled, onCheckedChange = {
                    if (it) ocrDisclosure = true else onSettings(settings.copy(screenOcrEnabled = false))
                })
            }
            if (settings.screenEnabled && status.lastCheckedAt > 0) {
                Text(status.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, lineHeight = 20.sp)
            }
            Text("只读取所选应用的结果页，不执行点击或转账。屏幕与通知可能采集同一笔时会提示核对，不直接重复计入。", fontSize = 11.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onReview, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("查看待确认账单") }
            OutlinedButton(onClick = { sourcePicker = true }, enabled = !importing, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Text(if (importing) "正在识别截图…" else "导入支付截图识别")
            }
            Text("若系统隐藏文字或禁止截图，自动识别可能不可用。可在允许截图的结果页手动截图后导入；不保存原图或完整页面文字。", fontSize = 11.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (disclosure) AlertDialog(onDismissRequest = { disclosure = false }, title = { Text("开启屏幕记账") },
        text = { Text("无障碍权限允许读取应用界面。轻记仅处理你选择的微信、支付宝交易结果页，提取金额、方向、交易时间和对方信息保存到本机。\n\n不读取密码输入，不执行点击或支付。不确定的结果保存为待确认，可随时关闭。") },
        confirmButton = { TextButton(onClick = { disclosure = false; onSettings(settings.copy(screenEnabled = true)); if (!granted) onAccess() }) { Text("同意并开启") } },
        dismissButton = { TextButton(onClick = { disclosure = false }) { Text("取消") } })
    if (ocrDisclosure) AlertDialog(onDismissRequest = { ocrDisclosure = false }, title = { Text("允许结果页截图识别") },
        text = { Text("仅在所选支付应用的已识别交易结果页尝试截取画面，用设备端中文 OCR 识别。图片和完整文字不保存到账本，识别结果需你核对后计入统计。\n\n系统禁止截图的页面不会识别。关闭屏幕记账后，也会停止自动截图。") },
        confirmButton = { TextButton(onClick = { ocrDisclosure = false; onSettings(settings.copy(screenOcrEnabled = true)) }) { Text("同意开启") } },
        dismissButton = { TextButton(onClick = { ocrDisclosure = false }) { Text("取消") } })
    if (sourcePicker) AlertDialog(onDismissRequest = { sourcePicker = false }, title = { Text("支付截图来自哪里？") },
        text = { Text("请选择转账、红包或付款结果截图。文字识别仅在设备上处理，账单先保存为待确认。") },
        confirmButton = { TextButton(onClick = { sourcePicker = false; onImport(EntrySource.WECHAT) }) { Text("微信") } },
        dismissButton = { TextButton(onClick = { sourcePicker = false; onImport(EntrySource.ALIPAY) }) { Text("支付宝") } })
}
