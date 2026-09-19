package com.cartunnel.client.diag

import android.content.Context
import java.security.MessageDigest
import java.util.ArrayDeque

object RedactingLog {
    private const val maxEntries = 200; private const val maxBytes = 128 * 1024; private val entries = ArrayDeque<String>(); private var size = 0
    fun install(context: Context) = Unit
    @Synchronized fun write(message: String) { val safe = redact(message).take(1024); while ((entries.size >= maxEntries || size + safe.length > maxBytes) && entries.isNotEmpty()) { val old = entries.removeFirst(); size -= old.length }; entries.addLast("${System.currentTimeMillis()} $safe"); size += safe.length }
    @Synchronized fun dump() = entries.joinToString("\n")
    @Synchronized fun clear() { entries.clear(); size = 0 }
    fun hostHash(host: String) = MessageDigest.getInstance("SHA-256").digest(host.toByteArray()).joinToString("") { "%02x".format(it) }.take(8)
    private fun redact(s: String): String = s.replace(Regex("[a-z]+://[^\\s]+", RegexOption.IGNORE_CASE), "[uri-redacted]").replace(Regex("[0-9a-f]{8}-[0-9a-f-]{27,36}", RegexOption.IGNORE_CASE), "[uuid-redacted]").replace(Regex("(?i)(password|token|uuid)=?[^\\s,&]+"), "$1=[redacted]")
}
