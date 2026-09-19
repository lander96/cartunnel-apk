package com.cartunnel.client.profile
import org.junit.Assert.*
import org.junit.Test
class ProfileJsonCodecTest {
    private val p = VmessWsProfile(id="id",name="节点",server="example.com",uuid="00000000-0000-4000-8000-000000000000",wsHost="example.com",wsPath="/car")
    @Test fun schemaTwoRoundTripContainsOnlySupportedFields() {
        val json = ProfileJsonCodec.toJson(p)
        assertEquals(2, json.getInt("schemaVersion"))
        assertEquals(setOf("schemaVersion","id","name","server","port","uuid","wsHost","wsPath"), json.keySet())
        assertEquals(listOf(p), ProfileJsonCodec.decode(ProfileJsonCodec.encode(listOf(p))))
    }
    @Test fun acceptsDeploymentSchemaOne() {
        val legacy = ProfileJsonCodec.toJson(p).put("schemaVersion",1).put("protocol","vmess").put("transport","ws").put("security","none")
        assertEquals(p, ProfileJsonCodec.fromJson(legacy))
    }
    @Test fun rejectsUnknownSchemaEmptyAndDuplicateImports() {
        assertTrue(runCatching { ProfileJsonCodec.fromJson(ProfileJsonCodec.toJson(p).put("schemaVersion",3)) }.isFailure)
        assertTrue(runCatching { ProfileJsonCodec.decode("[]") }.isFailure)
        assertTrue(runCatching { ProfileJsonCodec.decode(ProfileJsonCodec.encode(listOf(p,p))) }.isFailure)
    }
}
