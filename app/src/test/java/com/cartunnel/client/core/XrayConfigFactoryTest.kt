package com.cartunnel.client.core
import com.cartunnel.client.profile.VmessWsProfile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class XrayConfigFactoryTest {
    private val p = VmessWsProfile(id="id",name="节点",server="example.com",uuid="00000000-0000-4000-8000-000000000000",wsHost="ws.example.com",wsPath="/car")
    @Test fun onlyInternalSocksAndVmessWsAeadAreGenerated() {
        val config = JSONObject(XrayConfigFactory.create(p))
        val inbound = config.getJSONArray("inbounds")
        assertEquals(1, inbound.length())
        assertEquals("127.0.0.1", inbound.getJSONObject(0).getString("listen"))
        assertEquals(10808, inbound.getJSONObject(0).getInt("port"))
        assertTrue(config.has("stats"))
        assertTrue(config.getJSONObject("policy").getJSONObject("system").getBoolean("statsOutboundUplink"))
        val out = config.getJSONArray("outbounds")
        assertEquals(1, out.length())
        assertEquals("vmess", out.getJSONObject(0).getString("protocol"))
        val stream = out.getJSONObject(0).getJSONObject("streamSettings")
        assertEquals("ws", stream.getString("network")); assertEquals("none", stream.getString("security"))
        assertEquals("/car", stream.getJSONObject("wsSettings").getString("path"))
        assertEquals("ws.example.com", stream.getJSONObject("wsSettings").getJSONObject("headers").getString("Host"))
        val user = out.getJSONObject(0).getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getJSONArray("users").getJSONObject(0)
        assertEquals(setOf("id","alterId","security"), user.keySet())
        assertEquals(0,user.getInt("alterId")); assertEquals("auto",user.getString("security"))
        assertEquals(setOf("network","security","wsSettings"), stream.keySet())
    }
}
