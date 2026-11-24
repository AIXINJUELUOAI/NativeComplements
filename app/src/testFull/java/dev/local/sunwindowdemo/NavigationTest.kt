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
import dev.local.sunwindowdemo.features.gamemode.GameModeStore
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

    @Test fun removedWallpaperPageIsNotExposedOnHome() {
        launch(intent())
        compose.onNodeWithText("Alive 壁纸").assertDoesNotExist()
        assertFalse(Page.entries.any { it.name == "Alive" })
    }

    @Test fun targetExclusionsOpenInSharedPickerAndReturnToPrevention() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dev.local.sunwindowdemo.Config(context).prefs.edit().remove("windowExcludedPackages").commit()
        val (_, activity) = launch(intent(Page.Prevention))
        compose.onNodeWithText("不以小窗打开的应用").assertIsDisplayed().performClick()
        compose.onNodeWithTag("page-title-不以小窗打开的应用").assertIsDisplayed()
        compose.onNodeWithText("已选择 0 个应用").assertExists()
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("指定应用内禁用").assertIsDisplayed()
        assertTrue(dev.local.sunwindowdemo.Config(context).windowExcludedPackages().isEmpty())
    }

    @Test fun restoredWindowLaunchSettingsKeepExistingPreferenceKeys() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = dev.local.sunwindowdemo.Config(context).prefs
        prefs.edit().putBoolean("landscapeShareWindow", false).putBoolean("notificationWindow", false).commit()
        launch(intent(Page.Behavior))
        compose.onNodeWithText("应用间跳转小窗打开").assertIsDisplayed().performClick()
        compose.onNodeWithText("通知小窗打开").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(prefs.getBoolean("landscapeShareWindow", false))
            assertTrue(prefs.getBoolean("notificationWindow", false))
        }
    }

    @Test fun nestedSettingsUseNativeActivityBackAndKeepTheirParent() {
        val (homeScenario, home) = launch(intent())
        compose.onNodeWithText("小窗").performClick()
        val windowIntent = nextPage(home, Page.Window)
        homeScenario.moveToState(Lifecycle.State.CREATED)
        val (windowScenario, window) = launch(windowIntent)
        compose.onNodeWithTag("page-title-小窗设置").assertIsDisplayed()
        compose.onNodeWithText("小窗入口与菜单").performClick()
        val gestureIntent = nextPage(window, Page.Gestures)
        windowScenario.moveToState(Lifecycle.State.CREATED)
        val (gestureScenario, gesture) = launch(gestureIntent)
        compose.onNodeWithTag("page-title-小窗入口与菜单").assertIsDisplayed()
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

    @Test fun landscapeShadeHasIndependentSettingsAndNativeNavigation() {
        val (homeScenario, home) = launch(intent())
        compose.onNodeWithText("外观与控制").performScrollTo().performClick()
        val shadeIntent = nextPage(home, Page.Shade)
        homeScenario.moveToState(Lifecycle.State.CREATED)
        val (_, shade) = launch(shadeIntent)
        compose.onNodeWithTag("page-title-外观与控制").assertIsDisplayed()
        compose.onNodeWithText("桌面").assertIsDisplayed()
        compose.onNodeWithText("双击桌面锁屏").assertExists()
        compose.onNodeWithText("抽屉莫奈图标").assertExists()
        compose.onNodeWithText("遮罩莫奈图标").assertExists()
        compose.onNodeWithText("小窗莫奈图标").assertDoesNotExist()
        compose.onNodeWithText("保留原生手势条，取消底部占位").assertDoesNotExist()
        compose.onNodeWithText("双击桌面空白处息屏，效果等同按下电源键").assertDoesNotExist()
        compose.onNodeWithText("小白条沉浸").assertExists()
        compose.onNodeWithText("莫奈图标").assertDoesNotExist()
        compose.onNodeWithText("导航栏").assertDoesNotExist()
        compose.onNodeWithText("通知与控制中心").assertDoesNotExist()
        compose.onNodeWithText("状态栏").assertDoesNotExist()
        compose.onNodeWithText("状态栏与控制中心").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("横屏双栏").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("图标垂直位置").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle { assertTrue(shade.isFinishing) }
    }

    @Test fun developerToolsStayHiddenUntilSevenVersionTaps() {
        val (_, about) = launch(intent(Page.About))
        compose.onNodeWithText("开发者").assertIsDisplayed()
        compose.onNodeWithText("开发者工具").assertDoesNotExist()
        repeat(7) { compose.onNodeWithText("版本 ${BuildConfig.VERSION_NAME}").performClick() }
        compose.onNodeWithText("开发者工具").performScrollTo().performClick()
        val tools = nextPage(about, Page.Tools)
        launch(tools)
        compose.onNodeWithText("息屏显示壁纸（全景 AOD）").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("授权 root 并读取状态").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("状态未知", substring = true).assertExists()
    }

    @Test fun diagnosticsEntryOpensScrollableContentWithoutNestedInfiniteHeight() {
        val (aboutScenario, about) = launch(intent(Page.About))
        compose.onNodeWithText("诊断日志").performScrollTo().performClick()
        val logsIntent = nextPage(about, Page.Logs)
        aboutScenario.moveToState(Lifecycle.State.CREATED)
        launch(logsIntent)
        compose.onNodeWithTag("page-title-诊断日志").assertIsDisplayed()
        compose.onNodeWithText("开始记录 → 复现问题 → 停止 → 导出 ZIP").assertIsDisplayed()
        // Render through ModuleApp's scrolling SettingsPage: a nested verticalScroll throws here.
        compose.onNodeWithText("删除本地记录").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("导出 ZIP").assertExists()
    }

    @Test fun desktopDoubleTapSleepIsOptInAndPersistsIndependently() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dev.local.sunwindowdemo.features.shade.ShadeStore.preferences(context).edit().clear().commit()
        launch(intent(Page.Shade))
        compose.runOnIdle { org.junit.Assert.assertFalse(dev.local.sunwindowdemo.features.shade.ShadeStore.desktopDoubleTapSleep(context)) }
        compose.onNodeWithText("双击桌面锁屏").performScrollTo().performClick()
        compose.runOnIdle {
            org.junit.Assert.assertTrue(dev.local.sunwindowdemo.features.shade.ShadeStore.read(context).getBoolean("desktopDoubleTapSleep"))
            org.junit.Assert.assertFalse(dev.local.sunwindowdemo.features.shade.ShadeStore.read(context).getBoolean("enabled"))
        }
        compose.onNodeWithText("双击桌面锁屏").performClick()
        compose.runOnIdle { org.junit.Assert.assertFalse(dev.local.sunwindowdemo.features.shade.ShadeStore.desktopDoubleTapSleep(context)) }
    }

    @Test fun shadeHasOneSignalDisplaySwitchAndPersistsIt() {
        launch(intent(Page.Shade))
        compose.onNodeWithText("信号显示调整").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            org.junit.Assert.assertTrue(dev.local.sunwindowdemo.features.shade.ShadeStore.read(
                ApplicationProvider.getApplicationContext()).getBoolean("signalDisplay"))
        }
        compose.onNodeWithText("信号显示调整").performClick()
        compose.runOnIdle {
            org.junit.Assert.assertFalse(dev.local.sunwindowdemo.features.shade.ShadeStore.read(
                ApplicationProvider.getApplicationContext()).getBoolean("signalDisplay"))
        }
    }

    @Test fun gameModeOpensIndependentBarrageAndQuickManagement() {
        val (homeScenario, home) = launch(intent())
        compose.onNodeWithText("游戏助手").assertDoesNotExist()
        compose.onNodeWithText("游戏模式").performClick()
        val gameIntent = nextPage(home, Page.GameMode)
        homeScenario.moveToState(Lifecycle.State.CREATED)
        val (gameScenario, gameMode) = launch(gameIntent)
        compose.onNodeWithText("启用弹幕通知").assertDoesNotExist()
        compose.onNodeWithText("同时显示条数").assertDoesNotExist()
        compose.onNodeWithContentDescription("搜索应用").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("example.game")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("page-title-游戏模式").assertIsDisplayed()
        compose.onNodeWithText("侧边小白条").assertDoesNotExist()
        compose.onNodeWithText("固定屏幕亮度").assertDoesNotExist()
        compose.onAllNodes(hasScrollToNodeAction()).assertCountEquals(1)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("弹幕通知"))
        compose.onNodeWithText("弹幕通知").performClick()
        val barrageIntent = nextPage(gameMode, Page.Barrage)
        gameScenario.moveToState(Lifecycle.State.CREATED)
        val (barrageScenario, _) = launch(barrageIntent)
        compose.onNodeWithTag("page-title-弹幕通知").assertIsDisplayed()
        compose.onNodeWithText("启用弹幕通知").assertIsDisplayed()
        barrageScenario.close()
        gameScenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("快捷应用与功能"))
        compose.onNodeWithText("快捷应用与功能").performClick()
        val quickIntent = nextPage(gameMode, Page.GameQuick)
        gameScenario.moveToState(Lifecycle.State.CREATED)
        launch(quickIntent)
        compose.onNodeWithTag("page-title-快捷应用与功能").assertIsDisplayed()
        compose.onNodeWithText("应用", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("功能", useUnmergedTree = true).performClick()
        compose.onNodeWithText("已选功能", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertFalse(home.onBackPressedDispatcher.hasEnabledCallbacks()) }
    }

    @Test fun gameHandlePositionOpensIndependentDetailsAndNativeBackKeepsGameParent() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        GameModeStore.prefs(context).edit().putString("side", "left")
            .remove(GameModeStore.handleSideKey(false)).remove(GameModeStore.handleSideKey(true)).commit()
        val (gameScenario, game) = launch(intent(Page.GameMode))
        compose.onNodeWithText("竖屏 · 左侧 / 横屏 · 左侧").assertIsDisplayed()
        compose.onNodeWithTag("game-handle-position-preview").assertDoesNotExist()
        compose.onNodeWithContentDescription("游戏条上下位置").assertDoesNotExist()
        compose.onNodeWithText("小白条位置").performClick()
        val details = nextPage(game, Page.GameHandle)
        gameScenario.moveToState(Lifecycle.State.CREATED)
        val (detailScenario, detail) = launch(details)
        compose.onNodeWithTag("page-title-小白条位置").assertIsDisplayed()
        compose.onNodeWithTag("game-handle-position-preview").assertExists()
        compose.onNodeWithContentDescription("游戏条上下位置").assertExists()
        compose.runOnIdle {
            assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, detail.requestedOrientation)
            assertFalse(detail.onBackPressedDispatcher.hasEnabledCallbacks())
            detail.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, BackEventCompat.EDGE_LEFT))
            detail.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 450f, .6f, BackEventCompat.EDGE_LEFT))
            detail.onBackPressedDispatcher.dispatchOnBackCancelled()
            assertFalse(detail.isFinishing)
            detail.onBackPressedDispatcher.onBackPressed()
            assertTrue(detail.isFinishing)
            assertFalse(game.isFinishing)
        }
        detailScenario.close()
        gameScenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("page-title-游戏模式").assertIsDisplayed()
        compose.onNodeWithText("小白条位置").assertIsDisplayed()
        compose.onNodeWithTag("game-handle-position-preview").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "zh-rCN-w412dp-h915dp-port-420dpi")
    fun gameCornerShowsLandscapePreviewWithoutRotatingPageAndPersistsContinuousSize() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dev.local.sunwindowdemo.features.gamemode.GameModeStore.prefs(context).edit()
            .remove("corner_rejection").remove("corner_radius_percent").commit()
        val (gameScenario, game) = launch(intent(Page.GameMode))
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("角落防误触"))
        compose.onNodeWithContentDescription("角落防误触").assertIsOff().performClick()
        compose.runOnIdle { assertTrue(dev.local.sunwindowdemo.features.gamemode.GameModeStore.read(context).getBoolean("corner_rejection")) }
        compose.onNodeWithTag("game-corner-preview").assertDoesNotExist()
        compose.onNodeWithContentDescription("大小").assertDoesNotExist()
        compose.onNodeWithText("角落防误触").performClick()
        val details = nextPage(game, Page.GameCorner)
        gameScenario.moveToState(Lifecycle.State.CREATED)
        val (detailScenario, detail) = launch(details)
        val original = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        compose.onNodeWithTag("page-title-角落防误触").assertIsDisplayed()
        compose.onNodeWithTag("game-corner-preview").assertIsDisplayed()
        compose.runOnIdle { assertEquals(original, detail.requestedOrientation) }
        val preview = compose.onNodeWithTag("game-corner-preview").fetchSemanticsNode().boundsInRoot
        assertTrue(preview.width > preview.height)
        compose.onNodeWithText("范围预览").assertIsDisplayed()
        compose.onNodeWithText("大小").assertIsDisplayed()
        compose.onNodeWithText("短边的", substring = true).assertDoesNotExist()
        compose.onNodeWithText("红色：", substring = true).assertDoesNotExist()
        compose.onNodeWithText("在预览中", substring = true).assertDoesNotExist()
        val slider = compose.onNodeWithContentDescription("大小").fetchSemanticsNode()
        val range = slider.config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0, range.steps)
        assertEquals(50f..200f, range.range)
        compose.onNodeWithContentDescription("大小").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(50f) }
        compose.onNodeWithText("50 dp").assertIsDisplayed()
        compose.onNodeWithContentDescription("大小").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(200f) }
        compose.onNodeWithText("200 dp").assertIsDisplayed()
        compose.onNodeWithContentDescription("大小").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(164.375f) }
        val bounds = context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        val shortSideDp = minOf(bounds.width(), bounds.height()) / context.resources.displayMetrics.density
        compose.runOnIdle { assertEquals(164.375f / shortSideDp * 100f,
            dev.local.sunwindowdemo.features.gamemode.GameModeStore.read(context).getFloat("corner_radius_percent"), .0001f) }
        detailScenario.recreate()
        lateinit var recreated: MainActivity
        detailScenario.onActivity { recreated = it }
        compose.onNodeWithText("164 dp").assertIsDisplayed()
        compose.onNodeWithText("164.4 dp").assertDoesNotExist()
        compose.runOnIdle {
            val view = recreated.window.decorView
            val screenshot = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(screenshot))
            val screenshotDir = java.io.File(requireNotNull(System.getProperty("nativecomplements.screenshots"))).apply { mkdirs() }
            java.io.File(screenshotDir, "game-corner.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.runOnIdle { assertEquals(original, recreated.requestedOrientation) }
        detailScenario.close()
        assertEquals(original, recreated.requestedOrientation)
    }

    @Test fun navbarImmersiveIsOnlyUnderAppearanceAndControl() {
        val (homeScenario, home) = launch(intent())
        compose.onNodeWithText("导航栏适配").assertDoesNotExist()
        compose.onNodeWithText("小白条沉浸").assertDoesNotExist()
        compose.onNodeWithText("外观与控制").performScrollTo().performClick()
        val shadeIntent = nextPage(home, Page.Shade)
        homeScenario.moveToState(Lifecycle.State.CREATED)
        val (_, shade) = launch(shadeIntent)
        compose.onNodeWithTag("page-title-外观与控制").assertIsDisplayed()
        compose.onNodeWithText("小白条沉浸").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("逐应用设置").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle { assertTrue(shade.isFinishing) }
    }

    @Test fun developerToolsCanShowGameCornerRangeWithoutEnablingRejection() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = dev.local.sunwindowdemo.features.gamemode.GameModeStore.prefs(context)
        store.edit().remove("corner_show_regions").putBoolean("corner_rejection", false).commit()
        val (scenario, _) = launch(intent(Page.Tools))
        compose.onNodeWithText("显示游戏防误触范围").assertIsOff().performClick()
        compose.runOnIdle {
            assertTrue(dev.local.sunwindowdemo.features.gamemode.GameModeStore.read(context).getBoolean("corner_show_regions"))
            assertFalse(dev.local.sunwindowdemo.features.gamemode.GameModeStore.read(context).getBoolean("corner_rejection"))
        }
        scenario.recreate()
        compose.onNodeWithText("显示游戏防误触范围").assertIsOn().performClick()
        compose.runOnIdle { assertFalse(dev.local.sunwindowdemo.features.gamemode.GameModeStore.read(context).getBoolean("corner_show_regions")) }
    }

    @Test fun developerToolsNoLongerExposeNavbarDiagnostics() {
        launch(intent(Page.Tools))
        compose.onNodeWithText("小窗诊断与恢复").assertIsDisplayed()
        compose.onNodeWithText("图标实验与诊断").assertIsDisplayed()
        compose.onNodeWithText("导航栏诊断与标注").assertDoesNotExist()
        compose.onNodeWithText("标注模式").assertDoesNotExist()
    }

    @Test fun removedNavbarDestinationsFallBackToHome() {
        for (destination in listOf("Navbar", "NavbarApp", "NavbarDiagnostics")) {
            val (scenario, _) = launch(Intent(ApplicationProvider.getApplicationContext(), SettingsActivity::class.java)
                .putExtra(MainActivity.PAGE_EXTRA, destination))
            compose.onNodeWithTag("page-title-类原生补全计划").assertIsDisplayed()
            compose.onNodeWithText("导航栏适配").assertDoesNotExist()
            scenario.close()
        }
    }

    @Test fun legacySettingsEntryStillOpensWindowAndRecreationKeepsDestination() {
        val (scenario, _) = launch(Intent(ApplicationProvider.getApplicationContext(), SettingsActivity::class.java))
        compose.onNodeWithTag("page-title-小窗设置").assertIsDisplayed()
        scenario.close()
        val (routed, _) = launch(intent(Page.Gestures))
        compose.onNodeWithTag("page-title-小窗入口与菜单").assertIsDisplayed()
        routed.recreate()
        compose.onNodeWithTag("page-title-小窗入口与菜单").assertIsDisplayed()
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
