package com.cartunnel.client.profile

import org.junit.Assert.*
import org.junit.Test

class ProfileDeletionCoordinatorTest {
    private class Store : DeletionRepository {
        var ids = listOf("active", "other")
        var selected: String? = "active"
        var fail = false
        var calls = 0
        override fun deleteAtomically(id: String): String? {
            calls++
            if (fail) error("disk full")
            ids = ids.filterNot { it == id }
            if (selected == id) selected = ids.firstOrNull()
            return selected
        }
    }

    @Test fun unrelatedNodeCanBeDeletedWhileConnected() {
        val store = Store()
        val coordinator = ProfileDeletionCoordinator(store)
        assertEquals(DeleteResult.Deleted("active"), coordinator.request("other", "active", false))
        assertEquals(listOf("active"), store.ids)
    }

    @Test fun runningNodeWaitsForStoppedAndDeletesOnce() {
        val store = Store()
        val coordinator = ProfileDeletionCoordinator(store)
        assertEquals(DeleteResult.WaitingForStop, coordinator.request("active", "active", false))
        assertEquals(0, store.calls)
        assertEquals(DeleteResult.Busy, coordinator.request("active", "active", false))
        assertEquals(DeleteResult.WaitingForStop, coordinator.onState(false))
        assertEquals(DeleteResult.Deleted("other"), coordinator.onState(true))
        assertEquals(DeleteResult.Idle, coordinator.onState(true))
        assertEquals(1, store.calls)
    }

    @Test fun writeFailureRetainsNodeAndAllowsRetry() {
        val store = Store().apply { fail = true }
        val coordinator = ProfileDeletionCoordinator(store)
        assertEquals(DeleteResult.Failed, coordinator.request("active", null, true))
        assertEquals(listOf("active", "other"), store.ids)
        store.fail = false
        assertEquals(DeleteResult.Deleted("other"), coordinator.request("active", null, true))
    }

    @Test fun stopTimeoutDoesNotDelete() {
        val store = Store()
        val coordinator = ProfileDeletionCoordinator(store)
        coordinator.request("active", "active", false)
        coordinator.cancel()
        assertEquals(DeleteResult.Idle, coordinator.onState(true))
        assertEquals(0, store.calls)
    }
}
