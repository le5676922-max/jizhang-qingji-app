package com.example.jizhang.automation

/** No Android objects or third-party service details cross this diagnostic boundary. */
data class ScreenConnectionDiagnostics(
    /** null means the system rejected or could not complete the authorization query. */
    val granted: Boolean?,
    /** null means the enabled-service query was unavailable, rather than an absent service. */
    val systemBound: Boolean?,
    /** Supplied exclusively from this app's onServiceConnected/disconnection state. */
    val localConnected: Boolean,
) {
    val state: ScreenConnectionState
        get() = when {
            granted == false -> ScreenConnectionState.NOT_AUTHORIZED
            granted == null -> ScreenConnectionState.QUERY_UNAVAILABLE
            localConnected -> ScreenConnectionState.CONNECTED
            systemBound == null -> ScreenConnectionState.QUERY_UNAVAILABLE
            systemBound -> ScreenConnectionState.SYSTEM_BOUND_WAITING_CALLBACK
            else -> ScreenConnectionState.AUTHORIZED_NOT_BOUND
        }

    val isConnected: Boolean get() = state == ScreenConnectionState.CONNECTED
}

enum class ScreenConnectionState {
    NOT_AUTHORIZED,
    AUTHORIZED_NOT_BOUND,
    SYSTEM_BOUND_WAITING_CALLBACK,
    CONNECTED,
    QUERY_UNAVAILABLE,
}
