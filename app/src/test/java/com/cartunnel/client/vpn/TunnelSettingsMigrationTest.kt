package com.cartunnel.client.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelSettingsMigrationTest {
    @Test
    fun retiredModeClearsDesiredRunning() {
        val settings = TunnelSettingsMigration.fromLegacy(
            currentProfileId = "profile-a",
            desiredRunning = true,
            mode = "retired-mode",
            autoConnect = true,
            bypassLan = true,
        )

        assertFalse(settings.desiredRunning)
        assertTrue(settings.autoConnect)
        assertTrue(settings.bypassLan)
        assertTrue(settings.autoReconnect)
        assertTrue(settings.reconnectOnNetworkChange)
        assertTrue(settings.reconnectOnWake)
    }

    @Test
    fun legacyVpnRetainsSafeRunningIntentAndDefaults() {
        val settings = TunnelSettingsMigration.fromLegacy(
            currentProfileId = "profile-a",
            desiredRunning = true,
            mode = "VPN",
            autoConnect = false,
            bypassLan = false,
        )

        assertTrue(settings.desiredRunning)
        assertFalse(settings.autoConnect)
        assertTrue(settings.autoReconnect)
        assertTrue(settings.reconnectOnNetworkChange)
        assertTrue(settings.reconnectOnWake)
    }
}
