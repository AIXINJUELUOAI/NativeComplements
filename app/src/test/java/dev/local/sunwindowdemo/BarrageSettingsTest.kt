package dev.local.sunwindowdemo

import android.content.Context
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import dev.local.sunwindowdemo.core.ui.ComplementsTheme
import dev.local.sunwindowdemo.core.ui.Glyph
import dev.local.sunwindowdemo.core.ui.SettingsPage
import dev.local.sunwindowdemo.features.barrage.BarrageScreen
import dev.local.sunwindowdemo.features.barrage.BarrageStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h915dp-420dpi")
class BarrageSettingsTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var capture: View

    @Before fun resetPreferences() { BarrageStore.prefs(context).edit().clear().putBoolean("migrated", true).commit() }
    @After fun clearPreferences() { BarrageStore.prefs(context).edit().clear().commit() }

    private fun show() {
        compose.setContent {
            capture = LocalView.current
            ComplementsTheme(dynamic = false) {
                SettingsPage("弹幕通知") { bottomInset -> BarrageScreen(bottomInset) }
            }
        }
    }

    @Test fun gameGlyphLoadsTheProvidedGamepadVector() {
        val icon = context.getDrawable(R.drawable.gm_gamepad)
        assertTrue(icon is android.graphics.drawable.VectorDrawable)
        assertEquals((24 * context.resources.displayMetrics.density).toInt(), icon!!.intrinsicWidth)
        compose.setContent { ComplementsTheme(dynamic = false) { Glyph("game") } }
        compose.onRoot().assertExists()
    }

    @Test fun speedShowsExactMultiplierWithoutChangingStoredUnitsAndHistoryKeepsActualCount() {
        BarrageStore.addHistory(context, Bundle().apply {
            putString("package", "example.chat"); putString("text", "消息")
        })
        show()
        compose.onNodeWithText("1.0x").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(100, BarrageStore.read(context).getInt("speed")) }
        compose.runOnIdle { BarrageStore.speed(context, 45) }
        compose.onNodeWithText("0.45x").assertIsDisplayed()
        compose.runOnIdle { BarrageStore.speed(context, 240) }
        compose.onNodeWithText("2.4x").assertIsDisplayed()
        compose.onNodeWithText("弹幕记录").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1 条").assertIsDisplayed()
        compose.onNodeWithText("跟随游戏模式", substring = true).assertDoesNotExist()
        compose.onNodeWithText("还没有选择游戏", substring = true).assertDoesNotExist()
        compose.onNodeWithText("仅在已选游戏处于全屏前台时显示").assertDoesNotExist()
        compose.onNodeWithText("无限档在屏幕轨道用满后复用轨道", substring = true).assertDoesNotExist()
    }

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun eightCircularColorsUseFourColumnsAndPersistSelection() {
        show()
        val names = listOf("白色", "黄色", "橙色", "紫色", "青色", "绿色", "蓝色", "粉色")
        compose.onNodeWithContentDescription("粉色").performScrollTo()
        val bounds = names.map { name ->
            compose.onNodeWithContentDescription(name).fetchSemanticsNode().boundsInRoot
        }
        bounds.forEach {
            assertEquals(48 * compose.density.density, it.width, 1f)
            assertEquals(48 * compose.density.density, it.height, 1f)
        }
        bounds.take(4).forEach { assertEquals(bounds.first().top, it.top, 1f) }
        bounds.drop(4).forEach { assertEquals(bounds[4].top, it.top, 1f) }
        assertTrue(bounds[4].top > bounds.first().bottom)
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(capture.width, capture.height, Bitmap.Config.ARGB_8888)
            capture.draw(Canvas(bitmap))
            val circle = bounds.last()
            val center = bitmap.getPixel(circle.center.x.toInt(), circle.center.y.toInt())
            assertTrue(bitmap.getPixel(circle.left.toInt() + 1, circle.top.toInt() + 1) != center)
            assertEquals(0xFFF48FB1.toInt(), center)
            bitmap.recycle()
        }
        compose.onNodeWithContentDescription("白色").assertIsSelected()
        compose.onNodeWithContentDescription("粉色").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("白色").assertIsNotSelected()
        compose.runOnIdle { assertEquals(0xFFF48FB1.toInt(), BarrageStore.read(context).getInt("color")) }
    }
}
