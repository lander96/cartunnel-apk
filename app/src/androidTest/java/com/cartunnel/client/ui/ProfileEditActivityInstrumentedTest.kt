package com.cartunnel.client.ui

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cartunnel.client.R
import com.cartunnel.client.profile.SecureProfileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileEditActivityInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearStoredProfiles() {
        context.getSharedPreferences("cartunnel.private", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("cartunnel.state", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun editorOpensRepeatedlyAndExplainsServerFields() {
        repeat(20) {
            ActivityScenario.launch<ProfileEditActivity>(
                Intent(context, ProfileEditActivity::class.java),
            ).use { scenario ->
                scenario.onActivity { activity ->
                    assertFalse(activity.isFinishing)
                    assertTrue(activity.findViewById<EditText>(R.id.profile_server).isShown)
                    assertTrue(
                        activity.findViewById<TextView>(R.id.profile_title).text
                            .contains("新增节点"),
                    )
                }
            }
        }
    }

    @Test
    fun invalidFieldsKeepEditorOpenAndShowErrors() {
        ActivityScenario.launch<ProfileEditActivity>(
            Intent(context, ProfileEditActivity::class.java),
        ).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.profile_save).performClick()
                assertFalse(activity.isFinishing)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.profile_error_summary).visibility)
                assertTrue(activity.findViewById<EditText>(R.id.profile_server).error.isNotBlank())
                assertTrue(activity.findViewById<EditText>(R.id.profile_uuid).error.isNotBlank())
            }
        }
    }

    @Test
    fun validServerFieldsAreSaved() {
        ActivityScenario.launch<ProfileEditActivity>(
            Intent(context, ProfileEditActivity::class.java),
        ).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.profile_name).setText("Test VPS")
                activity.findViewById<EditText>(R.id.profile_server).setText("203.0.113.10")
                activity.findViewById<EditText>(R.id.profile_port).setText("8443")
                activity.findViewById<EditText>(R.id.profile_uuid)
                    .setText("00000000-0000-4000-8000-000000000000")
                activity.findViewById<EditText>(R.id.profile_ws_host).setText("example.com")
                activity.findViewById<EditText>(R.id.profile_ws_path).setText("/car")
                activity.findViewById<Button>(R.id.profile_save).performClick()
            }
        }

        val saved = SecureProfileStore(context).load().single()
        assertEquals("203.0.113.10", saved.server)
        assertEquals(8443, saved.port)
        assertEquals("example.com", saved.wsHost)
        assertEquals("/car", saved.wsPath)
    }
}
