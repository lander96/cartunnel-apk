package com.cartunnel.client.vpn

sealed class TunnelRuntimeState {
    data object Stopped : TunnelRuntimeState()
    data object PermissionRequired : TunnelRuntimeState()
    data class Starting(val step: String) : TunnelRuntimeState()
    data class Connected(
        val profileId: String,
        val since: Long,
        val latencyMs: Long? = null,
    ) : TunnelRuntimeState()
    data class Reconnecting(val attempt: Int, val reason: String) : TunnelRuntimeState()
    data object Stopping : TunnelRuntimeState()
    data class Error(val code: String, val safeMessage: String, val recoverable: Boolean) : TunnelRuntimeState()
}

sealed class TunnelCommand {
    data class Start(val profileId: String, val requestToken: Int = 0) : TunnelCommand()
    data class Stop(val requestToken: Int = 0) : TunnelCommand()
    data class NetworkChanged(val id: String?) : TunnelCommand()
    data object WakeResume : TunnelCommand()
    data object VpnRevoked : TunnelCommand()
}

data class TunnelSettings(
    val currentProfileId: String? = null,
    val desiredRunning: Boolean = false,
    val autoConnect: Boolean = false,
    val autoReconnect: Boolean = true,
    val reconnectOnNetworkChange: Boolean = true,
    val reconnectOnWake: Boolean = true,
    val bypassLan: Boolean = false,
)
