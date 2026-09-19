package com.cartunnel.client.vpn

import org.junit.Assert.*
import org.junit.Test

class Ipv4RoutePlannerTest {
    @Test fun defaultRouteWhenLanNotBypassed() { assertEquals(listOf(Cidr("0.0.0.0",0)), Ipv4RoutePlanner.routes(false)) }
    @Test fun noOverlappingOrDefaultWhenBypassed() { val r=Ipv4RoutePlanner.routes(true); assertTrue(r.isNotEmpty()); assertFalse(r.any { it.prefix==0 }); assertFalse(covered(r, "10.1.2.3")); assertFalse(covered(r, "198.18.0.1")); assertFalse(covered(r, "240.0.0.1")); assertTrue(covered(r, "8.8.8.8")); assertTrue(covered(r, "192.0.2.1")) }
    private fun covered(routes: List<Cidr>, value: String): Boolean { val ip = value.split('.').fold(0L) { a, s -> (a shl 8) or s.toLong() }; return routes.any { r -> val base = r.address.split('.').fold(0L) { a, s -> (a shl 8) or s.toLong() }; r.prefix == 0 || (ip shr (32 - r.prefix)) == (base shr (32 - r.prefix)) } }
}
