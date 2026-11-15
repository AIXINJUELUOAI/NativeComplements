package dev.local.sunwindowdemo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.local.sunwindowdemo.navigation.Page
import org.junit.Before
import org.junit.After
import dev.local.sunwindowdemo.features.callblocking.CallBlockingStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h915dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CallBlockingPageTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: MainActivity
    private lateinit var scenario: ActivityScenario<MainActivity>
    @Before fun launchPage() {
        scenario = ActivityScenario.launch(Intent(ApplicationProvider.getApplicationContext(), SettingsActivity::class.java)
            .putExtra(MainActivity.PAGE_EXTRA, Page.Calls.name))
        scenario.onActivity { activity = it }
    }
    @After fun closePage() { scenario.close() }

    @Test fun phonePageOpensAndFollowKeepsSim2IndependentChoice() {
        compose.runOnIdle {
            CallBlockingStore.preferences(activity).edit().clear().commit()
            CallBlockingStore.setRejectAll(activity, 1, true)
        }
        compose.onNodeWithText("启用来电拦截").performClick()
        compose.onNodeWithText("跟随 SIM 1").performScrollTo().performClick()
        compose.runOnIdle {
            val value = CallBlockingStore.readConfig(activity)
            assertTrue(value.getBoolean("enabled"))
            assertTrue(value.getBoolean("sim2_follow_sim1"))
            assertTrue(value.getBoolean("sim2_reject_all"))
        }
        compose.onNodeWithText("跟随 SIM 1").performClick()
        compose.runOnIdle {
            val value = CallBlockingStore.readConfig(activity)
            assertFalse(value.getBoolean("sim2_follow_sim1"))
            assertTrue(value.getBoolean("sim2_reject_all"))
            assertFalse(value.getBoolean("sim1_reject_all"))
        }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle { assertTrue(activity.isFinishing) }
    }
}
