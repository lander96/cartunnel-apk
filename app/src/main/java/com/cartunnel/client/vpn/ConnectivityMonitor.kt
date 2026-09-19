package com.cartunnel.client.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ConnectivityMonitor(
    context: Context,
    private val scope: CoroutineScope,
    private val onStableChange: (String?) -> Unit,
) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val tracker = UnderlyingNetworkTracker()
    private val networks = mutableMapOf<Network, String>()
    private var debounce: Job? = null
    private var registered = false
    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh(network)
        override fun onLost(network: Network) {
            synchronized(networks) { networks.remove(network) }
            debounce()
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh(network)
    }

    fun start() {
        if (registered) return
        registered = true
        manager.registerNetworkCallback(request, callback)
        manager.allNetworks.forEach(::refresh)
        debounce()
    }

    fun stop() {
        debounce?.cancel()
        debounce = null
        if (registered) manager.unregisterNetworkCallback(callback)
        synchronized(networks) { networks.clear() }
        tracker.reset()
        registered = false
    }

    private fun refresh(network: Network) {
        val capabilities = manager.getNetworkCapabilities(network)
        synchronized(networks) {
            if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN).not()
            ) {
                networks[network] = network.toString()
            } else {
                networks.remove(network)
            }
        }
        debounce()
    }

    private fun debounce() {
        debounce?.cancel()
        debounce = scope.launch {
            delay(2_000)
            val ids = synchronized(networks) { networks.values.toSet() }
            when (val result = tracker.reduce(ids)) {
                UnderlyingNetworkResult.NoChange -> Unit
                is UnderlyingNetworkResult.Changed -> onStableChange(result.networkIds.sorted().joinToString("|"))
            }
        }
    }
}
