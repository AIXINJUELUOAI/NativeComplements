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
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.local.sunwindowdemo.core.ui.ComplementsTheme
import dev.local.sunwindowdemo.features.floatingwindow.ui.GestureScreen
import dev.local.sunwindowdemo.features.floatingwindow.ui.WindowScreen
import dev.local.sunwindowdemo.features.suggestions.SuggestionScreen
import dev.local.sunwindowdemo.home.HomeScreen
import dev.local.sunwindowdemo.navigation.ModuleApp
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
    @get:Rule val compose = createRobolectricComposeRule()
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
        var harassmentSelected = false
        compose.setContent {
            captureView = LocalView.current
            ComplementsTheme(dynamic = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 32.dp)) {
                        androidx.compose.material3.Text("类原生补全", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(12.dp, 20.dp, 12.dp, 28.dp))
                        HomeScreen(onWindow = { selected = true }, onAbout = {}, onHarassment = { harassmentSelected = true })
                    }
                }
            }
        }
        compose.onNodeWithText("游戏工具").assertDoesNotExist()
        compose.onNodeWithText("小窗").assertIsDisplayed()
        compose.onNodeWithText("来电拦截").assertDoesNotExist()
        compose.onNodeWithText("短信拦截").assertDoesNotExist()
        compose.onNodeWithText("骚扰拦截").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(harassmentSelected) }
        screenshot("home-light")
        compose.onNodeWithText("小窗").performClick()
        compose.runOnIdle { assertTrue(selected) }
    }

    @Test fun harassmentPageNavigatesToExistingSettingsAndRecords() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val controller = WindowController(activity.get())
        var selected: Page? = null
        var wentBack = false
        try {
            compose.setContent {
                ComplementsTheme(dynamic = false) {
                    ModuleApp(controller, Page.Harassment, { selected = it }, { wentBack = true })
                }
            }
            compose.onNodeWithTag("page-title-骚扰拦截").assertIsDisplayed()
            listOf("来电拦截" to Page.Calls, "短信拦截" to Page.Sms,
                "来电记录" to Page.Records, "短信记录" to Page.SmsRecords).forEach { (label, page) ->
                compose.onNodeWithText(label).performScrollTo().performClick()
                compose.runOnIdle { assertEquals(page, selected) }
            }
            compose.onNodeWithContentDescription("返回").performClick()
            compose.runOnIdle { assertTrue(wentBack) }
        } finally {
            controller.close(); activity.destroy()
        }
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
        compose.onNodeWithText("小窗入口与菜单").performClick()
        compose.runOnIdle { assertEquals(Page.Gestures, page) }
        compose.onNodeWithText("快捷应用").assertDoesNotExist()
        compose.onNodeWithText("诊断与恢复").assertDoesNotExist()
        compose.onNodeWithText("启用小窗").assertExists()
    }

    @Test fun gesturePageOffersCornerEntryWithoutRetiredToggles() {
        compose.setContent {
            ComplementsTheme(dynamic = false) {
                Surface {
                    Column {
                        GestureScreen(WindowPreferences(), { _, _ -> }, { _, _ -> })
                    }
                }
            }
        }
        compose.onNodeWithText("角落滑动呼出").assertExists()
        compose.onNodeWithText("菜单背景模糊").assertExists()
        compose.onNodeWithText("长侧滑打开小窗").assertDoesNotExist()
        compose.onNodeWithText("关闭顶部提示").assertDoesNotExist()
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
            compose.onNodeWithText("屏幕旋转").assertExists()
            compose.onNodeWithText("以小窗打开").assertDoesNotExist()
            compose.onNodeWithText("短信读取").assertDoesNotExist()
            screenshot("suggestion-page")
            compose.onNodeWithText("按钮显示").performScrollTo().performClick()
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
                        androidx.compose.material3.Text("小窗入口与菜单", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(12.dp, 24.dp, 12.dp, 28.dp))
                        GestureScreen(prefs, { key, value -> store.boolean(key, value); prefs = store.read() }, { key, value -> store.number(key, value); prefs = store.read() })
                    }
                }
            }
        }
        compose.onNodeWithText("菜单背景模糊").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(Config(context).menuBlur()); assertTrue(WindowPreferenceStore(context).read().menuBlur) }
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
