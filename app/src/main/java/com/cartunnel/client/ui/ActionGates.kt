package com.cartunnel.client.ui

/** Tokens protect a new action from an old callback; cooldown also absorbs queued taps. */
class SingleFlightGate(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    data class Token(val action: String, val generation: Long)
    private var generation = 0L
    private val pending = mutableMapOf<String, Token>()
    private val acceptedAt = mutableMapOf<String, Long>()
    @Synchronized fun begin(action: String): Token? {
        val now = clock()
        if (pending.containsKey(action) || acceptedAt[action]?.let { now - it < 700 } == true) return null
        return Token(action, ++generation).also { pending[action] = it; acceptedAt[action] = now }
    }
    @Synchronized fun finish(token: Token) { if (pending[token.action] == token) pending.remove(token.action) }
    @Synchronized fun isBusy(action: String) = pending.containsKey(action)
}

class PendingVpnStartGate {
    data class Pending(val profileId: String, val generation: Long)
    var pending: Pending? = null; private set
    private var generation = 0L
    @Synchronized fun request(profileId: String): Pending? {
        if (pending != null) return null
        return Pending(profileId, ++generation).also { pending = it }
    }
    @Synchronized fun consume(generation: Long, granted: Boolean): Pending? {
        val value = pending ?: return null
        if (value.generation != generation || !granted) return null
        pending = null
        return value
    }
    @Synchronized fun cancel(generation: Long) { if (pending?.generation == generation) pending = null }
}
