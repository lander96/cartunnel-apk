package com.cartunnel.client.boot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeCooldownTest {
    @Test
    fun duplicateWakeEventsWithinCooldownAreSuppressed() {
        var stored = Long.MIN_VALUE
        var now = 1_000L
        val gate = ResumeCooldown({ stored }, { stored = it }, { now })

        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
        now += 5_000L
        assertTrue(gate.tryAcquire())
    }

    @Test
    fun deferredBootResumeIsConsumedOnlyOnce() {
        var pending = false
        val deferred = DeferredBootResume({ pending }, { pending = it })

        assertFalse(deferred.consume())
        deferred.mark()
        assertTrue(deferred.consume())
        assertFalse(deferred.consume())
    }
}
