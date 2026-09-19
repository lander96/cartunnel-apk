package com.cartunnel.client.profile

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Keystore-backed persistence must be run on an Android device. */
@RunWith(AndroidJUnit4::class)
class SecureProfileStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun profile() = VmessWsProfile(id="instrumented", name="node", server="example.com", uuid="00000000-0000-4000-8000-000000000000", wsHost="example.com")

    @Before
    fun resetStore() {
        SecureProfileStore(context).reset()
    }

    @Test fun keystoreRoundTrip() { val store = SecureProfileStore(context); store.save(listOf(profile())); assertEquals(listOf(profile()), store.load()) }

    @Test
    fun eachSaveUsesANewProviderGeneratedIv() {
        val store = SecureProfileStore(context)
        val preferences = context.getSharedPreferences("cartunnel.private", Context.MODE_PRIVATE)
        store.save(listOf(profile()))
        val firstIv = preferences.getString("profiles.iv", null)
        store.save(listOf(profile().copy(name = "updated")))
        val secondIv = preferences.getString("profiles.iv", null)

        assertNotNull(firstIv)
        assertNotNull(secondIv)
        assertNotEquals(firstIv, secondIv)
        assertEquals("updated", store.load().single().name)
    }

    @Test fun tamperedCiphertextIsUnreadable() { val store = SecureProfileStore(context); store.save(listOf(profile())); context.getSharedPreferences("cartunnel.private", Context.MODE_PRIVATE).edit().putString("profiles.blob", "AA==").commit(); assertThrows(ConfigUnreadableException::class.java) { store.load() } }
    @Test fun unsupportedSchemaIsUnreadable() { val store = SecureProfileStore(context); store.save(listOf(profile())); context.getSharedPreferences("cartunnel.private", Context.MODE_PRIVATE).edit().putInt("profiles.schema", 99).commit(); assertThrows(ConfigUnreadableException::class.java) { store.load() } }

    @Test
    fun resetRecoversFromUnreadableCiphertext() {
        val store = SecureProfileStore(context)
        store.save(listOf(profile()))
        context.getSharedPreferences("cartunnel.private", Context.MODE_PRIVATE)
            .edit()
            .putString("profiles.blob", "AA==")
            .commit()

        assertThrows(ConfigUnreadableException::class.java) { store.load() }
        store.reset()
        store.save(listOf(profile().copy(name = "recovered")))
        assertEquals("recovered", store.load().single().name)
    }
}
