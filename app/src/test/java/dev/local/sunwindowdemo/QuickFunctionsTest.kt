package dev.local.sunwindowdemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ApplicationProvider
import dev.local.sunwindowdemo.core.ui.ComplementsTheme
import dev.local.sunwindowdemo.core.ui.SettingsPage
import dev.local.sunwindowdemo.features.barrage.BarrageProvider
import dev.local.sunwindowdemo.features.barrage.BarrageScreen
import dev.local.sunwindowdemo.features.barrage.BarrageStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBinder
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h915dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuickFunctionsTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var captureView: View
    private lateinit var menu: View
    private lateinit var quickHost: WindowQuickHost
    private val actions = mutableListOf<String>()

    private fun show() {
        actions.clear()
        compose.setContent {
            captureView = LocalView.current
            ComplementsTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Surface(Modifier.width(280.dp).height(560.dp), color = MaterialTheme.colorScheme.surface) {
                        AndroidView(factory = { c ->
                            val app = AppCatalog.App("example.alpha", "示例应用", ColorDrawable(Color.BLUE))
                            quickHost = WindowQuickHost(c, Runnable {}, Runnable {})
                            AppList.create(c, listOf(app), listOf(ShortcutTarget.app(c, app)), {},
                                { _, done -> done.accept(true) }, quickHost.entries()) { id -> actions.add(id); quickHost.action(id) }
                                .also { menu = it; AppList.blur(it, false) }
                        }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    private fun views(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) repeat(root.childCount) { yieldAll(views(root.getChildAt(it))) }
    }
    private fun native(label: String) = views(menu).first {
        it.contentDescription?.toString() == label || it is TextView && it.text.toString() == label
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val root = captureView.rootView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val file = File(requireNotNull(System.getProperty("nativecomplements.screenshots")), "$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun shortcutsUseApplicationIconsAndAreAvailableBeforeManagement() {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
        show()
        var appWidth = 0
        compose.runOnIdle {
            assertTrue(native("更多").isShown)
            assertTrue(native("应用").isShown)
            assertTrue(native("快捷功能").isShown)
            val app = native("示例应用，点击打开")
            val appLocation = IntArray(2)
            val tabLocation = IntArray(2)
            app.getLocationOnScreen(appLocation)
            native("应用").getLocationOnScreen(tabLocation)
            appWidth = app.width
            assertTrue(tabLocation[1] >= appLocation[1] + app.height)
            assertEquals(app.javaClass, native("Wi-Fi").javaClass)
            native("快捷功能").performClick()
        }
        compose.runOnIdle {
            assertTrue(native("示例应用，点击打开").isShown)
            assertTrue(native("Wi-Fi").isShown)
            assertTrue(native("手电筒").isShown)
            assertTrue(native("飞行模式").isShown)
            assertTrue(native("自动亮度").isShown)
            assertTrue(native("省电模式").isShown)
            assertTrue(native("深色模式").isShown)
            assertTrue(native("应用小窗").isShown)
            assertTrue(Math.abs(appWidth - native("Wi-Fi").width) <= 1)
            assertFalse(native("Wi-Fi").performLongClick())
            assertFalse(views(menu).any { it is TextView && it.text.toString() == "上一首" })
            native("静音").performClick()
            assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertNull(native("静音").stateDescription)
            native("静音").performClick()
            assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        }
        screenshot("quick-functions-portrait")
        compose.runOnIdle { quickHost.close() }
    }

    @Test fun managingFromQuickTabAddsAQuickFunctionInPlace() {
        show()
        compose.runOnIdle {
            native("快捷功能").performClick()
            native("管理").performClick()
        }
        compose.runOnIdle {
            assertTrue(native("完成").isShown)
            assertTrue(native("快捷功能").isSelected)
            assertTrue(native("示例应用，点击移除，长按拖动调整顺序").isShown)
            native("Wi-Fi").performClick()
        }
        compose.runOnIdle {
            assertEquals(listOf<String>(), actions)
            assertTrue(native("Wi-Fi，点击移除，长按拖动调整顺序").isShown)
            native("完成").performClick()
        }
        compose.runOnIdle { quickHost.close() }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w915dp-h412dp-land-night-420dpi")
    fun compactLandscapeShowsStaticActionsWithoutMedia() {
        show()
        compose.runOnIdle {
            native("快捷功能").performClick()
            val quick = views(menu).filterIsInstance<ScrollView>().first()
            quick.fullScroll(View.FOCUS_DOWN)
            native("静音").performClick(); native("手电筒").performClick(); native("飞行模式").performClick()
            assertEquals(listOf("mute", "flashlight", "airplane"), actions)
            for (removed in listOf("上一首", "播放", "下一首")) {
                assertFalse(views(quick).any { it is TextView && it.text.toString() == removed })
            }
            assertFalse(quick.isVerticalFadingEdgeEnabled)
        }
        screenshot("quick-functions-landscape-dark")
        compose.runOnIdle { quickHost.close() }
    }

    @Test fun barrageMigratesOnceAndStaysIndependentOfWindowEnablement() {
        BarrageStore.prefs(context).edit().clear().commit()
        val old = context.createDeviceProtectedStorageContext().getSharedPreferences("game_mode", 0)
        old.edit().clear().putBoolean("enabled", true).putBoolean("barrage", true)
            .putStringSet("games", setOf("example.alpha")).commit()
        context.getSharedPreferences("corner", 0).edit().putBoolean("enabled", false).commit()
        assertTrue(BarrageStore.read(context).getBoolean("enabled"))
        assertEquals(listOf("example.alpha"), BarrageStore.read(context).getStringArrayList("apps"))
        BarrageStore.enabled(context, false)
        BarrageStore.select(context, "example.beta", true)
        BarrageStore.select(context, "example.alpha", false)
        assertFalse(BarrageStore.read(context).getBoolean("enabled"))
        assertEquals(listOf("example.beta"), BarrageStore.read(context).getStringArrayList("apps"))
        BarrageStore.enabled(context, true)
        assertTrue(BarrageStore.read(context).getBoolean("enabled"))
        assertFalse(context.getSharedPreferences("corner", 0).getBoolean("enabled", true))
        assertThrows(IllegalArgumentException::class.java) { BarrageStore.select(context, "bad", true) }
        assertEquals(3,BarrageStore.read(context).getInt("lines"))
        listOf(3,5,0).forEach { count ->
            BarrageStore.lines(context,count)
            assertEquals(count,BarrageStore.read(context).getInt("lines"))
        }
        BarrageStore.lines(context,-2)
        assertEquals(3,BarrageStore.read(context).getInt("lines"))
    }

    @Test fun barrageHistoryIsBoundedAndNewestFirst() {
        BarrageStore.prefs(context).edit().clear().commit()
        repeat(105) { index ->
            BarrageStore.addHistory(context, Bundle().apply {
                putString("package", "example.alpha")
                putLong("time", index.toLong())
                putString("app", "示例应用")
                putString("source", "好友A")
                putString("text", "消息$index")
            })
        }
        val history = BarrageStore.readHistory(context)
        assertEquals(100, history.size)
        assertEquals("消息104", history.first().getString("text"))
        assertEquals("好友A", history.first().getString("source"))
        BarrageStore.clearHistory(context)
        assertTrue(BarrageStore.readHistory(context).isEmpty())
    }

    @Test fun independentBarragePageSavesItsSwitchAndSelectedForegroundApps() {
        BarrageStore.prefs(context).edit().clear().putBoolean("migrated", true).commit()
        compose.setContent {
            captureView = LocalView.current
            ComplementsTheme {
                SettingsPage(title = "弹幕通知") { bottom ->
                    BarrageScreen(listOf(LaunchableApp("example.alpha", "示例应用")), true, bottom)
                }
            }
        }
        compose.onNodeWithText("启用弹幕通知").performClick()
        compose.onNodeWithText("同时显示条数").performClick()
        compose.onNodeWithText("五条").performClick()
        compose.runOnIdle { assertEquals(5,BarrageStore.read(context).getInt("lines")) }
        compose.onNodeWithText("同时显示条数").performClick()
        compose.onNodeWithText("无限").performClick()
        compose.runOnIdle { assertEquals(0,BarrageStore.read(context).getInt("lines")) }
        compose.onNodeWithText("逐应用设置").assertIsDisplayed()
        compose.onNodeWithText("示例应用").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(BarrageStore.read(context).getBoolean("enabled"))
            assertEquals(listOf("example.alpha"), BarrageStore.read(context).getStringArrayList("apps"))
        }
        screenshot("barrage-settings")
    }

    @Test fun providersCheckUidAndRejectRemovedTileAndSettingsActions() {
        ShadowBinder.setCallingUid(context.applicationInfo.uid)
        try {
            val prefs = context.getSharedPreferences("corner", 0)
            prefs.edit().clear().commit()
            val provider = Robolectric.buildContentProvider(ConfigProvider::class.java).create().get()
            assertNotNull(provider.call("read", null, null))
            val change = Bundle().apply { putString("tile", "wifi"); putInt("span", 2) }
            assertThrows(IllegalArgumentException::class.java) { provider.call("quickTileSize", null, change) }
            assertThrows(IllegalArgumentException::class.java) { provider.call("windowSettings", null, null) }
            val barrage = Robolectric.buildContentProvider(BarrageProvider::class.java).create().get()
            assertNotNull(barrage.call("read", null, null))
            assertThrows(IllegalArgumentException::class.java) { barrage.call("write", null, null) }
            ShadowBinder.setCallingUid(12345)
            assertThrows(SecurityException::class.java) { provider.call("read", null, null) }
            assertThrows(SecurityException::class.java) { provider.call("windowSettings", null, null) }
            assertThrows(SecurityException::class.java) { barrage.call("read", null, null) }
        } finally {
            ShadowBinder.reset()
        }
    }
}
