package com.cartunnel.client

import android.app.Application
import com.cartunnel.client.diag.RedactingLog
import com.cartunnel.client.profile.SecureProfileStore
import com.cartunnel.client.profile.ProfileHealthStore
import com.cartunnel.client.vpn.TunnelStateStore

class App : Application() {
    lateinit var profiles: SecureProfileStore; private set
    lateinit var profileHealth: ProfileHealthStore; private set
    lateinit var tunnelStates: TunnelStateStore; private set
    override fun onCreate() {
        super.onCreate()
        profiles = SecureProfileStore(this)
        profileHealth = ProfileHealthStore(this)
        tunnelStates = TunnelStateStore(this)
        RedactingLog.install(this)
        runCatching {
            val all = profiles.load()
            tunnelStates.updateSettings { state ->
                if (all.any { it.id == state.currentProfileId }) state
                else state.copy(currentProfileId = all.firstOrNull()?.id, desiredRunning = false)
            }
        }.onFailure { RedactingLog.write("PROFILE_MIGRATION_FAILED type=${it.javaClass.simpleName}") }
    }
}
