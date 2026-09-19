package com.cartunnel.client.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cartunnel.client.profile.ProfileValidator
import com.cartunnel.client.profile.SecureProfileStore
import com.cartunnel.client.vpn.CarTunnelService
import com.cartunnel.client.vpn.TunnelStateStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!com.cartunnel.client.ui.UserConsent.isAccepted(context)) return
        val action = intent.action ?: return
        if (action !in BOOT_ACTIONS && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val states = TunnelStateStore(context)
        val current = states.settings()
        val bootLike = action in BOOT_ACTIONS
        if (bootLike && !current.autoConnect) return
        if (!bootLike && !current.desiredRunning) return
        val profileId = current.currentProfileId ?: return
        val storedProfiles = runCatching { SecureProfileStore(context).load() }.getOrElse {
            if (bootLike) DeferredBootResume(context).mark()
            return
        }
        val profile = storedProfiles.firstOrNull { it.id == profileId } ?: return
        if (runCatching { ProfileValidator.requireValid(profile) }.isFailure) return
        if (bootLike) DeferredBootResume(context).clear()
        if (!ResumeCooldown(context).tryAcquire()) return

        if (bootLike) states.updateSettings { it.copy(desiredRunning = true) }
        if (states.settings().desiredRunning) CarTunnelService.resume(context)
    }

    companion object {
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "autochips.intent.action.QB_POWERON",
        )
    }
}
