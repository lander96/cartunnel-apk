package com.cartunnel.client.vpn

import com.cartunnel.client.core.HevConfigFactory
import com.cartunnel.client.core.ProfileLatencyProbe
import com.cartunnel.client.core.XrayConfigFactory
import com.cartunnel.client.profile.VmessWsProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IcarVpnContractTest {
    private val profile = VmessWsProfile(
        id = "icar",
        name = "iCAR",
        server = "203.0.113.1",
        uuid = "00000000-0000-4000-8000-000000000000",
        wsHost = "example.com",
    )

    @Test
    fun xrayConfigKeepsOnlyLoopbackSocksInbound() {
        val inbounds = JSONObject(XrayConfigFactory.create(profile)).getJSONArray("inbounds")

        assertEquals(1, inbounds.length())
        assertEquals(10808, inbounds.getJSONObject(0).getInt("port"))
        assertEquals("127.0.0.1", inbounds.getJSONObject(0).getString("listen"))
        assertFalse(XrayConfigFactory.create(profile).contains("10809"))
    }

    @Test
    fun vpnPlanMatchesIcarIpv4Contract() {
        val plan = VpnInterfacePlanFactory.create("com.cartunnel.client", bypassLan = false)

        assertEquals("CarTunnel", plan.session)
        assertEquals(1500, plan.mtu)
        assertEquals(listOf(VpnAddress("10.10.0.2", 32)), plan.addresses)
        assertEquals(listOf("223.5.5.5", "119.29.29.29"), plan.dnsServers)
        assertEquals(listOf(Cidr("0.0.0.0", 0)), plan.routes)
        assertEquals(setOf(VpnInterfacePlan.IPV4_FAMILY), plan.allowedFamilies)
        assertEquals(listOf("com.cartunnel.client"), plan.disallowedApplications)
    }

    @Test
    fun hevConfigMatchesTunAddressAndTimeoutContract() {
        val config = HevConfigFactory.render()

        assertTrue(config.contains("ipv4: 10.10.0.2"))
        assertTrue(config.contains("port: 10808"))
        assertTrue(config.contains("tcp-read-write-timeout: 300000"))
        assertTrue(config.contains("udp-read-write-timeout: 60000"))
        assertTrue(config.contains("address: 127.0.0.1"))
    }

    @Test
    fun healthEndpointsAreDomesticAndDecoupledFromStartup() {
        assertEquals(listOf("https://www.baidu.com/", "https://www.qq.com/"), ProfileLatencyProbe.DEFAULT_ENDPOINTS)
        assertFalse(ProfileLatencyProbe.DEFAULT_ENDPOINTS.any { it.contains("cloudflare", ignoreCase = true) })
    }
}
