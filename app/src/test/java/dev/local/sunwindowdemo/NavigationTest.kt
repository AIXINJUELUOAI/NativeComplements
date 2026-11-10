package dev.local.sunwindowdemo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h915dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val directory = File(requireNotNull(System.getProperty("nativecomplements.screenshots")))
            directory.mkdirs()
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun realActivityNavigatesAndBackReturnsToOrigin() {
        screenshot("activity-home")
        compose.onNodeWithText("小窗").performClick()
        screenshot("activity-window")
        compose.onNodeWithText("手势与菜单").performScrollTo().performClick()
        compose.onNodeWithText("关闭顶部提示").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("快捷应用").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("关于").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("检查模块状态").performClick()
        compose.onNodeWithText("系统后端").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("关于").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertTrue(!compose.activity.isFinishing) }
    }
}
