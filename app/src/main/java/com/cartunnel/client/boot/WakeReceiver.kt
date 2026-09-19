package com.cartunnel.client.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cartunnel.client.profile.ProfileValidator
import com.cartunnel.client.profile.SecureProfileStore
import com.cartunnel.client.vpn.CarTunnelService
import com.cartunnel.client.vpn.TunnelStateStore

class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!com.cartunnel.client.ui.UserConsent.isAccepted(context)) return
        // USER_UNLOCKED is only the delayed boot-recovery path. Runtime SCREEN_ON
        // is registered by the live service and is sent to WakeResume directly.
        if (intent.action != Intent.ACTION_USER_UNLOCKED) return
        if (!DeferredBootResume(context).consume()) return
        val states = TunnelStateStore(context)
        val settings = states.settings()
        if (!settings.autoConnect || settings.desiredRunning) return
        val profileId = settings.currentProfileId ?: return
        val valid = runCatching {
            SecureProfileStore(context).load().first { it.id == profileId }.also(ProfileValidator::requireValid)
        }.isSuccess
        if (!valid || !ResumeCooldown(context).tryAcquire()) return
        states.updateSettings { it.copy(desiredRunning = true) }
        CarTunnelService.resume(context)
    }
}
