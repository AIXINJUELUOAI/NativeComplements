package dev.local.sunwindowdemo

import android.content.Intent
import androidx.activity.BackEventCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.local.sunwindowdemo.features.iconlayers.IconLayerStore
import dev.local.sunwindowdemo.features.shade.ShadeStore
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
class WindowProductNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val scenarios = mutableListOf<ActivityScenario<MainActivity>>()
    @After fun closeActivities() { scenarios.asReversed().forEach { it.close() } }
    private fun intent(page: Page? = null) = Intent(ApplicationProvider.getApplicationContext(),
        if (page == null) MainActivity::class.java else SettingsActivity::class.java).apply {
        if (page != null) putExtra(MainActivity.PAGE_EXTRA, page.name)
    }
    private fun launch(intent: Intent): Pair<ActivityScenario<MainActivity>, MainActivity> {
        val scenario = ActivityScenario.launch<MainActivity>(intent).also { scenarios += it }
        lateinit var activity: MainActivity
        scenario.onActivity { activity = it }
        compose.waitForIdle()
        return scenario to activity
    }
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
    @Test fun standaloneHomeHasOnlyWindowControlsAndNoDailyDiagnostics() {
        launch(intent())
        compose.onNodeWithTag("page-title-类原生小窗").assertIsDisplayed()
        compose.onNodeWithText("启用小窗").assertIsDisplayed()
        for (label in listOf("弹幕通知", "来电拦截", "快捷建议", "边缘防误触", "加速度传感器控制",
            "图标适配", "外观与控制", "小白条沉浸", "开发者工具", "诊断与恢复", "小窗诊断与恢复", "系统后端", "SystemUI 宿主")) {
            compose.onNodeWithText(label).assertDoesNotExist()
        }
        compose.onNodeWithText("关于").assertExists()
    }
    @Test fun sharedGestureAndBehaviorUseNativeActivityBack() {
        for ((page, label) in listOf(Page.Gestures to "手势与菜单", Page.Behavior to "窗口与多任务")) {
            val (rootScenario, root) = launch(intent())
            compose.onNodeWithText(label).performScrollTo().performClick()
            val next = nextPage(root, page)
            rootScenario.moveToState(Lifecycle.State.CREATED)
            val (childScenario, child) = launch(next)
            compose.onNodeWithTag("page-title-$label").assertIsDisplayed()
            compose.runOnIdle {
                assertFalse(child.onBackPressedDispatcher.hasEnabledCallbacks())
                child.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, BackEventCompat.EDGE_LEFT))
                child.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 450f, .6f, BackEventCompat.EDGE_LEFT))
                child.onBackPressedDispatcher.dispatchOnBackCancelled()
                assertFalse(child.isFinishing)
                child.onBackPressedDispatcher.onBackPressed()
                assertTrue(child.isFinishing)
                assertFalse(root.isFinishing)
            }
            childScenario.close()
            rootScenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithTag("page-title-类原生小窗").assertIsDisplayed()
            rootScenario.close()
        }
    }
    @Test fun blockedPickerKeepsPreventionParentAndRecreationKeepsTitle() {
        val (rootScenario, root) = launch(intent())
        compose.onNodeWithText("小窗触发限制").performScrollTo().performClick()
        val prevention = nextPage(root, Page.Prevention)
        rootScenario.moveToState(Lifecycle.State.CREATED)
        val (preventionScenario, parent) = launch(prevention)
        compose.onNodeWithTag("page-title-小窗触发限制").assertIsDisplayed()
        compose.onNodeWithText("指定应用内禁用").performClick()
        val blocked = nextPage(parent, Page.Blocked)
        preventionScenario.moveToState(Lifecycle.State.CREATED)
        val (blockedScenario, _) = launch(blocked)
        compose.onNodeWithTag("page-title-禁用应用").assertIsDisplayed()
        blockedScenario.recreate()
        compose.onNodeWithTag("page-title-禁用应用").assertIsDisplayed()
        lateinit var recreated: MainActivity
        blockedScenario.onActivity { recreated = it }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle { assertTrue(recreated.isFinishing) }
        blockedScenario.close()
        preventionScenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("page-title-小窗触发限制").assertIsDisplayed()
        compose.runOnIdle { assertFalse(parent.isFinishing); assertFalse(root.isFinishing) }
    }
    @Test fun monetRequiresNativeThemeAndPersistsIndependentWindowChoices() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        IconLayerStore.prefs(context).edit().clear().commit()
        ShadeStore.setCoverMonet(context, false); ShadeStore.setWindowMonet(context, false)
        launch(intent())
        compose.onNodeWithText("需先启用系统主题图标").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("遮罩莫奈图标").assertIsNotEnabled()
        compose.onNodeWithText("小窗莫奈图标").assertIsNotEnabled()
        compose.runOnIdle { IconLayerStore.prefs(context).edit().putBoolean("system_themed", true).commit() }
        compose.onNodeWithText("遮罩莫奈图标").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(ShadeStore.coverMonet(context)); assertFalse(ShadeStore.windowMonet(context)) }
        compose.onNodeWithText("小窗莫奈图标").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(ShadeStore.coverMonet(context)); assertTrue(ShadeStore.windowMonet(context)) }
        compose.onNodeWithText("遮罩莫奈图标").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(ShadeStore.coverMonet(context)); assertTrue(ShadeStore.windowMonet(context)) }
    }
    @Test fun diagnosticsRequireSevenAboutVersionTapsAndExplainCoexistence() {
        val (aboutScenario, about) = launch(intent(Page.About))
        compose.onNodeWithTag("page-title-关于").assertIsDisplayed()
        compose.onNodeWithText("系统作用域").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("与主模块共存").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("开发者工具").assertDoesNotExist()
        compose.onNodeWithText("小窗诊断与恢复").assertDoesNotExist()
        repeat(6) { compose.onNodeWithText("版本 ${BuildConfig.VERSION_NAME}").performScrollTo().performClick() }
        compose.onNodeWithText("开发者工具").assertDoesNotExist()
        compose.onNodeWithText("版本 ${BuildConfig.VERSION_NAME}").performScrollTo().performClick()
        compose.onNodeWithText("开发者工具").performScrollTo().performClick()
        val toolsIntent = nextPage(about, Page.Tools)
        aboutScenario.moveToState(Lifecycle.State.CREATED)
        val (toolsScenario, tools) = launch(toolsIntent)
        compose.onNodeWithTag("page-title-开发者工具").assertIsDisplayed()
        compose.onNodeWithText("小窗诊断与恢复").performClick()
        val diagnosticIntent = nextPage(tools, Page.Diagnostics)
        toolsScenario.moveToState(Lifecycle.State.CREATED)
        launch(diagnosticIntent)
        compose.onNodeWithTag("page-title-小窗诊断与恢复").assertIsDisplayed()
        compose.onNodeWithText("系统后端").assertIsDisplayed()
    }
}
