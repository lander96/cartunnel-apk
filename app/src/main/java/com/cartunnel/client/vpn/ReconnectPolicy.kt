package com.cartunnel.client.vpn

class ReconnectPolicy {
    private val delays = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000, 30_000)
    fun delay(attempt: Int) = delays[attempt.coerceAtLeast(0).coerceAtMost(delays.lastIndex)]
    fun reset() = 0
}
