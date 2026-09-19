package com.cartunnel.client.diag

import java.util.Locale

/** Only emits stable categories and exception class names, never native error text. */
object ProbeFailureSummary {
    fun describe(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(4).toList()
        val classifierInput = chain.joinToString(" ") {
            "${it.javaClass.simpleName} ${it.message.orEmpty()}"
        }.lowercase(Locale.ROOT)
        val category = when {
            listOf("unknownhostexception", "no such host", "lookup", "dns").any(classifierInput::contains) -> "DNS"
            listOf("noroutetohostexception", "network is unreachable", "no route to host").any(classifierInput::contains) -> "NO_ROUTE"
            listOf("sockettimeoutexception", "timeout", "deadline exceeded").any(classifierInput::contains) -> "TIMEOUT"
            listOf("connection refused", "connectexception").any(classifierInput::contains) -> "CONNECT"
            listOf("tls", "handshake", "certificate", "x509").any(classifierInput::contains) -> "HANDSHAKE"
            else -> "UNKNOWN"
        }
        val classes = chain.joinToString(">") { it.javaClass.simpleName.ifBlank { "Throwable" } }
        return "category=$category classes=$classes"
    }
}
