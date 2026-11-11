package dev.local.sunwindowdemo

import android.content.Context
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ApplicationProvider
import dev.local.sunwindowdemo.core.ui.ComplementsTheme
import dev.local.sunwindowdemo.core.ui.SettingsPage
import dev.local.sunwindowdemo.features.barrage.BarrageProvider
import dev.local.sunwindowdemo.features.barrage.BarrageStore
import dev.local.sunwindowdemo.features.barrage.BarrageScreen
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
import org.robolectric.shadows.ShadowBinder
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h915dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuickFunctionsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var captureView: View
    private lateinit var menu: View

    private fun show(quick: (Context) -> View) {
        compose.setContent {
            captureView = LocalView.current
            ComplementsTheme { Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Surface(Modifier.width(224.dp).height(332.dp), color = MaterialTheme.colorScheme.surface) {
                    AndroidView(factory = { c ->
                        val app = AppCatalog.App("example.alpha", "示例应用", ColorDrawable(Color.BLUE))
                        AppList.create(c, listOf(app), listOf(ShortcutTarget.app(c, app)), {}, { _, done -> done.accept(true) }, quick(c))
                            .also { menu = it; AppList.blur(it, false) }
                    }, modifier = Modifier.fillMaxSize())
                }
            } }
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
        lateinit var host: WindowQuickHost
        val audio = context.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
        show { c -> WindowQuickHost(c, Runnable {}).also { host = it }.view() }
        var appWidth = 0
        compose.runOnIdle {
            assertTrue(native("应用").isShown)
            assertTrue(native("快捷功能").isShown)
            val app = native("示例应用，点击打开")
            appWidth = app.width
            assertEquals(app.javaClass, native("Wi-Fi").javaClass)
            native("快捷功能").performClick()
        }
        compose.runOnIdle {
            assertTrue(native("Wi-Fi").isShown)
            assertEquals(appWidth, native("Wi-Fi").width)
            assertFalse(native("Wi-Fi").performLongClick())
            assertFalse(views(menu).any { it is SeekBar })
            for (removed in listOf("亮度", "媒体音量", "电量", "小窗设置", "游戏面板", "弹幕通知")) {
                assertFalse(views(menu).any { it is TextView && it.text.toString().contains(removed) })
            }
            native("静音").performClick()
            assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals("已开启", native("静音").stateDescription)
            native("静音").performClick()
            assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        }
        screenshot("quick-functions-portrait")
        compose.runOnIdle { host.close() }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w915dp-h412dp-land-night-420dpi")
    fun compactLandscapeShowsMediaAsPlainIconsAndRefreshesPlaybackState() {
        val actions = mutableListOf<String>()
        lateinit var quick: View
        val entries = listOf("dnd" to "免打扰", "wifi" to "Wi-Fi", "rotation" to "旋转锁定", "mute" to "静音",
            "screenshot" to "截屏", "record" to "录屏", "previous" to "上一首", "play" to "暂停", "next" to "下一首")
            .map { (id, label) -> AppList.QuickEntry(id, label, ColorDrawable(Color.BLUE), null) }
        show { c -> AppList.quickPage(c, entries, actions::add).also { quick = it } }
        compose.runOnIdle {
            native("快捷功能").performClick()
            (quick as ScrollView).fullScroll(View.FOCUS_DOWN)
            native("暂停").performClick(); native("上一首").performClick(); native("下一首").performClick()
            assertEquals(listOf("play", "previous", "next"), actions)
            AppList.updateQuick(quick, entries.map { if (it.id() == "play") AppList.QuickEntry("play", "播放", it.icon(), null) else it })
            assertFalse(views(quick).any { it.contentDescription == "暂停" })
            native("播放").performClick()
            assertEquals("play", actions.last())
            assertFalse(quick.isVerticalFadingEdgeEnabled)
        }
        screenshot("quick-functions-landscape-dark")
    }

    @Test @Config(qualifiers = "zh-rCN-w412dp-h915dp-night-420dpi")
    fun windowPageHasLargeIconsAndSavesThroughTheActualToolbar() {
        val labels = listOf("通讯", "音乐", "浏览器", "相册", "文件", "设置", "日历", "地图", "相机", "邮箱")
        val packages = labels.mapIndexed { index, label ->
            val pkg = "example.app$index"
            val application = ApplicationInfo().apply {
                packageName = pkg; nonLocalizedLabel = label; icon = android.R.drawable.sym_def_app_icon
            }
            val info = ActivityInfo().apply {
                packageName = pkg; name = "$pkg.Main"; applicationInfo = application; nonLocalizedLabel = label
            }
            org.robolectric.Shadows.shadowOf(context.packageManager).installPackage(PackageInfo().apply {
                packageName = pkg; applicationInfo = application; activities = arrayOf(info)
            })
            val internalClass = Class.forName("android.content.pm.LauncherActivityInfoInternal")
            val internal = internalClass.getDeclaredConstructor(ActivityInfo::class.java,
                Class.forName("android.content.pm.IncrementalStatesInfo"), android.os.UserHandle::class.java)
                .newInstance(info, null, android.os.Process.myUserHandle())
            val launcherInfo = LauncherActivityInfo::class.java.getDeclaredConstructor(Context::class.java, internalClass)
                .apply { isAccessible = true }.newInstance(context, internal)
            org.robolectric.Shadows.shadowOf(context.getSystemService(LauncherApps::class.java))
                .addActivity(android.os.Process.myUserHandle(), launcherInfo)
            pkg
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val controller = WindowController(activity.get())
        try {
            compose.waitUntil(5000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); controller.catalogLoaded }
            controller.selected(packages.take(6))
            compose.setContent {
                captureView = LocalView.current
                ComplementsTheme(dark = true) { ModuleApp(controller, Page.Window, {}, {}) }
            }
            compose.onNodeWithText("管理").assertIsDisplayed()
            compose.runOnIdle {
                menu = views(captureView.rootView).first { it.javaClass.simpleName == "Panel" }
                assertNull(menu.background)
                assertFalse(views(menu).any { it is TextView && it.text.toString() == "更多应用" })
            }
            screenshot("window-page-dark")
            compose.onNodeWithText("管理").performClick()
            compose.onNodeWithText("完成").assertIsDisplayed()
            compose.runOnIdle { native("通讯，点击移除，长按拖动调整顺序").performClick() }
            compose.onNodeWithText("完成").performClick()
            compose.waitUntil(5000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); !controller.menuSaving }
            compose.onNodeWithText("管理").assertIsDisplayed()
            compose.runOnIdle { assertEquals(packages.subList(1, 6), controller.preferences.apps) }
        } finally {
            controller.close(); activity.pause().stop().destroy()
        }
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
