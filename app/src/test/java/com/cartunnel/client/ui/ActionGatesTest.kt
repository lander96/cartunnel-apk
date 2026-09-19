package com.cartunnel.client.ui

import org.junit.Assert.*
import org.junit.Test

class ActionGatesTest {
    @Test fun rapidClicksAndOldCompletionsCannotReleaseNewAction() {
        var now = 0L
        val gate = SingleFlightGate(clock = { now })
        val first = gate.begin("connect")!!
        assertNull(gate.begin("connect"))
        gate.finish(first)
        assertNull(gate.begin("connect"))
        now = 1000
        val second = gate.begin("connect")!!
        gate.finish(first)
        assertNull(gate.begin("connect"))
        gate.finish(second)
    }

    @Test fun abnormalResultWithRealPermissionStartsExactlyOnce() {
        val gate = PendingVpnStartGate()
        val pending = gate.request("node")!!
        assertNull(gate.request("node"))
        assertEquals("node", gate.consume(pending.generation, granted = true)?.profileId)
        assertNull(gate.consume(pending.generation, granted = true))
    }

    @Test fun cancelAndStaleCallbackNeverStart() {
        val gate = PendingVpnStartGate()
        val old = gate.request("old")!!
        assertNull(gate.consume(old.generation, granted = false))
        gate.cancel(old.generation)
        val fresh = gate.request("new")!!
        assertNull(gate.consume(old.generation, granted = true))
        assertEquals("new", gate.consume(fresh.generation, granted = true)?.profileId)
    }
}
