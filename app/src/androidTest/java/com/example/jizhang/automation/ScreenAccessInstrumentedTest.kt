package com.example.jizhang.automation

import android.content.ContentResolver
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only: never enables a service, changes secure settings, or reads another app's page. */
@RunWith(AndroidJUnit4::class)
class ScreenAccessInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun systemQueryCannotClaimAConnectionWithoutOurCallback() {
        val diagnostics = ScreenAccess.readConnectionDiagnostics(context, localConnected = false)
        assertFalse(diagnostics.isConnected)
        assertEquals(false, diagnostics.localConnected)
        if (diagnostics.granted == true && diagnostics.systemBound != null) {
            assertEquals(
                if (diagnostics.systemBound) ScreenConnectionState.SYSTEM_BOUND_WAITING_CALLBACK
                else ScreenConnectionState.AUTHORIZED_NOT_BOUND,
                diagnostics.state,
            )
        }
    }

    @Test fun rejectedSecureSettingsReadIsUnknownAndDoesNotCrashPermissionCheck() {
        val rejected = object : ContextWrapper(context) {
            override fun getContentResolver(): ContentResolver = throw SecurityException("Synthetic policy")
        }
        val diagnostics = ScreenAccess.readConnectionDiagnostics(rejected, localConnected = true)
        assertNull(diagnostics.granted)
        assertEquals(ScreenConnectionState.QUERY_UNAVAILABLE, diagnostics.state)
        assertFalse(ScreenAccess.isGranted(rejected))
    }

    @Test fun unavailableAccessibilityManagerIsUnknownRatherThanAnAbsentBinding() {
        val unavailable = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? = throw IllegalStateException("Synthetic unavailable service")
        }
        val diagnostics = ScreenAccess.readConnectionDiagnostics(unavailable, localConnected = false)
        assertNull(diagnostics.systemBound)
        assertFalse(diagnostics.isConnected)
        if (diagnostics.granted == true) {
            assertEquals(ScreenConnectionState.QUERY_UNAVAILABLE, diagnostics.state)
        }
    }
}
