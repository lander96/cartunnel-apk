package com.cartunnel.client.profile
import org.junit.Assert.*
import org.junit.Test
class ProfileValidatorTest {
    private val p = VmessWsProfile(id="id",name="节点",server="example.com",uuid="00000000-0000-4000-8000-000000000000",wsHost="example.com")
    @Test fun validProfile() { assertTrue(ProfileValidator.validate(p).isEmpty()) }
    @Test fun rejectsHostInjectionInvalidPortPathAndUuid() {
        val errors = ProfileValidator.validate(p.copy(server="https://example.com",port=0,uuid="1-1-1-1-1",wsHost="evil\r\nHost:x",wsPath="missing"))
        assertEquals(setOf("server","port","uuid","wsHost","wsPath"), errors.map { it.field }.toSet())
    }
}
