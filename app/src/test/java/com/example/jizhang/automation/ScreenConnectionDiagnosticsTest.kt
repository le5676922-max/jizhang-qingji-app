package com.example.jizhang.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenConnectionDiagnosticsTest {
    @Test fun noAuthorizationStaysDisconnectedEvenWithAStaleLocalCallback() {
        listOf(true, false, null).forEach { systemBound ->
            listOf(true, false).forEach { callback ->
                val diagnostics = ScreenConnectionDiagnostics(false, systemBound, callback)
                assertEquals(ScreenConnectionState.NOT_AUTHORIZED, diagnostics.state)
                assertFalse(diagnostics.isConnected)
            }
        }
    }

    @Test fun secureSettingsFailureCannotPretendToBeGrantedOrConnected() {
        listOf(true, false, null).forEach { systemBound ->
            listOf(true, false).forEach { callback ->
                val diagnostics = ScreenConnectionDiagnostics(null, systemBound, callback)
                assertEquals(ScreenConnectionState.QUERY_UNAVAILABLE, diagnostics.state)
                assertFalse(diagnostics.isConnected)
            }
        }
    }

    @Test fun authorizedButNotInSystemListIdentifiesBindingProblem() {
        val diagnostics = ScreenConnectionDiagnostics(true, false, false)
        assertEquals(ScreenConnectionState.AUTHORIZED_NOT_BOUND, diagnostics.state)
        assertFalse(diagnostics.isConnected)
    }

    @Test fun systemListMatchCannotPretendToBeAServiceCallback() {
        val diagnostics = ScreenConnectionDiagnostics(true, true, false)
        assertEquals(ScreenConnectionState.SYSTEM_BOUND_WAITING_CALLBACK, diagnostics.state)
        assertFalse(diagnostics.isConnected)
    }

    @Test fun unavailableSystemListCannotBeReportedAsNotBound() {
        val diagnostics = ScreenConnectionDiagnostics(true, null, false)
        assertEquals(ScreenConnectionState.QUERY_UNAVAILABLE, diagnostics.state)
        assertFalse(diagnostics.isConnected)
    }

    @Test fun actualCallbackAndAuthorizationProveConnectionRegardlessOfListLag() {
        listOf(true, false, null).forEach { systemBound ->
            val diagnostics = ScreenConnectionDiagnostics(true, systemBound, true)
            assertEquals(ScreenConnectionState.CONNECTED, diagnostics.state)
            assertTrue(diagnostics.isConnected)
        }
    }
}
