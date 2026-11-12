package dev.local.sunwindowdemo

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.local.sunwindowdemo.core.ui.ComplementsTheme
import dev.local.sunwindowdemo.features.floatingwindow.ui.GestureScreen
import dev.local.sunwindowdemo.features.floatingwindow.ui.WindowScreen
import dev.local.sunwindowdemo.features.suggestions.SuggestionScreen
import dev.local.sunwindowdemo.home.HomeScreen
import dev.local.sunwindowdemo.navigation.Page
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h915dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManagementUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var captureView: View

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = File(requireNotNull(System.getProperty("nativecomplements.screenshots")))
        directory.mkdirs()
        compose.runOnIdle {
            val view = captureView.rootView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun homeHasOnlyImplementedFeatureAndNavigates() {
        var selected = false
        compose.setContent {
            captureView = LocalView.current
            ComplementsTheme(dynamic = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 32.dp)) {
                        androidx.compose.material3.Text("类原生补全", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(12.dp, 20.dp, 12.dp, 28.dp))
                        HomeScreen(onWindow = { selected = true }, onAbout = {})
                    }
                }
            }
        }
        compose.onNodeWithText("游戏工具").assertDoesNotExist()
        compose.onNodeWithText("小窗").assertIsDisplayed()
        screenshot("home-light")
        compose.onNodeWithText("小窗").performClick()
        compose.runOnIdle { assertTrue(selected) }
    }

    @Test fun windowPageOffersSettingsWithoutDeveloperTools() {
        var page: Page? = null
        compose.setContent {
            captureView = LocalView.current
            ComplementsTheme(dynamic = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        androidx.compose.material3.Text("小窗设置", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(12.dp, 24.dp, 12.dp, 28.dp))
                        WindowScreen(WindowPreferences(), { page = it }, {})
                    }
                }
            }
        }
        screenshot("window-light")
        compose.onNodeWithText("手势与菜单").performClick()
        compose.runOnIdle { assertEquals(Page.Gestures, page) }
        compose.onNodeWithText("快捷应用").assertDoesNotExist()
        compose.onNodeWithText("诊断与恢复").assertDoesNotExist()
        compose.onNodeWithText("启用小窗").assertExists()
    }

    @Test fun suggestionPageUsesIconListAndButtonSheet() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val controller = WindowController(activity.get())
        try {
            compose.setContent {
                captureView = LocalView.current
                ComplementsTheme(dynamic = false) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                            SuggestionScreen(controller)
                        }
                    }
                }
            }
            compose.onNodeWithText("启用快捷建议").assertExists()
            compose.onNodeWithText("手电筒").assertExists()
            compose.onNodeWithText("本次常亮").assertExists()
            compose.onNodeWithText("蓝牙音频设备").assertExists()
            compose.onNodeWithText("验证码填充").assertExists()
            compose.onNodeWithText("链接与分享口令").assertExists()
            compose.onNodeWithText("地址与地点").assertExists()
            compose.onNodeWithText("以小窗打开").assertExists()
            compose.onNodeWithText("短信读取").assertDoesNotExist()
            screenshot("suggestion-page")
            compose.onNodeWithText("按钮显示").performClick()
            compose.onNodeWithText("显示时长").assertExists()
            compose.onNodeWithText("按钮大小").assertExists()
        } finally {
            controller.close(); activity.destroy()
        }
    }

    @Test fun gestureToggleWritesOriginalPreferenceAndSurvivesReload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("corner", Context.MODE_PRIVATE).edit().clear().commit()
        val store = WindowPreferenceStore(context)
        compose.setContent {
            captureView = LocalView.current
            var prefs by remember { mutableStateOf(store.read()) }
            ComplementsTheme(dark = true, dynamic = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        androidx.compose.material3.Text("手势与菜单", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(12.dp, 24.dp, 12.dp, 28.dp))
                        GestureScreen(prefs, { key, value -> store.boolean(key, value); prefs = store.read() }, { key, value -> store.number(key, value); prefs = store.read() })
                    }
                }
            }
        }
        compose.onNodeWithText("关闭顶部提示").performClick()
        compose.runOnIdle { assertTrue(Config(context).hideTopHint()); assertTrue(WindowPreferenceStore(context).read().hideTopHint) }
        screenshot("gestures-dark")
    }

    @Test fun migratedPreferencesKeepDemoDefaultsOrderAndBounds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val raw = context.getSharedPreferences("corner", Context.MODE_PRIVATE)
        raw.edit().clear().putInt("heat", 24).putString("apps", "com.test.two,com.test.one").putBoolean("hideRecents", true).commit()
        val store = WindowPreferenceStore(context)
        assertEquals(36, store.read().heat)
        assertEquals(listOf("com.test.two", "com.test.one"), store.read().apps)
        assertTrue(store.read().hideRecents)
        assertEquals(90, store.read().selectionDistance)
        assertEquals(false, store.read().doubleClose)
        store.number("heat", 100)
        assertEquals(72, Config(context).heat())
        store.apps(listOf("com.test.one", "com.test.one", "bad", "com.test.two", "com.test.three", "com.test.four", "com.test.five", "com.test.six"))
        assertEquals(listOf("com.test.one", "com.test.two", "com.test.three", "com.test.four", "com.test.five", "com.test.six"), Config(context).apps())
        store.blocked(setOf("com.test.one"))
        assertEquals(setOf("com.test.one"), Config(context).blocked())
    }
}
