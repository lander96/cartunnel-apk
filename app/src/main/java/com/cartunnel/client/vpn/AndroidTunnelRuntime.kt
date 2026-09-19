package com.cartunnel.client.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.cartunnel.client.core.HevConfigFactory
import com.cartunnel.client.core.HevTunForwarder
import com.cartunnel.client.core.ProfileLatencyProbe
import com.cartunnel.client.core.ProxyCore
import com.cartunnel.client.core.XrayConfigFactory
import com.cartunnel.client.core.XrayProxyCore
import com.cartunnel.client.core.XrayRuntimeEnvironment
import com.cartunnel.client.diag.RedactingLog
import com.cartunnel.client.diag.ProbeFailureSummary
import com.cartunnel.client.profile.SecureProfileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Android adapter: owns Xray, TUN and HEV resources for one VPN session. */
class AndroidTunnelRuntime(
    private val service: VpnService,
    private val profiles: SecureProfileStore,
    private val settings: () -> TunnelSettings,
) : TunnelRuntime {
    private var core: ProxyCore? = null
    private var hev: HevTunForwarder? = null
    private var tun: ParcelFileDescriptor? = null

    override suspend fun start(profileId: String): TunnelStartResult {
        if (VpnService.prepare(service) != null) return TunnelStartResult.PermissionRequired

        val profile = try {
            profiles.load().first { it.id == profileId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TunnelStartFailure(
                code = "PROFILE_INVALID",
                safeMessage = "当前节点不可读取或不存在",
                kind = TunnelFailureKind.STARTUP_FAILURE,
                recoverable = false,
                cause = e,
            )
        }

        try {
            XrayRuntimeEnvironment.initialize(service.filesDir.absolutePath)
            val xray = XrayProxyCore()
            xray.start(XrayConfigFactory.create(profile))
            core = xray
            awaitPort(XrayConfigFactory.SOCKS_PORT)
            RedactingLog.write("CORE_READY")

            val established = VpnInterfaceFactory(service).establish(settings().bypassLan)
                ?: throw TunnelStartFailure(
                    code = "TUN_ESTABLISH_FAILED",
                    safeMessage = "无法创建 VPN 网络接口",
                    kind = TunnelFailureKind.STARTUP_FAILURE,
                    recoverable = true,
                )
            tun = established
            RedactingLog.write("TUN_READY")

            val forwarder = HevTunForwarder()
            if (!forwarder.start(writeHevConfig(), established.fd)) {
                throw TunnelStartFailure(
                    code = "TUN_FORWARDER_FAILED",
                    safeMessage = "VPN 转发器启动失败",
                    kind = TunnelFailureKind.STARTUP_FAILURE,
                    recoverable = true,
                )
            }
            hev = forwarder
            RedactingLog.write("HEV_READY")
            return TunnelStartResult.Started
        } catch (e: CancellationException) {
            throw e
        } catch (e: TunnelStartFailure) {
            cleanup()
            throw e
        } catch (e: Exception) {
            cleanup()
            throw TunnelStartFailure(
                code = "CORE_INIT_FAILED",
                safeMessage = "连接启动失败",
                kind = TunnelFailureKind.STARTUP_FAILURE,
                recoverable = true,
                cause = e,
            )
        }
    }

    override suspend fun measureLatency(): Long? {
        val xray = core ?: return null
        var last: Throwable? = null
        for ((index, endpoint) in ProfileLatencyProbe.DEFAULT_ENDPOINTS.withIndex()) {
            try {
                return xray.measureDelay(endpoint).also {
                    require(it >= 0) { "Negative latency" }
                    RedactingLog.write("VPN_HTTP_OK target=${index + 1} latencyMs=$it")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                RedactingLog.write("VPN_HTTP_FAIL target=${index + 1} ${ProbeFailureSummary.describe(e)}")
                last = e
            }
        }
        throw last ?: IllegalStateException("No health endpoints configured")
    }

    override suspend fun stop() = cleanup()

    private suspend fun awaitPort(port: Int) {
        try {
            withTimeout(5_000) {
                while (true) {
                    if (runCatching { java.net.Socket("127.0.0.1", port).use {} }.isSuccess) return@withTimeout
                    delay(100)
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw TunnelStartFailure(
                code = "CORE_NOT_READY",
                safeMessage = "连接核心未就绪",
                kind = TunnelFailureKind.READINESS_TIMEOUT,
                recoverable = true,
                cause = e,
            )
        }
    }

    private suspend fun cleanup() {
        try {
            withTimeoutOrNull(3_000) { hev?.stop() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Cleanup is best effort; the coordinator still releases PFD and Xray.
        }
        hev = null
        try {
            tun?.close()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep the release order deterministic even if closing the PFD fails.
        }
        tun = null
        try {
            core?.stop()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep the release order deterministic even if native Xray stop reports an error.
        }
        core = null
    }

    private fun writeHevConfig(): String {
        val file = java.io.File(service.filesDir, "hev.yml")
        file.writeText(HevConfigFactory.render())
        return file.absolutePath
    }
}
