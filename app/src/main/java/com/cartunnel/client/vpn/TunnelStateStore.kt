package com.cartunnel.client.vpn

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object TunnelSettingsMigration {
    const val CURRENT_SCHEMA = 2

    fun fromLegacy(
        currentProfileId: String?,
        desiredRunning: Boolean,
        mode: String?,
        autoConnect: Boolean,
        bypassLan: Boolean,
        autoReconnect: Boolean = true,
        reconnectOnNetworkChange: Boolean = true,
        reconnectOnWake: Boolean = true,
    ) = TunnelSettings(
        currentProfileId = currentProfileId,
        desiredRunning = desiredRunning && (mode == null || mode == "VPN"),
        autoConnect = autoConnect,
        autoReconnect = autoReconnect,
        reconnectOnNetworkChange = reconnectOnNetworkChange,
        reconnectOnWake = reconnectOnWake,
        bypassLan = bypassLan,
    )
}

class TunnelStateStore(context: Context) : TunnelSettingsRepository, TunnelStateSink {
    private val prefs = context.getSharedPreferences("cartunnel.state", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow<TunnelRuntimeState>(TunnelRuntimeState.Stopped)
    val state: StateFlow<TunnelRuntimeState> = mutable

    @Synchronized
    override fun read(): TunnelSettings {
        val schema = prefs.getInt(KEY_SCHEMA, 0)
        val migrated = TunnelSettingsMigration.fromLegacy(
            currentProfileId = prefs.getString(KEY_CURRENT, null),
            desiredRunning = prefs.getBoolean(KEY_DESIRED, false),
            mode = prefs.getString(KEY_MODE, null),
            autoConnect = prefs.getBoolean(KEY_AUTO_CONNECT, false),
            bypassLan = prefs.getBoolean(KEY_BYPASS_LAN, false),
            autoReconnect = prefs.getBoolean(KEY_AUTO_RECONNECT, true),
            reconnectOnNetworkChange = prefs.getBoolean(KEY_NETWORK_RECONNECT, true),
            reconnectOnWake = prefs.getBoolean(KEY_WAKE_RECONNECT, true),
        )
        if (schema < TunnelSettingsMigration.CURRENT_SCHEMA) persist(migrated)
        return migrated
    }

    @Synchronized
    override fun write(settings: TunnelSettings) {
        persist(settings)
    }

    fun settings() = read()
    fun saveSettings(settings: TunnelSettings) = write(settings)

    @Synchronized
    fun updateSettings(transform: (TunnelSettings) -> TunnelSettings) {
        write(transform(read()))
    }

    override fun publish(state: TunnelRuntimeState) {
        mutable.value = state
    }

    fun set(state: TunnelRuntimeState) = publish(state)

    private fun persist(settings: TunnelSettings) {
        val committed = prefs.edit()
            .putInt(KEY_SCHEMA, TunnelSettingsMigration.CURRENT_SCHEMA)
            .putString(KEY_CURRENT, settings.currentProfileId)
            .putBoolean(KEY_DESIRED, settings.desiredRunning)
            .remove(KEY_MODE)
            .putBoolean(KEY_AUTO_CONNECT, settings.autoConnect)
            .putBoolean(KEY_AUTO_RECONNECT, settings.autoReconnect)
            .putBoolean(KEY_NETWORK_RECONNECT, settings.reconnectOnNetworkChange)
            .putBoolean(KEY_WAKE_RECONNECT, settings.reconnectOnWake)
            .putBoolean(KEY_BYPASS_LAN, settings.bypassLan)
            .commit()
        check(committed) { "Tunnel settings commit failed" }
    }

    private companion object {
        const val KEY_SCHEMA = "schemaVersion"
        const val KEY_CURRENT = "current"
        const val KEY_DESIRED = "desired"
        const val KEY_MODE = "mode"
        const val KEY_AUTO_CONNECT = "auto"
        const val KEY_AUTO_RECONNECT = "autoReconnect"
        const val KEY_NETWORK_RECONNECT = "reconnectOnNetworkChange"
        const val KEY_WAKE_RECONNECT = "reconnectOnWake"
        const val KEY_BYPASS_LAN = "lan"
    }
}
