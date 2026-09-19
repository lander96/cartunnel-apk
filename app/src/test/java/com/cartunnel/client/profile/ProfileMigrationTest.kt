package com.cartunnel.client.profile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProfileMigrationTest {
    private fun legacy() = JSONObject("""{"schemaVersion":1,"id":"keep","name":"车机","server":"example.com","port":8443,"uuid":"00000000-0000-4000-8000-000000000000","protocol":"vmess","transport":"ws","security":"none","wsHost":"example.com","wsPath":"/car"}""")
    @Test fun mixedLegacyDamagedAndSelectedAreMigrated() {
        val input = JSONArray().put(legacy()).put(legacy().put("id", "old").put("protocol", "vless"))
            .put(JSONObject().put("schemaVersion",1)).put("broken")
        val result = ProfileMigration.migrate(input, "old")
        assertEquals(listOf("keep"), result.profiles.map { it.id })
        assertEquals("keep", result.selectedId)
        assertEquals(3, result.removedCount)
        assertEquals(2, result.profiles.single().schemaVersion)
    }
    @Test fun migrationIsIdempotentAndRepairsEmptySelection() {
        val first = ProfileMigration.migrate(JSONArray().put(legacy()), null)
        val second = ProfileMigration.migrate(JSONArray(ProfileJsonCodec.encode(first.profiles)), first.selectedId)
        assertEquals(first.profiles, second.profiles)
        assertEquals("keep", second.selectedId)
        assertEquals(0, second.removedCount)
        assertNull(ProfileMigration.migrate(JSONArray(), "missing").selectedId)
    }
    @Test fun unsupportedModesAreNeverSilentlyNormalized() {
        for (bad in listOf(legacy().put("security","tls"), legacy().put("alterId",1), legacy().put("transport","tcp"))) {
            assertTrue(runCatching { ProfileJsonCodec.fromJson(bad) }.isFailure)
        }
    }
}
