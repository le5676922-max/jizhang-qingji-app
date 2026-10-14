package com.example.jizhang.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LedgerColors = lightColorScheme(
    primary = Color(0xFF345A49), onPrimary = Color.White,
    primaryContainer = Color(0xFFE2EBE1), onPrimaryContainer = Color(0xFF244232),
    secondary = Color(0xFF6B7A63), secondaryContainer = Color(0xFFEEF1E7),
    background = Color(0xFFF6F7F2), onBackground = Color(0xFF202E26),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF202E26),
    surfaceVariant = Color(0xFFF0F2EB), onSurfaceVariant = Color(0xFF727C72),
    outline = Color(0xFFA4ADA0), outlineVariant = Color(0xFFE3E7DE),
    error = Color(0xFFB65D46)
)

@Composable
fun JizhangTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LedgerColors, typography = Typography, content = content)
}
