package com.cartunnel.client.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStopTokenGateTest {
    @Test
    fun oldStopTokenCannotStopNewerStart() {
        val service = FakeServiceStartIds(latestStartId = 3)
        val gate = service.gate()

        assertFalse(gate.requestStop(2))
        assertEquals(listOf(2), service.stopSelfResultTokens)
        assertEquals(0, service.removedNotifications)
    }

    @Test
    fun stopTokenEndsServiceWhenThereIsNoNewerRequest() {
        val service = FakeServiceStartIds(latestStartId = 2)

        assertTrue(service.gate().requestStop(2))
        assertEquals(1, service.stoppedCount)
        assertEquals(1, service.removedNotifications)
    }

    @Test
    fun oldPermissionAndTerminalFailureCannotStopUpdatedStart() {
        val service = FakeServiceStartIds(latestStartId = 3)
        val gate = service.gate()

        assertFalse(gate.requestStop(2)) // PermissionRequired from Start token 2.
        assertFalse(gate.requestStop(2)) // TerminalFailure from Start token 2.
        assertEquals(0, service.removedNotifications)
        assertEquals(0, service.stoppedCount)
    }

    @Test
    fun singleStopRemovesNotificationOnlyAfterServiceEnds() {
        val service = FakeServiceStartIds(latestStartId = 8)
        val gate = service.gate()

        assertTrue(gate.requestStop(8))
        assertEquals(1, service.stoppedCount)
        assertEquals(1, service.removedNotifications)
    }

    private class FakeServiceStartIds(var latestStartId: Int) {
        val stopSelfResultTokens = mutableListOf<Int>()
        var stoppedCount = 0
        var removedNotifications = 0

        fun gate() = ServiceStopTokenGate(
            stopSelfResult = { token ->
                stopSelfResultTokens += token
                if (token == latestStartId) {
                    stoppedCount++
                    true
                } else {
                    false
                }
            },
            removeForeground = { removedNotifications++ },
        )
    }
}
