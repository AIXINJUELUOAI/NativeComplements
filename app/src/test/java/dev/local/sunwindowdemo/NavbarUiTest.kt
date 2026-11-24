package dev.local.sunwindowdemo

import android.content.Context
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import dev.local.sunwindowdemo.core.ui.ComplementsTheme
import dev.local.sunwindowdemo.core.ui.SettingsPage
import dev.local.sunwindowdemo.features.navbar.NavbarStore
import dev.local.sunwindowdemo.features.shade.ShadeScreen
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h915dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavbarUiTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun resetPreferences() { NavbarStore.preferences(context).edit().clear().commit() }
    @After fun clearPreferences() { NavbarStore.preferences(context).edit().clear().commit() }

    private fun showAppearance() {
        compose.setContent {
            ComplementsTheme(dynamic = false) {
                SettingsPage("外观与控制") { bottomInset -> ShadeScreen(bottomInset) }
            }
        }
        compose.onNodeWithText("小白条沉浸").performScrollTo()
    }

    @Test fun oneImmersiveSwitchDefaultsOffAndPersistsWithoutAppRules() {
        showAppearance()
        compose.onAllNodesWithText("小白条沉浸").assertCountEquals(1)
        compose.onNodeWithText("小白条沉浸").assertIsOff()
        compose.onNodeWithText("保留原生手势条，取消底部占位").assertDoesNotExist()
        compose.onNodeWithText("逐应用设置").assertDoesNotExist()
        compose.onNodeWithText("搜索应用、包名或 Activity").assertDoesNotExist()
        compose.onNodeWithText("启用导航栏适配").assertDoesNotExist()
        compose.onNodeWithText("小白条沉浸").performClick()
        compose.onNodeWithText("小白条沉浸").assertIsOn()
        compose.runOnIdle { assertTrue(NavbarStore.preferences(context).getBoolean("enabled", false)) }
        compose.onNodeWithText("小白条沉浸").performClick()
        compose.onNodeWithText("小白条沉浸").assertIsOff()
        compose.runOnIdle { assertFalse(NavbarStore.preferences(context).getBoolean("enabled", false)) }
    }

    @Test fun externalPreferenceChangesRefreshTheSwitch() {
        NavbarStore.setEnabled(context, true)
        showAppearance()
        compose.onNodeWithText("小白条沉浸").assertIsOn()
        compose.runOnIdle { NavbarStore.setEnabled(context, false) }
        compose.onNodeWithText("小白条沉浸").assertIsOff()
        compose.runOnIdle { NavbarStore.setEnabled(context, true) }
        compose.onNodeWithText("小白条沉浸").assertIsOn()
    }
}
