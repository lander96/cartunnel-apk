package com.cartunnel.client.vpn

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TunnelSessionIcarPolicyTest {
    @Test
    fun connectivityEndpointFailureDoesNotFailTunnelStartup() = runTest {
        val runtime = FakeRuntime(measurement = Result.failure(IllegalStateException("endpoint unavailable")))
        val health = RecordingHealthSink()
        val h = Harness(this, runtime, TunnelSettings(autoReconnect = false), health)

        h.coordinator.dispatch(TunnelCommand.Start("profile-a"))
        runCurrent()

        assertTrue(h.coordinator.state is TunnelRuntimeState.Connected)
        assertEquals(listOf("ENDPOINT_UNREACHABLE"), health.failures)
        assertFalse(h.coordinator.state is TunnelRuntimeState.Error)
        h.close()
    }

    @Test
    fun socksReadinessTimeoutStillFailsStartup() = runTest {
        val runtime = FakeRuntime(startFailure = TunnelStartFailure(
            code = "CORE_NOT_READY",
            safeMessage = "连接核心未就绪",
            kind = TunnelFailureKind.READINESS_TIMEOUT,
            recoverable = true,
        ))
        val h = Harness(this, runtime, TunnelSettings(autoReconnect = false), RecordingHealthSink())

        h.coordinator.dispatch(TunnelCommand.Start("profile-a"))
        runCurrent()

        assertEquals(TunnelRuntimeState.Error("CORE_NOT_READY", "连接核心未就绪", true), h.coordinator.state)
        assertEquals(0, h.coordinator.retryLoopsCreated)
        h.close()
    }

    @Test
    fun activeNetworkUnavailableDoesNotStopHealthySession() = runTest {
        val runtime = FakeRuntime()
        val h = Harness(this, runtime, TunnelSettings(autoReconnect = false), RecordingHealthSink())
        h.coordinator.dispatch(TunnelCommand.Start("profile-a"))
        runCurrent()

        h.coordinator.dispatch(TunnelCommand.NetworkChanged(null))
        runCurrent()

        assertEquals(1, runtime.startCalls)
        assertTrue(h.coordinator.state is TunnelRuntimeState.Connected)
        h.close()
    }

    @Test
    fun reconnectDisabledPreventsRetryLoop() = runTest {
        val runtime = FakeRuntime(startFailure = TunnelStartFailure(
            code = "CORE_INIT_FAILED",
            safeMessage = "启动失败",
            kind = TunnelFailureKind.STARTUP_FAILURE,
            recoverable = true,
        ))
        val h = Harness(this, runtime, TunnelSettings(autoReconnect = false), RecordingHealthSink())

        h.coordinator.dispatch(TunnelCommand.Start("profile-a"))
        runCurrent()

        assertEquals(0, h.coordinator.retryLoopsCreated)
        h.close()
    }

    @Test
    fun networkChangeReconnectDisabledIgnoresNetworkEvent() = runTest {
        val runtime = FakeRuntime()
        val h = Harness(this, runtime, TunnelSettings(reconnectOnNetworkChange = false), RecordingHealthSink())
        h.coordinator.dispatch(TunnelCommand.Start("profile-a"))
        runCurrent()

        h.coordinator.dispatch(TunnelCommand.NetworkChanged("wifi-2"))
        runCurrent()

        assertEquals(1, runtime.startCalls)
        assertTrue(h.coordinator.state is TunnelRuntimeState.Connected)
        h.close()
    }

    @Test
    fun networkChangeRebuildsWhenFailureRetryIsDisabled() = runTest {
        val runtime = FakeRuntime()
        val h = Harness(
            this,
            runtime,
            TunnelSettings(autoReconnect = false, reconnectOnNetworkChange = true),
            RecordingHealthSink(),
        )
        h.coordinator.dispatch(TunnelCommand.Start("profile-a"))
        runCurrent()

        h.coordinator.dispatch(TunnelCommand.NetworkChanged("wifi-2"))
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(2, runtime.startCalls)
        assertTrue(h.coordinator.state is TunnelRuntimeState.Connected)
        h.close()
    }

    @Test
    fun wakeReconnectDisabledIgnoresWakeEvent() = runTest {
        val runtime = FakeRuntime()
        val h = Harness(this, runtime, TunnelSettings(reconnectOnWake = false), RecordingHealthSink())
        h.coordinator.dispatch(TunnelCommand.Start("profile-a"))
        runCurrent()

        h.coordinator.dispatch(TunnelCommand.WakeResume)
        runCurrent()

        assertEquals(1, runtime.startCalls)
        assertTrue(h.coordinator.state is TunnelRuntimeState.Connected)
        h.close()
    }

    @Test
    fun wakeResumeRebuildsWhenFailureRetryIsDisabled() = runTest {
        val runtime = FakeRuntime()
        val h = Harness(
            this,
            runtime,
            TunnelSettings(autoReconnect = false, reconnectOnWake = true),
            RecordingHealthSink(),
        )
        h.coordinator.dispatch(TunnelCommand.Start("profile-a"))
        runCurrent()

        h.coordinator.dispatch(TunnelCommand.WakeResume)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(2, runtime.startCalls)
        assertTrue(h.coordinator.state is TunnelRuntimeState.Connected)
        h.close()
    }

    private class Harness(
        scope: kotlinx.coroutines.test.TestScope,
        runtime: FakeRuntime,
        initial: TunnelSettings,
        health: RecordingHealthSink,
    ) {
        private val settings = FakeSettings(initial)
        val coordinator = TunnelSessionCoordinator(
            scope = scope,
            settings = settings,
            stateSink = RecordingStateSink(),
            runtime = runtime,
            healthSink = health,
        )

        suspend fun close() = coordinator.shutdown()
    }

    private class FakeSettings(private var value: TunnelSettings) : TunnelSettingsRepository {
        override fun read() = value
        override fun write(settings: TunnelSettings) { value = settings }
    }

    private class RecordingStateSink : TunnelStateSink {
        val states = CopyOnWriteArrayList<TunnelRuntimeState>()
        override fun publish(state: TunnelRuntimeState) { states += state }
    }

    private class RecordingHealthSink : TunnelHealthSink {
        val failures = CopyOnWriteArrayList<String>()
        override fun testing(profileId: String) = Unit
        override fun available(profileId: String, latencyMs: Long) = Unit
        override fun failed(profileId: String, safeErrorCode: String) { failures += safeErrorCode }
    }

    private class FakeRuntime(
        private val startFailure: TunnelStartFailure? = null,
        private val measurement: Result<Long> = Result.success(42L),
    ) : TunnelRuntime {
        var startCalls = 0
        override suspend fun start(profileId: String): TunnelStartResult {
            startCalls++
            startFailure?.let { throw it }
            return TunnelStartResult.Started
        }
        override suspend fun stop() = Unit
        override suspend fun measureLatency(): Long = measurement.getOrThrow()
    }
}
