package com.cartunnel.client.profile

import java.net.IDN
import java.util.UUID

object ProfileValidator {
    private fun hostValid(host: String) = host.isNotBlank() && host.length <= 253 &&
        host.none { it.isWhitespace() || it == '/' || it == ':' || it.isISOControl() } &&
        runCatching { IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES) }.isSuccess
    fun validate(p: VmessWsProfile): List<FieldError> = buildList {
        if (p.schemaVersion != 2) add(FieldError("schemaVersion", "不支持的配置版本"))
        if (p.id.isBlank()) add(FieldError("id", "节点标识不能为空"))
        if (p.name.trim().length !in 1..40 || p.name.any(Char::isISOControl)) add(FieldError("name", "节点名需为 1 到 40 个字符"))
        if (!hostValid(p.server)) add(FieldError("server", "服务器必须是域名或 IPv4 地址"))
        if (p.port !in 1..65535) add(FieldError("port", "端口需在 1 到 65535"))
        if (!Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}").matches(p.uuid) ||
            runCatching { UUID.fromString(p.uuid) }.isFailure) add(FieldError("uuid", "UUID 格式无效"))
        if (!hostValid(p.wsHost)) add(FieldError("wsHost", "WebSocket Host 必须是域名或 IPv4 地址"))
        if (!p.wsPath.startsWith("/") || p.wsPath.length > 256 || p.wsPath.any { it.isWhitespace() || it.isISOControl() })
            add(FieldError("wsPath", "WebSocket 路径必须以 / 开头、不含空白且不超过 256 字符"))
    }
    fun requireValid(p: VmessWsProfile) { validate(p).also { if (it.isNotEmpty()) throw ProfileValidationException(it) } }
}
