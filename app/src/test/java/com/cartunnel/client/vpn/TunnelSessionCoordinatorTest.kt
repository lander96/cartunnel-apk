package com.cartunnel.client.vpn

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TunnelSessionCoordinatorTest {
    @Test
    fun successfulStartupPublishesConnected() = runTest {
        val h = Harness(this)
        h.runtime.enqueue(StartOutcome.Success)

        h.coordinator.submit(TunnelCommand.Start("profile-a"))
        runCurrent()

        assertTrue(h.sink.states.last() is TunnelRuntimeState.Connected)
        h.close()
    }

    @Test
    fun healthyCoreNetworkChangeForcesRebuild() = runTest {
        val h = Harness(this)
        h.startAndRun("profile-a")

        h.coordinator.submit(TunnelCommand.NetworkChanged("wifi-2"))
        runCurrent()
        assertTrue(h.sink.states.last() is TunnelRuntimeState.Reconnecting)
        assertEquals(1, h.runtime.startCalls.size)

        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(2, h.runtime.startCalls.size)
        assertEquals(listOf("profile-a", "profile-a"), h.runtime.startCalls)
        assertTrue(h.sink.states.last() is TunnelRuntimeState.Connected)
        assertTrue(h.runtime.stopCalls > 0)
        h.close()
    }

    @Test
    fun retryDoesNotCancelItself() = runTest {
        val h = Harness(this)
        h.runtime.enqueue(StartOutcome.Failure(recoverable = true))
        h.runtime.enqueue(StartOutcome.Success)

        h.coordinator.submit(TunnelCommand.Start("profile-a"))
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(2, h.runtime.startCalls.size)
        assertTrue(h.sink.states.last() is TunnelRuntimeState.Connected)
        assertFalse(h.coordinator.hasActiveRetryJob)
        h.close()
    }

    @Test
    fun consecutiveFailuresUseBoundedBackoff() = runTest {
        val h = Harness(this)
        repeat(8) { h.runtime.enqueue(StartOutcome.Failure(recoverable = true)) }
        h.coordinator.submit(TunnelCommand.Start("profile-a"))
        runCurrent()

        val expected = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 30_000L)
        assertEquals(listOf(1_000L), h.delays)
        expected.drop(1).forEach { wait ->
            advanceTimeBy(wait)
            runCurrent()
        }
        assertEquals(expected, h.delays)
        assertTrue(h.runtime.startCalls.size >= expected.size)
        h.close()
    }

    @Test
    fun stopCancelsPendingRetry() = runTest {
        val h = Harness(this)
        h.runtime.enqueue(StartOutcome.Failure(recoverable = true))
        h.coordinator.submit(TunnelCommand.Start("profile-a"))
        runCurrent()
        val startsBeforeStop = h.runtime.startCalls.size

        h.coordinator.submit(TunnelCommand.Stop(2))
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(startsBeforeStop, h.runtime.startCalls.size)
        assertEquals(TunnelRuntimeState.Stopped, h.sink.states.last())
        assertFalse(h.settings.value.desiredRunning)
        h.close()
    }

    @Test
    fun stopCancelsStartupWithoutError() = runTest {
        val h = Harness(this)
        h.runtime.enqueue(StartOutcome.Suspend)
        h.coordinator.submit(TunnelCommand.Start("profile-a"))
        runCurrent()
        assertTrue(h.coordinator.hasActiveOperationJob)

        val stopDispatch = async { h.coordinator.dispatch(TunnelCommand.Stop(2)) }
        runCurrent()
        stopDispatch.await()

        assertEquals(1, h.runtime.startupCancellationCount)
        assertEquals(TunnelRuntimeState.Stopped, h.sink.states.last())
        assertFalse(h.sink.states.any { it is TunnelRuntimeState.Error })
        assertTrue(h.delays.isEmpty())
        assertFalse(h.coordinator.hasActiveOperationJob)
        assertFalse(h.coordinator.hasActiveRetryJob)
        h.close()
    }

    @Test
    fun staleSuccessCannotOverwriteStopped() = runTest {
        val h = NonCancellableHarness(this)
        h.coordinator.submit(TunnelCommand.Start("old"))
        runCurrent()
        h.coordinator.submit(TunnelCommand.Stop(2))
        runCurrent()
        h.runtime.completePendingSuccess()
        runCurrent()

        assertEquals(TunnelRuntimeState.Stopped, h.sink.states.last())
        assertFalse(h.sink.states.dropWhile { it != TunnelRuntimeState.Stopped }.any { it is TunnelRuntimeState.Connected })
        h.close()
    }

    @Test
    fun staleFailureCannotScheduleRetry() = runTest {
        val h = NonCancellableHarness(this)
        h.coordinator.submit(TunnelCommand.Start("old"))
        runCurrent()
        h.coordinator.submit(TunnelCommand.Stop(2))
        runCurrent()
        h.runtime.completePendingFailure()
        runCurrent()
        val startsAfterStop = h.runtime.startCalls.size

        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(startsAfterStop, h.runtime.startCalls.size)
        assertEquals(0, h.coordinator.retryLoopsCreated)
        assertFalse(h.coordinator.hasActiveRetryJob)
        assertEquals(TunnelRuntimeState.Stopped, h.sink.states.last())
        h.close()
    }

    @Test
    fun rapidStartStopStartKeepsLatestProfile() = runTest {
        val h = Harness(this)
        h.runtime.enqueue(StartOutcome.Suspend)
        h.runtime.enqueue(StartOutcome.Success)

        h.coordinator.submit(TunnelCommand.Start("profile-old", 2))
        runCurrent()
        assertEquals(listOf("profile-old"), h.runtime.startCalls)
        h.coordinator.submit(TunnelCommand.Stop(2))
        h.coordinator.submit(TunnelCommand.Start("profile-new", 3))
        runCurrent()

        assertEquals("profile-new", h.settings.value.currentProfileId)
        assertTrue(h.settings.value.desiredRunning)
        assertTrue(h.sink.states.last() is TunnelRuntimeState.Connected)
        assertEquals(listOf("profile-old", "profile-new"), h.runtime.startCalls)
        assertEquals(1, h.runtime.startupCancellationCount)
        h.close()
    }

    @Test
    fun stopLeavesNoOperationOrRetryJob() = runTest {
        val h = Harness(this)
        h.runtime.enqueue(StartOutcome.Failure(recoverable = true))
        h.coordinator.submit(TunnelCommand.Start("profile-a"))
        runCurrent()
        assertTrue(h.coordinator.hasActiveRetryJob)

        h.coordinator.submit(TunnelCommand.Stop(2))
        runCurrent()

        assertFalse(h.coordinator.hasActiveOperationJob)
        assertFalse(h.coordinator.hasActiveRetryJob)
        assertEquals(TunnelRuntimeState.Stopped, h.sink.states.last())
        h.close()
    }

    @Test
    fun cleanupOrderIsHevTunXray() = runTest {
        val h = Harness(this)
        h.startAndRun("profile-a")
        h.runtime.cleanupEvents.clear()

        h.coordinator.submit(TunnelCommand.Stop(2))
        runCurrent()

        assertEquals(listOf("HEV_STOP", "TUN_CLOSE", "XRAY_STOP"), h.runtime.cleanupEvents)
        h.close()
    }

    @Test
    fun permissionRequiredClearsIntentAndStopsServiceAction() = runTest {
        val h = Harness(this)
        h.runtime.enqueue(StartOutcome.PermissionRequired)
        h.coordinator.submit(TunnelCommand.Start("profile-a", 7))
        runCurrent()

        assertEquals(TunnelRuntimeState.PermissionRequired, h.sink.states.last())
        assertFalse(h.settings.value.desiredRunning)
        assertEquals(1, h.callbacks.permissionRequired)
        assertEquals(listOf(7), h.callbacks.permissionTokens)
        assertEquals(1, h.callbacks.stopRequests)
        h.close()
    }

    @Test
    fun repeatedNetworkChangesDoNotCreateParallelLoops() = runTest {
        val h = Harness(this)
        h.startAndRun("profile-a")
        h.coordinator.submit(TunnelCommand.NetworkChanged("one"))
        h.coordinator.submit(TunnelCommand.NetworkChanged("two"))
        h.coordinator.submit(TunnelCommand.NetworkChanged("three"))
        runCurrent()

        assertEquals(1, h.coordinator.retryLoopsCreated)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, h.runtime.startCalls.size)
        assertEquals(1, h.runtime.maxConcurrentStarts)
        h.close()
    }

    @Test
    fun multiDispatcherEpochVisibilityBlocksStalePublish() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = FakeSettings()
        val sink = RecordingStateSink()
        val runtime = CancellableFakeRuntime()
        val callbacks = RecordingCallbacks()
        val coordinator = TunnelSessionCoordinator(scope, settings, sink, runtime, callbacks = callbacks)
        runtime.enqueue(StartOutcome.Suspend)
        try {
            coordinator.dispatch(TunnelCommand.Start("old", 1))
            runtime.started.await()
            val startEpoch = coordinator.epoch
            val stopDispatch = async(Dispatchers.Default) { coordinator.dispatch(TunnelCommand.Stop(2)) }
            withTimeout(2_000) { while (coordinator.epoch == startEpoch) delay(1) }
            stopDispatch.await()

            assertEquals(1, runtime.startupCancellationCount)
            assertEquals(TunnelRuntimeState.Stopped, sink.states.last())
            assertFalse(sink.states.any { it is TunnelRuntimeState.Connected })
            assertFalse(sink.states.any { it is TunnelRuntimeState.Error })
            assertFalse(coordinator.hasActiveOperationJob)
            assertFalse(coordinator.hasActiveRetryJob)
        } finally {
            coordinator.shutdown()
            scope.cancel()
        }
    }

    private class Harness(private val testScope: TestScope) {
        val settings = FakeSettings()
        val sink = RecordingStateSink()
        val runtime = CancellableFakeRuntime()
        val callbacks = RecordingCallbacks()
        val delays = mutableListOf<Long>()
        val coordinator = TunnelSessionCoordinator(
            scope = testScope,
            settings = settings,
            stateSink = sink,
            runtime = runtime,
            wait = { millis -> delays += millis; delay(millis) },
            callbacks = callbacks,
        )

        suspend fun startAndRun(profileId: String) {
            coordinator.submit(TunnelCommand.Start(profileId))
            testScope.runCurrent()
            assertTrue(sink.states.last() is TunnelRuntimeState.Connected)
        }

        suspend fun close() = coordinator.shutdown()
    }

    private class NonCancellableHarness(private val testScope: TestScope) {
        val settings = FakeSettings()
        val sink = RecordingStateSink()
        val runtime = NonCancellableFakeRuntime()
        val callbacks = RecordingCallbacks()
        val coordinator = TunnelSessionCoordinator(testScope, settings, sink, runtime, callbacks = callbacks)
        suspend fun close() = coordinator.shutdown()
    }

    private class FakeSettings(var value: TunnelSettings = TunnelSettings()) : TunnelSettingsRepository {
        override fun read() = value
        override fun write(settings: TunnelSettings) { value = settings }
    }

    private class RecordingStateSink : TunnelStateSink {
        val states = CopyOnWriteArrayList<TunnelRuntimeState>()
        override fun publish(state: TunnelRuntimeState) { states += state }
    }

    private class RecordingCallbacks : TunnelSessionCallbacks {
        @Volatile var permissionRequired = 0
        @Volatile var stopRequests = 0
        val permissionTokens = CopyOnWriteArrayList<Int>()
        override fun onPermissionRequired(requestToken: Int) { permissionRequired++; permissionTokens += requestToken; stopRequests++ }
        override fun onStopped(requestToken: Int) { stopRequests++ }
        override fun onTerminalFailure(requestToken: Int) { stopRequests++ }
    }

    private sealed class StartOutcome {
        data object Success : StartOutcome()
        data object PermissionRequired : StartOutcome()
        data object Suspend : StartOutcome()
        data class Failure(val recoverable: Boolean) : StartOutcome()
    }

    private class CancellableFakeRuntime : TunnelRuntime {
        val startCalls = CopyOnWriteArrayList<String>()
        val cleanupEvents = CopyOnWriteArrayList<String>()
        private val outcomes = ConcurrentLinkedQueue<StartOutcome>()
        private val concurrentStarts = AtomicInteger()
        private val cancellationCount = AtomicInteger()
        private var pending: CancellableContinuation<TunnelStartResult>? = null
        private var active = false
        var maxConcurrentStarts = 0
            private set
        val startupCancellationCount: Int get() = cancellationCount.get()
        val started = CompletableDeferred<Unit>()
        val stopCalls: Int get() = stopCallCount.get()
        private val stopCallCount = AtomicInteger()

        fun enqueue(outcome: StartOutcome) { outcomes.add(outcome) }

        override suspend fun start(profileId: String): TunnelStartResult {
            startCalls += profileId
            val concurrent = concurrentStarts.incrementAndGet()
            synchronized(this) { maxConcurrentStarts = maxOf(maxConcurrentStarts, concurrent) }
            started.complete(Unit)
            try {
                return when (val outcome = outcomes.poll() ?: StartOutcome.Success) {
                    StartOutcome.Success -> { active = true; TunnelStartResult.Started }
                    StartOutcome.PermissionRequired -> TunnelStartResult.PermissionRequired
                    StartOutcome.Suspend -> suspendCancellableCoroutine { continuation ->
                        synchronized(this) { pending = continuation }
                        continuation.invokeOnCancellation { cancellationCount.incrementAndGet() }
                    }
                    is StartOutcome.Failure -> throw TunnelStartFailure(
                        code = "FAKE_START_FAILED",
                        safeMessage = "fake startup failed",
                        kind = TunnelFailureKind.STARTUP_FAILURE,
                        recoverable = outcome.recoverable,
                    )
                }
            } finally {
                concurrentStarts.decrementAndGet()
            }
        }

        override suspend fun stop() {
            stopCallCount.incrementAndGet()
            if (active) {
                cleanupEvents += "HEV_STOP"
                cleanupEvents += "TUN_CLOSE"
                cleanupEvents += "XRAY_STOP"
            }
            active = false
        }
    }

    /** Deliberately ignores cancellation; used only to deliver stale results after Stop. */
    private class NonCancellableFakeRuntime : TunnelRuntime {
        val startCalls = CopyOnWriteArrayList<String>()
        private var pending: Continuation<TunnelStartResult>? = null
        override suspend fun start(profileId: String): TunnelStartResult {
            startCalls += profileId
            return suspendCoroutine { continuation -> synchronized(this) { pending = continuation } }
        }
        override suspend fun stop() = Unit
        fun completePendingSuccess() {
            val continuation = synchronized(this) { pending.also { pending = null } } ?: error("no pending startup")
            continuation.resume(TunnelStartResult.Started)
        }
        fun completePendingFailure() {
            val continuation = synchronized(this) { pending.also { pending = null } } ?: error("no pending startup")
            continuation.resumeWith(Result.failure(TunnelStartFailure("FAKE_STALE_FAILURE", "fake stale failure", TunnelFailureKind.STARTUP_FAILURE, true)))
        }
    }
}
