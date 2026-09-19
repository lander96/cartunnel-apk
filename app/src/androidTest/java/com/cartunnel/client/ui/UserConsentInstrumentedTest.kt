package com.cartunnel.client.ui

import android.content.Context
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cartunnel.client.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserConsentInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun clearConsent() {
        context.getSharedPreferences("cartunnel.consent", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun restoreConsent() { UserConsent.accept(context) }

    @Test fun acceptanceUnlocksMainAndSurvivesRecreationAndRelaunch() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                assertFalse(UserConsent.isAccepted(context))
                assertNull(it.findViewById<Button>(R.id.connect))
                assertTrue(it.consentDialog!!.isShowing)
                it.consentDialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertTrue(UserConsent.isAccepted(context))
                assertNotNull(it.findViewById<Button>(R.id.connect))
            }
            scenario.recreate()
            scenario.onActivity { assertNull(it.consentDialog) }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertNull(it.consentDialog) }
        }
    }

    @Test fun recreationBeforeAcceptanceKeepsGateClosed() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.onActivity {
                assertFalse(UserConsent.isAccepted(context))
                assertNull(it.findViewById<Button>(R.id.connect))
                assertTrue(it.consentDialog!!.isShowing)
            }
        }
    }

    @Test fun rejectionExitsAndNextLaunchAsksAgain() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.consentDialog!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                assertFalse(UserConsent.isAccepted(context))
            }
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (scenario.state != androidx.lifecycle.Lifecycle.State.DESTROYED &&
                android.os.SystemClock.uptimeMillis() < deadline) {
                android.os.SystemClock.sleep(50)
            }
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertTrue(it.consentDialog!!.isShowing) }
        }
    }
}
