package com.cartunnel.client.profile

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VmessUriCodecTest {
    private fun uri(
        net: String = "ws",
        tls: String = "",
        aid: String = "0",
    ): String {
        val json = JSONObject().apply {
            put("v", "2"); put("ps", "Car VMess WS"); put("add", "203.0.113.1"); put("port", "818")
            put("id", "00000000-0000-4000-8000-000000000000"); put("aid", aid); put("scy", "auto")
            put("net", net); put("type", "none"); put("host", "www.baidu.com"); put("path", "/compat"); put("tls", tls)
        }
        return "vmess://${Base64.getEncoder().withoutPadding().encodeToString(json.toString().toByteArray(StandardCharsets.UTF_8))}"
    }

    @Test fun decodesCarCompatibleVmessWebsocket() {
        val profile = VmessUriCodec.decode(uri())
        assertEquals(818, profile.port)
        assertEquals("www.baidu.com", profile.wsHost)
        assertEquals("/compat", profile.wsPath)
    }

    @Test fun roundTripPreservesCompatibilityFields() {
        val profile = VmessUriCodec.decode(uri())
        assertEquals(profile.copy(id = "ignored"), VmessUriCodec.decode(VmessUriCodec.encode(profile)).copy(id = "ignored"))
        assertEquals(profile.wsPath, ProfileUriCodec.decode(ProfileUriCodec.encode(profile)).wsPath)
    }

    @Test fun rejectsTlsTcpAndLegacyAlterId() {
        listOf(uri(tls="tls"), uri(net="tcp"), uri(aid="1")).forEach {
            assertTrue(runCatching { VmessUriCodec.decode(it) }.exceptionOrNull() is ProfileValidationException)
        }
    }
}
