package com.example.jizhang.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Platform capabilities, rather than a manufacturer whitelist; no persistent device identifier. */
@Composable
internal fun DeviceCompatibilityCard(
    onBackgroundSettings: () -> Unit,
    manufacturer: String = Build.MANUFACTURER,
    model: String = Build.MODEL,
    sdk: Int = Build.VERSION.SDK_INT,
    lowRam: Boolean = (LocalContext.current.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true,
) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("本机兼容与后台运行", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${deviceLabel(manufacturer)} · ${deviceLabel(model)} · API $sdk",
                modifier = Modifier.testTag("device-platform"), style = MaterialTheme.typography.bodySmall)
            Text(when {
                sdk < 24 -> "需要 Android 7.0 或更高版本。"
                sdk < 30 -> "Android 7–10：支持交易页文字识别与主动导入截图；系统未提供自动截图接口。"
                sdk < 34 -> "Android 11–13：可尝试整屏 OCR，需裁剪并检查交易窗口；浮窗、输入法或其他应用可能使识别暂停。"
                else -> "Android 14 及以上：可尝试交易窗口截图；系统隐藏的敏感文字与受保护页面仍可能无法识别。"
            }, modifier = Modifier.testTag("device-screen-capability"), style = MaterialTheme.typography.bodySmall)
            if (lowRam && sdk in 24..29) Text(
                "本机属于 Android 7–10 低内存设备，系统不支持通知监听服务。可使用交易页文字识别、主动导入或手动记账。",
                modifier = Modifier.testTag("device-low-ram-limit"), style = MaterialTheme.typography.bodySmall)
            Text("能力按系统版本判断。不同品牌的后台策略、支付应用页面与系统权限会影响识别；已授权后仍需检查服务是否已连接。",
                style = MaterialTheme.typography.bodySmall)
            Text("若服务经常断开，可在系统中检查轻记的后台运行与电池限制。设置名称随品牌而不同，是否允许由你决定。",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onBackgroundSettings,
                modifier = Modifier.fillMaxWidth().testTag("device-background-settings")) {
                Text("管理后台运行")
            }
        }
    }
}

private fun deviceLabel(value: String): String = value.trim().take(80).takeUnless { it.isEmpty() || it.equals("unknown", ignoreCase = true) } ?: "未知"
