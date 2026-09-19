package com.cartunnel.client.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface TunnelSettingsRepository {
    fun read(): TunnelSettings
    fun write(settings: TunnelSettings)
}

interface TunnelStateSink {
    fun publish(state: TunnelRuntimeState)
}

sealed class TunnelStartResult {
    data object Started : TunnelStartResult()
    data object PermissionRequired : TunnelStartResult()
}

enum class TunnelFailureKind {
    READINESS_TIMEOUT,
    STARTUP_FAILURE,
}

class TunnelStartFailure(
    val code: String,
    val safeMessage: String,
    val kind: TunnelFailureKind,
    val recoverable: Boolean,
    cause: Throwable? = null,
) : Exception(code, cause)

interface TunnelRuntime {
    suspend fun start(profileId: String): TunnelStartResult
    suspend fun stop()
    suspend fun measureLatency(): Long? = null
}

interface TunnelHealthSink {
    fun testing(profileId: String)
    fun available(profileId: String, latencyMs: Long)
    fun failed(profileId: String, safeErrorCode: String)
}

interface TunnelSessionCallbacks {
    fun onPermissionRequired(requestToken: Int) {}
    fun onStopped(requestToken: Int) {}
    fun onTerminalFailure(requestToken: Int) {}
}

/** The single serialized owner of user intent, epoch, runtime and retry jobs. */
class TunnelSessionCoordinator(
    private val scope: CoroutineScope,
    private val settings: TunnelSettingsRepository,
    private val stateSink: TunnelStateSink,
    private val runtime: TunnelRuntime,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val callbacks: TunnelSessionCallbacks = object : TunnelSessionCallbacks {},
    private val healthSink: TunnelHealthSink? = null,
) {
    private data class Envelope(
        val command: TunnelCommand,
        val completed: CompletableDeferred<Unit>? = null,
    )

    private val commands = Channel<Envelope>(Channel.UNLIMITED)
    private val stateMutex = Mutex()
    private val initialSettings = settings.read()
    private val worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        for (envelope in commands) {
            try {
                handle(envelope.command)
                envelope.completed?.complete(Unit)
            } catch (e: CancellationException) {
                envelope.completed?.cancel(e)
                throw e
            } catch (e: Throwable) {
                envelope.completed?.completeExceptionally(e)
            }
        }
    }

    @Volatile private var currentEpoch = 0L
    @Volatile private var desiredRunning = initialSettings.desiredRunning
    @Volatile private var currentProfileId = initialSettings.currentProfileId
    @Volatile private var autoReconnect = initialSettings.autoReconnect
    @Volatile private var reconnectOnNetworkChange = initialSettings.reconnectOnNetworkChange
    @Volatile private var reconnectOnWake = initialSettings.reconnectOnWake
    @Volatile private var currentSessionToken = 0
    @Volatile private var publishedState: TunnelRuntimeState = TunnelRuntimeState.Stopped
    @Volatile private var operationJob: Job? = null
    @Volatile private var retryJob: Job? = null
    @Volatile private var healthJob: Job? = null

    val state: TunnelRuntimeState get() = publishedState
    val epoch: Long get() = currentEpoch
    val hasActiveOperationJob: Boolean get() = operationJob?.isActive == true
    val hasActiveRetryJob: Boolean get() = retryJob?.isActive == true
    val hasActiveHealthJob: Boolean get() = healthJob?.isActive == true

    @Volatile
    var retryLoopsCreated: Int = 0
        private set

    fun submit(command: TunnelCommand): Boolean = commands.trySend(Envelope(command)).isSuccess

    suspend fun dispatch(command: TunnelCommand) {
        val completed = CompletableDeferred<Unit>()
        commands.send(Envelope(command, completed))
        completed.await()
    }

    suspend fun shutdown() {
        val jobs = stateMutex.withLock {
            currentEpoch++
            desiredRunning = false
            takeTrackedJobsLocked()
        }
        jobs.cancelAndJoin()
        cleanupRuntime()
        commands.close()
        worker.cancelAndJoin()
    }

    private suspend fun handle(command: TunnelCommand) {
        when (command) {
            is TunnelCommand.Start -> handleStart(command.profileId, command.requestToken)
            is TunnelCommand.Stop -> handleStop(command.requestToken)
            is TunnelCommand.NetworkChanged -> handleNetworkChanged(command.id)
            TunnelCommand.WakeResume -> handleWakeResume()
            TunnelCommand.VpnRevoked -> handleVpnRevoked()
        }
    }

    private data class StartPlan(
        val epoch: Long,
        val profileId: String,
        val requestToken: Int,
        val jobs: List<Job>,
        val sameHealthySession: Boolean,
    )

    private suspend fun handleStart(profileId: String, requestToken: Int) {
        val latest = settings.read()
        val start = stateMutex.withLock {
            autoReconnect = latest.autoReconnect
            reconnectOnNetworkChange = latest.reconnectOnNetworkChange
            reconnectOnWake = latest.reconnectOnWake
            val sameHealthySession = desiredRunning &&
                currentProfileId == profileId &&
                publishedState is TunnelRuntimeState.Connected &&
                operationJob?.isActive != true &&
                retryJob?.isActive != true
            val epoch = currentEpoch + 1
            currentEpoch = epoch
            desiredRunning = true
            currentProfileId = profileId
            currentSessionToken = requestToken
            StartPlan(
                epoch = epoch,
                profileId = profileId,
                requestToken = requestToken,
                jobs = if (sameHealthySession) emptyList() else takeTrackedJobsLocked(),
                sameHealthySession = sameHealthySession,
            )
        }

        if (start.sameHealthySession) {
            settings.write(settings.read().copy(currentProfileId = profileId, desiredRunning = true))
            return
        }

        start.jobs.cancelAndJoin()
        cleanupRuntime()
        settings.write(settings.read().copy(currentProfileId = profileId, desiredRunning = true))
        launchOperation(start.epoch, start.profileId, start.requestToken, scheduleRetryOnFailure = true, publishStarting = true)
    }

    private suspend fun handleStop(requestToken: Int) {
        val jobs = stateMutex.withLock {
            currentEpoch++
            desiredRunning = false
            settings.write(settings.read().copy(desiredRunning = false))
            takeTrackedJobsLocked()
        }
        jobs.cancelAndJoin()
        publishUnconditionally(TunnelRuntimeState.Stopping)
        cleanupRuntime()
        publishUnconditionally(TunnelRuntimeState.Stopped)
        callbacks.onStopped(requestToken)
    }

    private suspend fun handleNetworkChanged(networkId: String?) {
        reconnectOnNetworkChange = settings.read().reconnectOnNetworkChange
        if (networkId == null || !reconnectOnNetworkChange) return
        handleRebuildHint("底层网络变化", networkId)
    }

    private suspend fun handleWakeResume() {
        reconnectOnWake = settings.read().reconnectOnWake
        if (!reconnectOnWake) return
        handleRebuildHint("唤醒恢复", null)
    }

    private suspend fun handleRebuildHint(reason: String, networkId: String?) {
        val continueAfterFailure = settings.read().autoReconnect
        val restart = stateMutex.withLock {
            if (!desiredRunning || currentProfileId == null || retryJob?.isActive == true) return@withLock null
            currentEpoch++
            val request = RestartRequest(
                epoch = currentEpoch,
                profileId = currentProfileId!!,
                requestToken = currentSessionToken,
                reason = reason,
                networkId = networkId,
                continueAfterFailure = continueAfterFailure,
            )
            request to takeTrackedJobsLocked()
        } ?: return

        val (request, jobs) = restart
        jobs.cancelAndJoin()
        scheduleRetry(request)
    }

    private suspend fun handleVpnRevoked() {
        val (jobs, requestToken) = stateMutex.withLock {
            currentEpoch++
            desiredRunning = false
            settings.write(settings.read().copy(desiredRunning = false))
            takeTrackedJobsLocked() to currentSessionToken
        }
        jobs.cancelAndJoin()
        cleanupRuntime()
        publishUnconditionally(TunnelRuntimeState.Error("VPN_REVOKED", "系统 VPN 授权已撤销", recoverable = false))
        callbacks.onTerminalFailure(requestToken)
    }

    private data class RestartRequest(
        val epoch: Long,
        val profileId: String,
        val requestToken: Int,
        val reason: String,
        val networkId: String?,
        val continueAfterFailure: Boolean,
    )

    private suspend fun launchOperation(
        epoch: Long,
        profileId: String,
        requestToken: Int,
        scheduleRetryOnFailure: Boolean,
        publishStarting: Boolean,
    ) {
        stateMutex.withLock {
            if (!isCurrentLocked(epoch)) return@withLock
            val job = scope.launch(start = CoroutineStart.LAZY) {
                runStartup(epoch, profileId, requestToken, scheduleRetryOnFailure, publishStarting)
            }
            operationJob = job
            job.start()
        }
    }

    private suspend fun runStartup(
        epoch: Long,
        profileId: String,
        requestToken: Int,
        scheduleRetryOnFailure: Boolean,
        publishStarting: Boolean,
    ) {
        if (publishStarting) publishIfCurrent(epoch, TunnelRuntimeState.Starting("启动 Xray"))
        try {
            when (runtime.start(profileId)) {
                TunnelStartResult.PermissionRequired -> {
                    if (!isCurrent(epoch)) return
                    stateMutex.withLock {
                        if (!isCurrentLocked(epoch)) return@withLock
                        desiredRunning = false
                        settings.write(settings.read().copy(desiredRunning = false, currentProfileId = profileId))
                        publishedState = TunnelRuntimeState.PermissionRequired
                        stateSink.publish(publishedState)
                    }
                    callbacks.onPermissionRequired(requestToken)
                }

                TunnelStartResult.Started -> {
                    if (publishIfCurrent(epoch, TunnelRuntimeState.Connected(profileId, System.currentTimeMillis()))) {
                        launchHealth(epoch, profileId)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val failure = e as? TunnelStartFailure ?: TunnelStartFailure(
                code = "CORE_INIT_FAILED",
                safeMessage = "连接启动失败",
                kind = TunnelFailureKind.STARTUP_FAILURE,
                recoverable = true,
                cause = e,
            )
            if (!isCurrent(epoch)) return
            if (!cleanupIfCurrent(epoch)) return
            publishIfCurrent(epoch, TunnelRuntimeState.Error(failure.code, failure.safeMessage, failure.recoverable))
            autoReconnect = settings.read().autoReconnect
            if (failure.recoverable && autoReconnect && scheduleRetryOnFailure && isCurrent(epoch)) {
                scheduleRetry(
                    RestartRequest(
                        epoch = epoch,
                        profileId = profileId,
                        requestToken = requestToken,
                        reason = "启动失败",
                        networkId = null,
                        continueAfterFailure = true,
                    ),
                )
            } else if (!failure.recoverable) {
                stateMutex.withLock {
                    if (!isCurrentLocked(epoch)) return@withLock
                    desiredRunning = false
                    settings.write(settings.read().copy(desiredRunning = false))
                }
                callbacks.onTerminalFailure(requestToken)
            }
        }
    }

    private suspend fun launchHealth(epoch: Long, profileId: String) {
        stateMutex.withLock {
            if (!isCurrentLocked(epoch)) return@withLock
            healthJob?.cancel()
            healthJob = scope.launch(start = CoroutineStart.LAZY) { runHealth(epoch, profileId) }
            healthJob?.start()
        }
    }

    private suspend fun runHealth(epoch: Long, profileId: String) {
        healthSink?.testing(profileId)
        try {
            val latency = runtime.measureLatency() ?: return
            if (!isCurrent(epoch)) return
            healthSink?.available(profileId, latency)
            publishLatencyIfCurrent(epoch, profileId, latency)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            if (isCurrent(epoch)) healthSink?.failed(profileId, "ENDPOINT_UNREACHABLE")
        }
    }

    private suspend fun publishLatencyIfCurrent(epoch: Long, profileId: String, latencyMs: Long) {
        stateMutex.withLock {
            val connected = publishedState as? TunnelRuntimeState.Connected ?: return@withLock
            if (!isCurrentLocked(epoch) || connected.profileId != profileId) return@withLock
            publishedState = connected.copy(latencyMs = latencyMs)
            stateSink.publish(publishedState)
        }
    }

    private suspend fun scheduleRetry(request: RestartRequest) {
        stateMutex.withLock {
            if (!isCurrentLocked(request.epoch) || retryJob?.isActive == true) return@withLock
            val job = scope.launch(start = CoroutineStart.LAZY) { retryLoop(request) }
            retryJob = job
            retryLoopsCreated++
            job.start()
        }
    }

    private suspend fun retryLoop(request: RestartRequest) {
        var attempt = 0
        while (currentCoroutineContext().isActive && isCurrent(request.epoch)) {
            publishIfCurrent(epoch = request.epoch, state = TunnelRuntimeState.Reconnecting(attempt + 1, request.reason))
            wait(reconnectPolicy.delay(attempt))
            currentCoroutineContext().ensureActive()
            if (!isCurrent(request.epoch)) return
            if (!cleanupIfCurrent(request.epoch)) return
            launchOperation(request.epoch, request.profileId, request.requestToken, scheduleRetryOnFailure = false, publishStarting = false)
            awaitCurrentOperation(request.epoch)
            if (!isCurrent(request.epoch)) return
            if (isConnected(request.epoch)) return
            if (!request.continueAfterFailure || !settings.read().autoReconnect) return
            attempt++
        }
    }

    private suspend fun publishIfCurrent(epoch: Long, state: TunnelRuntimeState): Boolean = stateMutex.withLock {
        if (!isCurrentLocked(epoch)) return@withLock false
        publishedState = state
        stateSink.publish(state)
        true
    }

    private suspend fun publishUnconditionally(state: TunnelRuntimeState) {
        stateMutex.withLock {
            publishedState = state
            stateSink.publish(state)
        }
    }

    private suspend fun isCurrent(epoch: Long): Boolean = stateMutex.withLock { isCurrentLocked(epoch) }

    private suspend fun isConnected(epoch: Long): Boolean = stateMutex.withLock {
        isCurrentLocked(epoch) && publishedState is TunnelRuntimeState.Connected
    }

    private suspend fun awaitCurrentOperation(epoch: Long) {
        val job = stateMutex.withLock { if (isCurrentLocked(epoch)) operationJob else null }
        job?.join()
    }

    private fun isCurrentLocked(epoch: Long): Boolean = currentEpoch == epoch && desiredRunning

    private fun takeTrackedJobsLocked(): List<Job> {
        val jobs = listOfNotNull(operationJob, retryJob, healthJob)
        operationJob = null
        retryJob = null
        healthJob = null
        return jobs.distinct()
    }

    private suspend fun List<Job>.cancelAndJoin() {
        forEach(Job::cancel)
        joinAll()
    }

    private suspend fun cleanupRuntime() {
        withContext(kotlinx.coroutines.NonCancellable) {
            try {
                runtime.stop()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Cleanup is best effort; the coordinator still completes the transition.
            }
        }
    }

    private suspend fun cleanupIfCurrent(epoch: Long): Boolean = stateMutex.withLock {
        if (!isCurrentLocked(epoch)) return@withLock false
        cleanupRuntime()
        true
    }
}
