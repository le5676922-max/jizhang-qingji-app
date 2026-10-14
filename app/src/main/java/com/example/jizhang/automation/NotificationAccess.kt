package com.example.jizhang.automation

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

object NotificationAccess {
    fun isGranted(context: Context): Boolean {
        val component = ComponentName(context, PaymentNotificationListener::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val manager = context.getSystemService(NotificationManager::class.java)
            return manager?.isNotificationListenerAccessGranted(component) == true
        }
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return enabled?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true
    }

    fun settingsIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(context, PaymentNotificationListener::class.java).flattenToString(),
                )
            if (detail.resolveActivity(context.packageManager) != null) return detail
        }
        val general = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        if (general.resolveActivity(context.packageManager) != null) return general
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    }
}
