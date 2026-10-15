package com.example.jizhang.automation

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

object ScreenAccess {
    /** Unknown or inaccessible secure settings must never be treated as permission. */
    fun isGranted(context: Context): Boolean = readGranted(context) == true

    /**
     * Reads only whether this app's own service appears in the system result. The returned
     * value contains no names, identifiers, or configuration of other accessibility services.
     * A system-list match is a diagnostic hint; only our service callback proves a connection.
     */
    fun readConnectionDiagnostics(
        context: Context,
        localConnected: Boolean,
    ): ScreenConnectionDiagnostics = ScreenConnectionDiagnostics(
        granted = readGranted(context),
        systemBound = readSystemBound(context),
        localConnected = localConnected,
    )

    private fun readGranted(context: Context): Boolean? = try {
        if (Settings.Secure.getInt(
                context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0,
            ) != 1
        ) {
            false
        } else {
            val component = ComponentName(context, ScreenCaptureService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            enabled?.splitToSequence(':')?.any {
                ComponentName.unflattenFromString(it) == component
            } == true
        }
    } catch (_: RuntimeException) {
        // Vendor policy and restricted settings can reject reads even on a settings page.
        null
    }

    private fun readSystemBound(context: Context): Boolean? = try {
        val component = ComponentName(context, ScreenCaptureService::class.java)
        context.getSystemService(AccessibilityManager::class.java)
            ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { service ->
                val ownService = service.resolveInfo?.serviceInfo
                ownService != null &&
                    ComponentName(ownService.packageName, ownService.name) == component
            }
    } catch (_: RuntimeException) {
        // Unavailable is distinct from a successful query with no match.
        null
    }

    /** Uses the public general settings screen; package visibility filtering cannot hide it. */
    @Suppress("UNUSED_PARAMETER")
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
