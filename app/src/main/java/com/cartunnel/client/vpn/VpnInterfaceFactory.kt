package com.cartunnel.client.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor

data class VpnAddress(val address: String, val prefix: Int)

data class VpnInterfacePlan(
    val session: String,
    val mtu: Int,
    val addresses: List<VpnAddress>,
    val dnsServers: List<String>,
    val routes: List<Cidr>,
    val disallowedApplications: List<String>,
    val allowedFamilies: Set<Int>,
) {
    companion object {
        const val IPV4_FAMILY = 2 // AF_INET
    }
}

object VpnInterfacePlanFactory {
    fun create(packageName: String, bypassLan: Boolean): VpnInterfacePlan = VpnInterfacePlan(
        session = "CarTunnel",
        mtu = 1500,
        addresses = listOf(VpnAddress("10.10.0.2", 32)),
        dnsServers = listOf("223.5.5.5", "119.29.29.29"),
        routes = Ipv4RoutePlanner.routes(bypassLan),
        disallowedApplications = listOf(packageName),
        allowedFamilies = setOf(VpnInterfacePlan.IPV4_FAMILY),
    )
}

class VpnInterfaceFactory(private val service: VpnService) {
    fun establish(bypassLan: Boolean): ParcelFileDescriptor? {
        val plan = VpnInterfacePlanFactory.create(service.packageName, bypassLan)
        return service.Builder()
            .setSession(plan.session)
            .setMtu(plan.mtu)
            .apply {
                plan.addresses.forEach { addAddress(it.address, it.prefix) }
                plan.dnsServers.forEach(::addDnsServer)
                plan.routes.forEach { addRoute(it.address, it.prefix) }
                plan.disallowedApplications.forEach(::addDisallowedApplication)
                plan.allowedFamilies.forEach(::allowFamily)
            }
            .establish()
    }
}
