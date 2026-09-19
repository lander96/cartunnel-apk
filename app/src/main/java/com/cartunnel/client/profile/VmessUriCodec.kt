package com.cartunnel.client.profile

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import org.json.JSONObject

/** Strict VMess URI support for the single car-compatible VMess + plain WebSocket mode. */
object VmessUriCodec {
    fun decode(value: String): VmessWsProfile {
        if (!value.startsWith("vmess://", ignoreCase = true)) invalid("uri", "仅支持 vmess URI")
        val payload = value.substringAfter("://").trim()
        val decoded = runCatching { decodeBase64(payload) }.getOrElse { invalid("uri", "VMess URI Base64 无效") }
        val json = runCatching { JSONObject(String(decoded, StandardCharsets.UTF_8)) }
            .getOrElse { invalid("uri", "VMess URI JSON 无效") }
        fun need(key: String) = json.optString(key).takeIf(String::isNotBlank) ?: invalid(key, "缺少参数")
        val network = json.optString("net", "tcp").lowercase()
        val tls = json.optString("tls").lowercase()
        if (json.optString("scy", "auto") !in listOf("", "auto") || json.optString("type", "none") !in listOf("", "none")) invalid("uri", "仅支持 auto 加密和标准 WebSocket")
        val alterId = json.optString("aid", "0").toIntOrNull() ?: invalid("aid", "alterId 无效")
        if (network != "ws" || tls.isNotEmpty() || alterId != 0) {
            invalid("uri", "车机兼容模式仅支持 VMess、WebSocket、无 TLS、alterId=0")
        }
        val profile = VmessWsProfile(
            id = UUID.randomUUID().toString(),
            name = json.optString("ps").ifBlank { "导入的 VMess 节点" },
            server = need("add"),
            port = need("port").toIntOrNull() ?: invalid("port", "端口无效"),
            uuid = need("id").lowercase(),
            wsHost = need("host"),
            wsPath = json.optString("path", "/").ifBlank { "/" },
        )
        ProfileValidator.requireValid(profile)
        return profile
    }

    fun encode(profile: VmessWsProfile): String {
        ProfileValidator.requireValid(profile)
        val json = JSONObject().apply {
            put("v", "2")
            put("ps", profile.name)
            put("add", profile.server)
            put("port", profile.port.toString())
            put("id", profile.uuid)
            put("aid", "0")
            put("scy", "auto")
            put("net", "ws")
            put("type", "none")
            put("host", profile.wsHost)
            put("path", profile.wsPath)
            put("tls", "")
            put("sni", "")
        }
        return "vmess://${Base64.getEncoder().withoutPadding().encodeToString(json.toString().toByteArray(StandardCharsets.UTF_8))}"
    }

    private fun decodeBase64(value: String): ByteArray {
        val normalized = value.replace("\n", "").replace("\r", "")
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching { Base64.getDecoder().decode(padded) }
            .getOrElse { Base64.getUrlDecoder().decode(padded) }
    }

    private fun invalid(field: String, message: String): Nothing =
        throw ProfileValidationException(listOf(FieldError(field, message)))
}
