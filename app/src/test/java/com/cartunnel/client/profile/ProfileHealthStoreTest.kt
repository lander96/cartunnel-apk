package com.cartunnel.client.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileHealthStoreTest {
    private val profile = VmessWsProfile(
        id = "profile-a",
        name = "节点 A",
        server = "203.0.113.1",
        uuid = "00000000-0000-4000-8000-000000000000",
        wsHost = "example.com",
    )

    @Test
    fun profileHealthPersistsAcrossRepositoryRecreation() {
        val storage = MemoryHealthStorage()
        ProfileHealthStore(storage) { 100L }.markAvailable(profile, 86L)

        val record = ProfileHealthStore(storage) { 200L }.read(profile)

        assertEquals(ProfileHealthStatus.AVAILABLE, record.status)
        assertEquals(86L, record.latencyMs)
        assertEquals(100L, record.checkedAt)
    }

    @Test
    fun profileEditReturnsStaleHealthAndDeleteRemovesIt() {
        val storage = MemoryHealthStorage()
        val store = ProfileHealthStore(storage) { 100L }
        store.markAvailable(profile, 86L)

        val edited = profile.copy(server = "203.0.113.2")
        assertEquals(ProfileHealthStatus.STALE, store.read(edited).status)

        store.delete(profile.id)
        assertEquals(ProfileHealthStatus.UNTESTED, store.read(edited).status)
    }

    private class MemoryHealthStorage : HealthStorage {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
    }
}
