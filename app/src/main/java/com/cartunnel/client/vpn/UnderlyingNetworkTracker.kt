package com.cartunnel.client.vpn

sealed interface UnderlyingNetworkResult {
    data object NoChange : UnderlyingNetworkResult
    data class Changed(val networkIds: Set<String>) : UnderlyingNetworkResult
}

/** Stable set reducer; an empty set is retained as a baseline but never emits offline. */
class UnderlyingNetworkTracker {
    private var initialized = false
    private var current: Set<String> = emptySet()

    fun reset() {
        initialized = false
        current = emptySet()
    }

    fun reduce(networkIds: Set<String>): UnderlyingNetworkResult {
        val normalized = networkIds.toSet()
        if (!initialized) {
            initialized = true
            current = normalized
            return UnderlyingNetworkResult.NoChange
        }
        if (normalized.isEmpty()) {
            current = emptySet()
            return UnderlyingNetworkResult.NoChange
        }
        if (current == normalized) return UnderlyingNetworkResult.NoChange
        current = normalized
        return UnderlyingNetworkResult.Changed(normalized)
    }
}
