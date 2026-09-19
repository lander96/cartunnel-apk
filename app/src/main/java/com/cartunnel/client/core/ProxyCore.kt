package com.cartunnel.client.core

import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

interface ProxyCore {
    suspend fun start(config: String)
    suspend fun stop()
    suspend fun measureDelay(url: String): Long
    fun isRunning(): Boolean
}
interface TunForwarder { suspend fun start(configPath: String, borrowedTunFd: Int): Boolean; suspend fun stop(); fun isRunning(): Boolean }

object XrayRuntimeEnvironment {
    @Volatile
    private var initializedPath: String? = null

    @Synchronized
    fun initialize(path: String) {
        if (initializedPath == path) return
        Libv2ray.initCoreEnv(path, "")
        initializedPath = path
    }
}

class XrayProxyCore : ProxyCore {
    private var controller: CoreController? = null
    private val callback = object : CoreCallbackHandler {
        override fun startup(): Long = 0L
        override fun shutdown(): Long = 0L
        override fun onEmitStatus(code: Long, message: String): Long = 0L
    }
    override suspend fun start(config: String) {
        check(controller == null) { "Only one Xray controller is allowed" }
        try {
            val instance = Libv2ray.newCoreController(callback)
            instance.startLoop(config, 0)
            controller = instance
        } catch (e: Exception) { throw IllegalStateException("CORE_INIT_FAILED", e) }
    }
    override suspend fun stop() { controller?.let { runCatching { it.stopLoop() } }; controller = null }
    override suspend fun measureDelay(url: String): Long =
        checkNotNull(controller) { "Core is not started" }.measureDelay(url)
    override fun isRunning() = controller?.isRunning == true

}

class HevTunForwarder : TunForwarder {
    private var running = false
    override suspend fun start(configPath: String, borrowedTunFd: Int): Boolean { running = try { System.loadLibrary("hev-socks5-tunnel"); HevNative.TProxyStartService(configPath, borrowedTunFd) } catch (_: UnsatisfiedLinkError) { false }; return running }
    override suspend fun stop() { if (running) HevNative.TProxyStopService(); running = false }
    override fun isRunning() = running && runCatching { HevNative.TProxyIsRunning() }.getOrDefault(false)
}
/** Java retains PFD ownership; HEV only borrows its integer descriptor while running. */
object HevNative {
    external fun TProxyStartService(configPath: String, fd: Int): Boolean
    external fun TProxyStopService(): Boolean
    external fun TProxyIsRunning(): Boolean

    // Required by the upstream RegisterNatives table even when the UI does not
    // currently display HEV packet counters. Omitting it makes JNI_OnLoad fail.
    external fun TProxyGetStats(): LongArray
}
