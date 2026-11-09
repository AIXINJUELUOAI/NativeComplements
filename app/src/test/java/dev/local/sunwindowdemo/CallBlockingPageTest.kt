package dev.local.sunwindowdemo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun phonePageOpensAndFollowKeepsSim2IndependentChoice() {
        compose.runOnIdle {
            CallBlockingStore.preferences(compose.activity).edit().clear().commit()
            CallBlockingStore.setRejectAll(compose.activity, 1, true)
        }
        compose.onNodeWithText("电话拦截").performScrollTo().performClick()
        compose.onNodeWithText("开启电话拦截").performClick()
        compose.onNodeWithText("跟随 SIM 1").performScrollTo().performClick()
        compose.runOnIdle {
            val value = CallBlockingStore.readConfig(compose.activity)
            assertTrue(value.getBoolean("enabled"))
            assertTrue(value.getBoolean("sim2_follow_sim1"))
            assertTrue(value.getBoolean("sim2_reject_all"))
        }
        compose.onNodeWithText("跟随 SIM 1").performClick()
        compose.runOnIdle {
            val value = CallBlockingStore.readConfig(compose.activity)
            assertFalse(value.getBoolean("sim2_follow_sim1"))
            assertTrue(value.getBoolean("sim2_reject_all"))
            assertFalse(value.getBoolean("sim1_reject_all"))
        }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("关于").assertIsDisplayed()
    }
}
