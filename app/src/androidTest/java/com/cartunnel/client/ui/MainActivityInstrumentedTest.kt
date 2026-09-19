package com.cartunnel.client.ui

import android.content.Context
import android.widget.TextView
import android.widget.Button
import android.widget.LinearLayout
import com.cartunnel.client.App
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cartunnel.client.R
import com.cartunnel.client.profile.ProfileJsonCodec
import com.cartunnel.client.profile.SecureProfileStore
import com.cartunnel.client.profile.VmessWsProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun resetAppState() {
        UserConsent.accept(context)
        SecureProfileStore(context).reset()
        context.getSharedPreferences("cartunnel.state", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun jsonImportEncryptsAndPersistsProfile() {
        ActivityScenario.launch<MainActivity>(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.importText(ProfileJsonCodec.encode(listOf(profile())))
                assertEquals(
                    "已导入 1 个节点并选中当前节点",
                    activity.findViewById<TextView>(R.id.status).text.toString(),
                )
                assertFalse(activity.isFinishing)
            }
        }

        assertEquals(profile(), SecureProfileStore(context).load().single())
    }

    @Test
    fun importingOneNodeSelectsItEvenWithExistingSelection() {
        val app = context as App
        app.profiles.save(listOf(profile().copy(id = "old")))
        app.tunnelStates.updateSettings { it.copy(currentProfileId = "old", desiredRunning = false) }
        ActivityScenario.launch<MainActivity>(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.importText(ProfileJsonCodec.encode(listOf(profile()))))
                assertEquals(profile().id, app.tunnelStates.settings().currentProfileId)
            }
        }
    }

    @Test
    fun rowChildMoreDoesNotSelectItsParent() {
        val app = context as App
        app.profiles.save(listOf(profile().copy(id = "first"), profile().copy(id = "second")))
        app.tunnelStates.updateSettings { it.copy(currentProfileId = "first", desiredRunning = false) }
        ActivityScenario.launch<MainActivity>(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val second = activity.findViewById<LinearLayout>(R.id.profile_list).getChildAt(1)
                second.findViewById<Button>(R.id.profile_more).performClick()
                assertEquals("first", app.tunnelStates.settings().currentProfileId)
            }
        }
    }

    @Test
    fun unreadableExistingCiphertextDoesNotEscapeImportHandler() {
        val preferences = context.getSharedPreferences("cartunnel.private", Context.MODE_PRIVATE)
        preferences.edit()
            .putInt("profiles.schema", 1)
            .putString("profiles.iv", "AA==")
            .putString("profiles.blob", "AA==")
            .commit()

        ActivityScenario.launch<MainActivity>(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.importText(ProfileJsonCodec.encode(listOf(profile())))
                assertTrue(
                    activity.findViewById<TextView>(R.id.status).text
                        .contains("本地加密存储不可用"),
                )
                assertFalse(activity.isFinishing)
            }
        }
    }

    @Test
    fun nodeAndActionGapsAreUniformInBothOrientations() {
        val app = context as App
        app.profiles.save(listOf(profile().copy(id = "first"), profile().copy(id = "second")))
        app.tunnelStates.updateSettings { it.copy(currentProfileId = "first", desiredRunning = false) }
        for (orientation in listOf(android.content.res.Configuration.ORIENTATION_LANDSCAPE,
                android.content.res.Configuration.ORIENTATION_PORTRAIT)) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    it.requestedOrientation = if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
                val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                val deadline = android.os.SystemClock.uptimeMillis() + 5000
                var ready = false
                while (!ready && android.os.SystemClock.uptimeMillis() < deadline) {
                    instrumentation.waitForIdleSync()
                    scenario.onActivity {
                        ready = it.resources.configuration.orientation == orientation &&
                            it.findViewById<LinearLayout>(R.id.profile_list).height > 0
                    }
                    if (!ready) android.os.SystemClock.sleep(50)
                }
                assertTrue("orientation/layout did not settle", ready)
                scenario.onActivity {
                    val list = it.findViewById<LinearLayout>(R.id.profile_list)
                    val first = list.getChildAt(0)
                    val second = list.getChildAt(1)
                    val importButton = it.findViewById<Button>(R.id.import_profile)
                    val actions = it.findViewById<Button>(R.id.config_menu).parent as android.view.View
                    val gap = it.resources.getDimensionPixelSize(R.dimen.control_gap)
                    assertEquals(gap, second.top - first.bottom)
                    assertEquals(gap, importButton.top - list.bottom)
                    assertEquals(gap, actions.top - importButton.bottom)
                }
                // Wait for the system rotation animation, which outlives layout/idle.
                android.os.SystemClock.sleep(1000)
                instrumentation.waitForIdleSync()
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(context.getExternalFilesDir(null), "spacing-$orientation.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
        }
    }

    private fun profile() = VmessWsProfile(
        id = "imported-profile",
        name = "Imported VPS",
        server = "203.0.113.10",
        port = 8443,
        uuid = "00000000-0000-4000-8000-000000000000",
        wsHost = "example.com",
    )
}
