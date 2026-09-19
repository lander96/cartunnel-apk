package com.cartunnel.client.core

import com.cartunnel.client.profile.ProfileValidator
import com.cartunnel.client.profile.VmessWsProfile
import org.json.JSONArray
import org.json.JSONObject

object XrayConfigFactory {
    const val SOCKS_PORT = 10808
    fun create(profile: VmessWsProfile): String {
        ProfileValidator.requireValid(profile)
        val inbound = JSONObject().put("tag", "socks-in").put("listen", "127.0.0.1")
            .put("port", SOCKS_PORT).put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "noauth").put("udp", true))
        val user = JSONObject().put("id", profile.uuid).put("alterId", 0).put("security", "auto")
        val server = JSONObject().put("address", profile.server).put("port", profile.port)
            .put("users", JSONArray().put(user))
        val stream = JSONObject().put("network", "ws").put("security", "none")
            .put("wsSettings", JSONObject().put("path", profile.wsPath)
                .put("headers", JSONObject().put("Host", profile.wsHost)))
        val outbound = JSONObject().put("tag", "proxy").put("protocol", "vmess")
            .put("settings", JSONObject().put("vnext", JSONArray().put(server))).put("streamSettings", stream)
        return JSONObject().put("log", JSONObject().put("loglevel", "warning"))
            .put("stats", JSONObject()).put("policy", JSONObject().put("system",
                JSONObject().put("statsOutboundUplink", true).put("statsOutboundDownlink", true)))
            .put("inbounds", JSONArray().put(inbound)).put("outbounds", JSONArray().put(outbound)).toString()
    }
}
