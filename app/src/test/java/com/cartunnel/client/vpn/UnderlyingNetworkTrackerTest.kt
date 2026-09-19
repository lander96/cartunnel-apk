package com.cartunnel.client.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class UnderlyingNetworkTrackerTest {
    @Test
    fun emptyBaselineDoesNotEmitOfflineChange() {
        val tracker = UnderlyingNetworkTracker()

        assertEquals(UnderlyingNetworkResult.NoChange, tracker.reduce(emptySet()))
        assertEquals(
            UnderlyingNetworkResult.Changed(setOf("cell")),
            tracker.reduce(setOf("cell")),
        )
    }

    @Test
    fun activeNetworkUnavailableDoesNotStopHealthySession() {
        val tracker = UnderlyingNetworkTracker()

        tracker.reduce(setOf("wifi"))

        assertEquals(UnderlyingNetworkResult.NoChange, tracker.reduce(emptySet()))
        assertEquals(
            UnderlyingNetworkResult.Changed(setOf("cell")),
            tracker.reduce(setOf("cell")),
        )
    }

    @Test
    fun duplicateAndReorderedCallbacksDoNotCreateChanges() {
        val tracker = UnderlyingNetworkTracker()

        assertEquals(UnderlyingNetworkResult.NoChange, tracker.reduce(setOf("wifi", "cell")))
        assertEquals(UnderlyingNetworkResult.NoChange, tracker.reduce(setOf("cell", "wifi")))
        assertEquals(UnderlyingNetworkResult.NoChange, tracker.reduce(setOf("wifi", "cell")))
    }

    @Test
    fun vpnNetworkIsNotIncludedInUnderlyingSet() {
        val tracker = UnderlyingNetworkTracker()

        assertEquals(UnderlyingNetworkResult.NoChange, tracker.reduce(emptySet()))
        assertEquals(
            UnderlyingNetworkResult.Changed(setOf("wifi")),
            tracker.reduce(setOf("wifi")),
        )
    }

    @Test
    fun lostThenAvailableProducesOneStableChange() {
        val tracker = UnderlyingNetworkTracker()

        tracker.reduce(setOf("wifi"))
        assertEquals(UnderlyingNetworkResult.NoChange, tracker.reduce(emptySet()))
        assertEquals(
            UnderlyingNetworkResult.Changed(setOf("cell")),
            tracker.reduce(setOf("cell")),
        )
    }
}
