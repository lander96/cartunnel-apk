package com.cartunnel.client.core

import com.cartunnel.client.profile.VmessWsProfile
import com.cartunnel.client.diag.ProbeFailureSummary
import com.cartunnel.client.diag.RedactingLog
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import libv2ray.Libv2ray

interface ProfileLatencyProbe {
    suspend fun measure(profile: VmessWsProfile): Long

    companion object {
        val DEFAULT_ENDPOINTS = listOf("https://www.baidu.com/", "https://www.qq.com/")
    }
}

/** Runs a one-shot HTTP request through the profile without changing Android routes. */
class XrayProfileLatencyProbe(
    private val environmentPath: String,
    private val endpoints: List<String> = ProfileLatencyProbe.DEFAULT_ENDPOINTS,
) : ProfileLatencyProbe {
    override suspend fun measure(profile: VmessWsProfile): Long = withContext(Dispatchers.IO) {
        nativeProbeMutex.withLock {
            val hostHash = RedactingLog.hostHash(profile.server)
            RedactingLog.write("NODE_PROBE_START host=$hostHash port=${profile.port} transport=vmess-ws")
            try {
                Socket().use { it.connect(InetSocketAddress(profile.server, profile.port), 3_000) }
                RedactingLog.write("NODE_TCP_OPEN host=$hostHash port=${profile.port}")
            } catch (error: Exception) {
                RedactingLog.write("NODE_TCP_FAIL host=$hostHash port=${profile.port} ${ProbeFailureSummary.describe(error)}")
            }
            RedactingLog.write("NODE_TLS_NOT_USED host=$hostHash port=${profile.port}")
            XrayRuntimeEnvironment.initialize(environmentPath)
            var last: Throwable? = null
            for ((index, endpoint) in endpoints.withIndex()) {
                try {
                    return@withLock Libv2ray.measureOutboundDelay(
                        XrayConfigFactory.create(profile),
                        endpoint,
                    ).also {
                        require(it >= 0) { "Negative latency" }
                        RedactingLog.write("NODE_HTTP_OK target=${index + 1} latencyMs=$it")
                    }
                } catch (error: Throwable) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    RedactingLog.write("NODE_HTTP_FAIL target=${index + 1} ${ProbeFailureSummary.describe(error)}")
                    last = error
                }
            }
            throw last ?: IllegalStateException("No health endpoints configured")
        }
    }

    private companion object {
        val nativeProbeMutex = Mutex()

    }
}
