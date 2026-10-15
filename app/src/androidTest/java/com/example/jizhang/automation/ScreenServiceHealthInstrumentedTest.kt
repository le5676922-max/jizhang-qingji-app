package com.example.jizhang.automation

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenServiceHealthInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun persistedConnectedHistoryCannotBecomeALiveCaptureConnection() {
        val name = "screen-health-test-${UUID.randomUUID()}"
        try {
            val store = ScreenServiceHealthStore(context, name)
            store.record(ScreenServicePhase.CREATED)
            val connected = store.record(ScreenServicePhase.CONNECTED, configurationWarning = false)
            assertTrue(connected.lastConnectedAt > 0)
            store.record(ScreenServicePhase.UNBOUND)
            val restored = ScreenServiceHealthStore(context, name).read()
            assertEquals(ScreenServicePhase.UNBOUND, restored.phase)
            assertEquals(connected.lastConnectedAt, restored.lastConnectedAt)
            assertFalse(ScreenConnectionDiagnostics(granted = true, systemBound = true, localConnected = false).isConnected)
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun inaccessibleHistoryDoesNotCrashOrInventALastConnection() {
        val denied = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = throw SecurityException("Synthetic denied access")
        }
        val value = ScreenServiceHealthStore(denied).read()
        assertFalse(value.readable)
        assertEquals(0L, value.lastConnectedAt)
        assertEquals(ScreenServicePhase.NOT_STARTED, value.phase)
        val recorded = ScreenServiceHealthStore(denied).record(ScreenServicePhase.CREATED)
        assertFalse(recorded.readable)
        assertEquals(0L, recorded.lastConnectedAt)
    }

    @Test fun publishedServiceKeepsSystemSignaturePermissionWhenExported() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getServiceInfo(ComponentName(context, ScreenCaptureService::class.java), 0)
        assertTrue(info.exported)
        assertTrue(info.enabled)
        assertEquals(Manifest.permission.BIND_ACCESSIBILITY_SERVICE, info.permission)
        assertEquals(context.applicationInfo.processName, info.processName)
    }
}
