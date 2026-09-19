package com.cartunnel.client.vpn

/**
 * Prevents a callback from an older service start from removing a newer
 * foreground notification.
 */
class ServiceStopTokenGate(
    private val stopSelfResult: (Int) -> Boolean,
    private val removeForeground: () -> Unit,
) {
    fun requestStop(requestToken: Int): Boolean {
        if (!stopSelfResult(requestToken)) return false
        removeForeground()
        return true
    }
}
