package com.example.jizhang.automation

import android.content.Context

/** Fixed lifecycle facts about this app's own service; never a screen or payment log. */
enum class ScreenServicePhase(val label: String) {
    NOT_STARTED("尚无服务启动记录"),
    CREATED("服务已创建，等待系统连接回调"),
    CONNECTED("收到系统连接回调"),
    INTERRUPTED("系统暂时中断服务反馈"),
    UNBOUND("系统已解除服务绑定"),
    DESTROYED("服务已销毁"),
}

data class ScreenServiceHealth(
    val phase: ScreenServicePhase = ScreenServicePhase.NOT_STARTED,
    val changedAt: Long = 0,
    val lastConnectedAt: Long = 0,
    val configurationWarning: Boolean = false,
    val readable: Boolean = true,
)

/** Best-effort history survives process recreation, but never grants live capture access. */
class ScreenServiceHealthStore(context: Context, preferenceName: String = "screen_service_health") {
    private val preferences by lazy { context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE) }

    fun read(): ScreenServiceHealth = try {
        val phase = runCatching { ScreenServicePhase.valueOf(preferences.getString("phase", "NOT_STARTED") ?: "NOT_STARTED") }
            .getOrDefault(ScreenServicePhase.NOT_STARTED)
        ScreenServiceHealth(phase, preferences.getLong("changed_at", 0).coerceAtLeast(0),
            preferences.getLong("last_connected_at", 0).coerceAtLeast(0),
            preferences.getBoolean("configuration_warning", false))
    } catch (_: Exception) {
        ScreenServiceHealth(readable = false)
    }

    fun record(phase: ScreenServicePhase, configurationWarning: Boolean? = null): ScreenServiceHealth {
        val previous = read()
        val now = System.currentTimeMillis()
        val value = ScreenServiceHealth(phase, now,
            if (phase == ScreenServicePhase.CONNECTED) now else previous.lastConnectedAt,
            configurationWarning ?: previous.configurationWarning)
        return try {
            preferences.edit().putString("phase", phase.name).putLong("changed_at", value.changedAt)
                .putLong("last_connected_at", value.lastConnectedAt)
                .putBoolean("configuration_warning", value.configurationWarning).apply()
            value
        } catch (_: Exception) {
            value.copy(readable = false)
        }
    }
}
