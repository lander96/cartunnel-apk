package com.cartunnel.client.profile

import org.json.JSONArray
import org.json.JSONObject

/** Schema 2 stores only the fields the VMess/WS product can use. */
object ProfileJsonCodec {
    fun toJson(p: VmessWsProfile) = JSONObject().apply {
        ProfileValidator.requireValid(p)
        put("schemaVersion", 2); put("id", p.id); put("name", p.name)
        put("server", p.server); put("port", p.port); put("uuid", p.uuid)
        put("wsHost", p.wsHost); put("wsPath", p.wsPath)
    }
    fun fromJson(o: JSONObject): VmessWsProfile {
        val schema = o.optInt("schemaVersion", 1)
        fun reject() : Nothing = throw ProfileValidationException(listOf(FieldError("profile", "仅支持 VMess AEAD、WebSocket、无 TLS、alterId=0")))
        if (schema !in 1..2) reject()
        if (schema == 1 && (o.optString("protocol") != "vmess" || o.optString("transport") != "ws" || o.optString("security") != "none")) reject()
        for ((key, fixed) in listOf("protocol" to "vmess", "transport" to "ws", "security" to "none")) {
            if (o.has(key) && o.optString(key) != fixed) reject()
        }
        for (key in listOf("alterId", "aid")) if (o.has(key) && o.optString(key).toIntOrNull() != 0) reject()
        if (o.has("tls") && o.optString("tls") !in listOf("", "none")) reject()
        if (o.has("network") && o.optString("network") != "ws") reject()
        if (o.has("scy") && o.optString("scy") != "auto") reject()
        for (key in listOf("serverName", "realityPassword", "publicKey", "shortId", "flow")) {
            if (o.optString(key).isNotEmpty()) reject()
        }
        return VmessWsProfile(
            id = o.getString("id"), name = o.getString("name"), server = o.getString("server"),
            port = o.getInt("port"), uuid = o.getString("uuid").lowercase(),
            wsHost = o.getString("wsHost"), wsPath = o.optString("wsPath", "/"),
        ).also(ProfileValidator::requireValid)
    }
    fun encode(profiles: List<VmessWsProfile>) = JSONArray().apply { profiles.forEach { put(toJson(it)) } }.toString(2)
    fun decode(value: String): List<VmessWsProfile> {
        val trimmed = value.trim()
        val result = if (trimmed.startsWith("[")) JSONArray(trimmed).let { a -> List(a.length()) { fromJson(a.getJSONObject(it)) } }
        else listOf(fromJson(JSONObject(trimmed)))
        require(result.isNotEmpty() && result.map { it.id }.distinct().size == result.size) { "Empty or duplicate profiles" }
        return result
    }
}

data class ProfileMigrationResult(val profiles: List<VmessWsProfile>, val selectedId: String?, val removedCount: Int)
object ProfileMigration {
    fun migrate(entries: JSONArray, selectedId: String?): ProfileMigrationResult {
        val profiles = (0 until entries.length()).mapNotNull {
            runCatching { ProfileJsonCodec.fromJson(entries.getJSONObject(it)) }.getOrNull()
        }.distinctBy { it.id }
        return ProfileMigrationResult(profiles, selectedId?.takeIf { id -> profiles.any { it.id == id } } ?: profiles.firstOrNull()?.id,
            entries.length() - profiles.size)
    }
}
