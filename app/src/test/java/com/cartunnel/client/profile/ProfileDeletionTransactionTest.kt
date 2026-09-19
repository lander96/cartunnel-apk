package com.cartunnel.client.profile

import org.junit.Assert.*
import org.junit.Test

class ProfileDeletionTransactionTest {
    private val p = VmessWsProfile(id="a",name="A",server="example.com",uuid="00000000-0000-4000-8000-000000000000",wsHost="example.com")
    @Test fun everyWriteFailureKeepsNodeAndRestoresSelectionAndHealth() {
        for (failure in listOf("health", "selection", "profiles")) {
            var nodes = listOf(p, p.copy(id="b"))
            var selected: String? = "a"
            var health: String? = "available"
            var failOnce = true
            fun write(stage: String) { if (failOnce && stage == failure) { failOnce = false; error("disk full") } }
            val transaction = ProfileDeletionTransaction({ nodes }, { write("profiles"); nodes = it },
                { selected }, { write("selection"); selected = it }, { health },
                { write("health"); health = null }, { _, value -> health = value })
            assertTrue(runCatching { transaction.deleteAtomically("a") }.isFailure)
            assertEquals(listOf("a","b"), nodes.map { it.id })
            assertEquals("a", selected); assertEquals("available", health)
            assertEquals("b", transaction.deleteAtomically("a"))
            assertEquals(listOf("b"), nodes.map { it.id }); assertNull(health)
        }
    }
}
