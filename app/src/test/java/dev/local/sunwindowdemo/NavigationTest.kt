package dev.local.sunwindowdemo

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import androidx.activity.BackEventCompat
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.local.sunwindowdemo.navigation.Page
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h915dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val scenarios = mutableListOf<ActivityScenario<MainActivity>>()

    @After fun closeActivities() { scenarios.asReversed().forEach { it.close() } }

    private fun launch(intent: Intent): Pair<ActivityScenario<MainActivity>, MainActivity> {
        val scenario = ActivityScenario.launch<MainActivity>(intent).also { scenarios += it }
        lateinit var activity: MainActivity
        scenario.onActivity { activity = it }
        compose.waitForIdle()
        return scenario to activity
    }

    private fun intent(page: Page? = null) = Intent(
        ApplicationProvider.getApplicationContext(),
        if (page == null) MainActivity::class.java else SettingsActivity::class.java,
    ).apply { if (page != null) putExtra(MainActivity.PAGE_EXTRA, page.name) }

    private fun nextPage(activity: MainActivity, page: Page): Intent {
        lateinit var next: Intent
        compose.runOnIdle {
            next = requireNotNull(shadowOf(activity).nextStartedActivity)
            assertEquals(SettingsActivity::class.java.name, next.component?.className)
            assertEquals(page.name, next.getStringExtra(MainActivity.PAGE_EXTRA))
            assertEquals(0, next.flags and (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        return next
    }

    @Test fun nestedSettingsUseNativeActivityBackAndKeepTheirParent() {
        val (homeScenario, home) = launch(intent())
        compose.onNodeWithText("小窗").performClick()
        val windowIntent = nextPage(home, Page.Window)
        homeScenario.moveToState(Lifecycle.State.CREATED)
        val (windowScenario, window) = launch(windowIntent)
        compose.onNodeWithTag("page-title-小窗设置").assertIsDisplayed()
        compose.onNodeWithText("手势与菜单").performClick()
        val gestureIntent = nextPage(window, Page.Gestures)
        windowScenario.moveToState(Lifecycle.State.CREATED)
        val (gestureScenario, gesture) = launch(gestureIntent)
        compose.onNodeWithTag("page-title-手势与菜单").assertIsDisplayed()
        compose.runOnIdle {
            // No app callback intercepts the platform cross-activity predictive animation.
            assertFalse(gesture.onBackPressedDispatcher.hasEnabledCallbacks())
            for (edge in listOf(BackEventCompat.EDGE_LEFT, BackEventCompat.EDGE_RIGHT)) {
                gesture.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, edge))
                gesture.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 450f, .6f, edge))
                gesture.onBackPressedDispatcher.dispatchOnBackCancelled()
                assertFalse(gesture.isFinishing)
            }
            gesture.onBackPressedDispatcher.onBackPressed()
            assertTrue(gesture.isFinishing)
            assertFalse(window.isFinishing)
            assertFalse(home.isFinishing)
        }
        gestureScenario.close()
        windowScenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("page-title-小窗设置").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle { assertTrue(window.isFinishing) }
        windowScenario.close()
        homeScenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("page-title-类原生补全计划").assertIsDisplayed()
        compose.runOnIdle { assertFalse(home.isFinishing) }
    }

    @Test fun developerToolsStayHiddenUntilSevenVersionTaps() {
        val (_, about) = launch(intent(Page.About))
        compose.onNodeWithText("开发者").assertIsDisplayed()
        compose.onNodeWithText("开发者工具").assertDoesNotExist()
        repeat(7) { compose.onNodeWithText("版本 ${BuildConfig.VERSION_NAME}").performClick() }
        compose.onNodeWithText("开发者工具").performScrollTo().performClick()
        nextPage(about, Page.Tools)
    }

    @Test fun barrageEntryStartsNativeSettingsAboveHome() {
        val (_, home) = launch(intent())
        compose.onNodeWithText("游戏助手").assertDoesNotExist()
        compose.onNodeWithText("弹幕通知").performClick()
        nextPage(home, Page.Barrage)
        compose.onNodeWithTag("page-title-类原生补全计划").assertIsDisplayed()
        compose.runOnIdle { assertFalse(home.onBackPressedDispatcher.hasEnabledCallbacks()) }
    }

    @Test fun legacySettingsEntryStillOpensWindowAndRecreationKeepsDestination() {
        val (scenario, _) = launch(Intent(ApplicationProvider.getApplicationContext(), SettingsActivity::class.java))
        compose.onNodeWithTag("page-title-小窗设置").assertIsDisplayed()
        scenario.close()
        val (routed, _) = launch(intent(Page.Gestures))
        compose.onNodeWithTag("page-title-手势与菜单").assertIsDisplayed()
        routed.recreate()
        compose.onNodeWithTag("page-title-手势与菜单").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "zh-rCN-w915dp-h412dp-land-night-420dpi")
    fun landscapeDarkSettingsUseDynamicSystemBars() {
        val (_, activity) = launch(intent(Page.Motion))
        compose.onNodeWithTag("page-title-加速度传感器控制").assertIsDisplayed()
        compose.onNodeWithContentDescription("批量设置").performClick()
        compose.onNodeWithText("全部前 7 秒限制").assertIsDisplayed()
        compose.runOnIdle {
            val bars = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            assertFalse(bars.isAppearanceLightNavigationBars)
            assertFalse(activity.window.isNavigationBarContrastEnforced)
            assertEquals(dynamicDarkColorScheme(activity).surface.toArgb(),
                (activity.window.decorView.background as ColorDrawable).color)
        }
    }
}
