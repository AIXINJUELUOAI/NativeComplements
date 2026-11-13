package dev.local.sunwindowdemo

import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.drawable.ColorDrawable
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.local.sunwindowdemo.core.ui.ComplementsTheme
import dev.local.sunwindowdemo.core.ui.SettingsPage
import dev.local.sunwindowdemo.features.navbar.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h915dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavbarUiTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private lateinit var captureView: View

    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = captureView.rootView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File(requireNotNull(System.getProperty("nativecomplements.screenshots")), "$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun appAndActivityChoicesPersistAndReturningShowsUpdatedSummary() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pkg = "test.navbar.app"
        val detailActivity = "$pkg.DetailActivity"
        val alias = "$pkg.LauncherAlias"
        val preferences = NavbarStore.preferences(context)
        preferences.edit().clear().putBoolean("enabled", true).commit()
        val info = PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg; uid = 12345 }
            activities = arrayOf(
                ActivityInfo().apply { name = detailActivity; packageName = pkg },
                ActivityInfo().apply { name = alias; packageName = pkg; targetActivity = detailActivity },
            )
        }
        shadowOf(context.packageManager).installPackage(info)
        val app = LaunchableApp(pkg, "示例应用", ColorDrawable(0xff227799.toInt()))
        val detail = mutableStateOf(false)
        compose.setContent {
            captureView = LocalView.current
            ComplementsTheme(dynamic = false) {
                SettingsPage(if (detail.value) app.label else "导航栏适配") {
                    if (detail.value) NavbarAppScreen(app, true, 0.dp)
                    else NavbarScreen(listOf(app), true, 0.dp) { detail.value = true }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("示例应用").fetchSemanticsNodes().isNotEmpty() }
        screenshot("navbar-settings-main")
        compose.onNodeWithText("示例应用").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("应用默认模式").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("取色").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(NavbarStore.COLOR, preferences.getInt("$pkg/*", -1)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("DetailActivity").fetchSemanticsNodes().isNotEmpty() }
        screenshot("navbar-settings-app")
        compose.onNodeWithText("DetailActivity").performScrollTo().performClick()
        compose.onAllNodesWithText("悬浮").onLast().performClick()
        compose.runOnIdle { assertEquals(NavbarStore.FLOATING, preferences.getInt("$pkg/$detailActivity", -1)) }
        compose.onNodeWithText("LauncherAlias").performScrollTo().performClick()
        compose.onAllNodesWithText("保留系统原有").onLast().performClick()
        compose.runOnIdle { assertEquals(NavbarStore.ORIGINAL, preferences.getInt("$pkg/$alias", -1)) }
        compose.onNodeWithText("DetailActivity").performScrollTo().performClick()
        compose.onNodeWithText("跟随应用与模板").performClick()
        compose.runOnIdle {
            assertFalse(preferences.contains("$pkg/$detailActivity"))
            detail.value = false
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("应用默认 · 取色 · 1 条独立规则").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("搜索应用").assertDoesNotExist()
        compose.onNodeWithText("搜索 Activity").assertDoesNotExist()
    }
}
