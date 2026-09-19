package com.cartunnel.client.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.cartunnel.client.App
import com.cartunnel.client.R
import com.cartunnel.client.diag.RedactingLog
import com.cartunnel.client.profile.ProfileHealthStore
import com.cartunnel.client.profile.SecureProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

class CarTunnelService : VpnService() {
    companion object {
        const val ACTION_START = "com.cartunnel.client.START"
        const val ACTION_STOP = "com.cartunnel.client.STOP"
        const val ACTION_RESUME = "com.cartunnel.client.RESUME"
        const val EXTRA_PROFILE = "profile"
        private const val NOTIFICATION_ID = 10

        fun start(context: Context, profileId: String) = context.startForegroundService(
            Intent(context, CarTunnelService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROFILE, profileId),
        )

        fun resume(context: Context) = context.startForegroundService(
            Intent(context, CarTunnelService::class.java).setAction(ACTION_RESUME),
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var states: TunnelStateStore
    private lateinit var profiles: SecureProfileStore
    private lateinit var profileHealth: ProfileHealthStore
    private lateinit var connectivity: ConnectivityMonitor
    private lateinit var coordinator: TunnelSessionCoordinator
    private var screenOnReceiverRegistered = false
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) {
                coordinator.submit(TunnelCommand.WakeResume)
            }
        }
    }
    private val stopTokenGate by lazy {
        ServiceStopTokenGate(
            stopSelfResult = ::stopSelfResult,
            removeForeground = { stopForeground(STOP_FOREGROUND_REMOVE) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as App
        states = app.tunnelStates
        profiles = app.profiles
        profileHealth = app.profileHealth
        channel()
        coordinator = TunnelSessionCoordinator(
            scope = scope,
            settings = states,
            stateSink = object : TunnelStateSink {
                override fun publish(state: TunnelRuntimeState) {
                    states.publish(state)
                    when (state) {
                        is TunnelRuntimeState.Starting -> updateForeground("正在启动：${state.step}")
                        is TunnelRuntimeState.Connected -> updateForeground(
                            "已连接${state.latencyMs?.let { " · ${it}ms" }.orEmpty()}",
                        )
                        is TunnelRuntimeState.Reconnecting -> updateForeground("正在重连（第 ${state.attempt} 次）：${state.reason}")
                        TunnelRuntimeState.Stopping -> Unit
                        TunnelRuntimeState.Stopped,
                        TunnelRuntimeState.PermissionRequired,
                        is TunnelRuntimeState.Error,
                        -> Unit
                    }
                }
            },
            runtime = AndroidTunnelRuntime(this, profiles) { states.read() },
            callbacks = object : TunnelSessionCallbacks {
                override fun onPermissionRequired(requestToken: Int) = stopServiceAction(requestToken)
                override fun onStopped(requestToken: Int) = stopServiceAction(requestToken)
                override fun onTerminalFailure(requestToken: Int) = stopServiceAction(requestToken)
            },
            healthSink = object : TunnelHealthSink {
                override fun testing(profileId: String) {
                    profile(profileId)?.let(profileHealth::markTesting)
                }

                override fun available(profileId: String, latencyMs: Long) {
                    profile(profileId)?.let { profileHealth.markAvailable(it, latencyMs) }
                }

                override fun failed(profileId: String, safeErrorCode: String) {
                    profile(profileId)?.let { profileHealth.markFailed(it, safeErrorCode) }
                }

                private fun profile(profileId: String) = runCatching {
                    profiles.load().firstOrNull { it.id == profileId }
                }.getOrNull()
            },
        )
        connectivity = ConnectivityMonitor(this, scope) { networkId ->
            coordinator.submit(TunnelCommand.NetworkChanged(networkId))
        }
        runCatching { connectivity.start() }.onFailure { error ->
            RedactingLog.write("Connectivity monitor unavailable: ${error.javaClass.simpleName}")
        }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                screenOnReceiver,
                IntentFilter(Intent.ACTION_SCREEN_ON),
                ContextCompat.RECEIVER_EXPORTED,
            )
            screenOnReceiverRegistered = true
        }.onFailure { error ->
            RedactingLog.write("Screen-on receiver unavailable: ${error.javaClass.simpleName}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!com.cartunnel.client.ui.UserConsent.isAccepted(this)) {
            stopServiceAction(startId)
            return START_NOT_STICKY
        }
        if (intent == null || intent.action == ACTION_RESUME) {
            val saved = states.read()
            val profileId = saved.currentProfileId
            if (!saved.desiredRunning || profileId == null) {
                stopServiceAction(startId)
                return START_NOT_STICKY
            }
            if (!beginForeground("正在恢复", startId)) return START_NOT_STICKY
            coordinator.submit(TunnelCommand.Start(profileId, startId))
            return START_STICKY
        }

        when (intent.action) {
            ACTION_STOP -> coordinator.submit(TunnelCommand.Stop(startId))
            ACTION_START -> {
                val profileId = intent.getStringExtra(EXTRA_PROFILE) ?: return START_NOT_STICKY
                if (!beginForeground("正在连接", startId)) return START_NOT_STICKY
                coordinator.submit(TunnelCommand.Start(profileId, startId))
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        coordinator.submit(TunnelCommand.VpnRevoked)
    }

    override fun onDestroy() {
        if (screenOnReceiverRegistered) {
            runCatching { unregisterReceiver(screenOnReceiver) }.onFailure { error ->
                RedactingLog.write("Screen-on receiver stop failed: ${error.javaClass.simpleName}")
            }
            screenOnReceiverRegistered = false
        }
        runCatching { connectivity.stop() }.onFailure { error ->
            RedactingLog.write("Connectivity monitor stop failed: ${error.javaClass.simpleName}")
        }
        runBlocking { coordinator.shutdown() }
        scope.cancel()
        super.onDestroy()
    }

    private fun stopServiceAction(requestToken: Int) {
        stopTokenGate.requestStop(requestToken)
    }

    private fun beginForeground(text: String, requestToken: Int): Boolean {
        if (updateForeground(text)) return true
        states.updateSettings { it.copy(desiredRunning = false) }
        states.publish(TunnelRuntimeState.Error("FOREGROUND_START_FAILED", "系统拒绝启动 VPN 前台服务", false))
        stopServiceAction(requestToken)
        return false
    }

    private fun updateForeground(text: String): Boolean {
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, CarTunnelService::class.java).setAction(ACTION_STOP).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, "tunnel")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("CarTunnel")
            .setContentText(text)
            .setOngoing(true)
            .addAction(0, getString(R.string.stop), stop)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
                    )
                } catch (error: RuntimeException) {
                    RedactingLog.write("VPN systemExempted foreground rejected: ${error.javaClass.simpleName}; using specialUse")
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                    )
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (error: RuntimeException) {
            RedactingLog.write("Foreground update failed: ${error.javaClass.simpleName}")
            false
        }
    }

    private fun channel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("tunnel", getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onBind(intent: Intent?): IBinder? = if (SERVICE_INTERFACE == intent?.action) super.onBind(intent) else null
}
