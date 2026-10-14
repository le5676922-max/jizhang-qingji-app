package com.example.jizhang.automation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

object ScreenAccess {
    fun isGranted(context: Context): Boolean {
        if (Settings.Secure.getInt(
                context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0,
            ) != 1
        ) return false
        val component = ComponentName(context, ScreenCaptureService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        return enabled?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true
    }

    /** Uses the public general settings screen; package visibility filtering cannot hide it. */
    @Suppress("UNUSED_PARAMETER")
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
