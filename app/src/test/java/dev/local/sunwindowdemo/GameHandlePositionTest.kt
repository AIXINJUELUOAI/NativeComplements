package dev.local.sunwindowdemo

import android.graphics.Rect
import android.os.Bundle
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import dev.local.sunwindowdemo.core.ui.ComplementsTheme
import dev.local.sunwindowdemo.core.ui.SettingsPage
import dev.local.sunwindowdemo.features.gamemode.GameHandlePositionScreen
import dev.local.sunwindowdemo.features.gamemode.GameHandleGeometry
import dev.local.sunwindowdemo.features.gamemode.GameHandlePositionSettings
import dev.local.sunwindowdemo.features.gamemode.GameModeStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GameHandlePositionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() { GameModeStore.prefs(context).edit().clear().commit() }

    @Test fun absentPositionKeysKeepLegacyPlacementAndSideWithoutWritingMigration() {
        GameModeStore.prefs(context).edit().putString("side", "right").commit()
        val config = GameModeStore.read(context)
        for (landscape in listOf(false, true)) {
            assertEquals("right", GameModeStore.handleSide(config, landscape))
            assertFalse(config.containsKey(GameModeStore.handlePositionKey(landscape)))
            assertTrue(GameModeStore.handlePosition(config, landscape).isNaN())
            assertFalse(GameModeStore.prefs(context).contains(GameModeStore.handlePositionKey(landscape)))
        }
        val portrait = GameHandleGeometry(400, 900, 1f, Rect(0, 24, 400, 850), emptyList())
        val landscape = GameHandleGeometry(900, 400, 1f, Rect(0, 24, 900, 376), emptyList())
        assertEquals((900 - 88) / 2, portrait.bounds(true).top)
        assertEquals(24 + 28, landscape.bounds(true).top)
    }

    @Test fun orientationSettingsClampAndResetIndependentlyWhilePreservingLegacyKey() {
        GameModeStore.side(context, "right")
        GameModeStore.handleSide(context, false, "left")
        GameModeStore.handlePosition(context, false, -.5f)
        GameModeStore.handlePosition(context, true, 1.5f)
        var config = GameModeStore.read(context)
        assertEquals("left", GameModeStore.handleSide(config, false))
        assertEquals("right", GameModeStore.handleSide(config, true))
        assertEquals(0f, GameModeStore.handlePosition(config, false), 0f)
        assertEquals(1f, GameModeStore.handlePosition(config, true), 0f)
        assertThrows(IllegalArgumentException::class.java) { GameModeStore.handlePosition(context, true, Float.NaN) }
        GameModeStore.resetHandlePosition(context, false)
        config = GameModeStore.read(context)
        assertEquals("right", config.getString("side"))
        assertEquals("right", GameModeStore.handleSide(config, false))
        assertTrue(GameModeStore.handlePosition(config, false).isNaN())
        assertEquals(1f, GameModeStore.handlePosition(config, true), 0f)
        GameModeStore.prefs(context).edit().putFloat(GameModeStore.handlePositionKey(false), Float.POSITIVE_INFINITY).commit()
        assertTrue(GameModeStore.handlePosition(GameModeStore.read(context), false).isNaN())
        GameModeStore.prefs(context).edit().putInt(GameModeStore.handlePositionKey(false), 1).commit()
        assertEquals(1f, GameModeStore.handlePosition(GameModeStore.read(context), false), 0f)
    }

    @Test fun placementStaysInSafeBoundsAtEitherEndAndAvoidsAllEdgeCutouts() {
        val cuts = listOf(Rect(0, 10, 40, 120), Rect(0, 120, 40, 230), Rect(972, 350, 1000, 480))
        val model = GameHandleGeometry(1000, 500, 1f, Rect(0, 24, 1000, 480), cuts)
        assertEquals(230, model.bounds(false, 0f).top)
        assertEquals(262, model.bounds(true, 1f).top)
        for (right in listOf(false, true)) for (position in listOf(-1f, 0f, .5f, 1f, 2f)) {
            val target = model.bounds(right, position)
            assertFalse(target.isEmpty)
            assertTrue(model.safe.contains(target))
            assertTrue(cuts.none { Rect.intersects(it, target) })
            assertEquals(if (right) 1000 - 28 else 0, target.left)
        }
        val sideBar = model.copy(safe = Rect(0, 24, 952, 480), cutouts = emptyList())
        assertEquals(952, sideBar.bounds(true, .5f).right)
        assertTrue(model.copy(cutouts = listOf(Rect(0, 0, 40, 500))).bounds(false).isEmpty)
        val tiny = model.copy(height = 50, safe = Rect(0, 10, 1000, 40), cutouts = emptyList())
        assertEquals(Rect(0, 10, 28, 40), tiny.bounds(false, 1f))
    }

    @Test fun simulatedOrientationRotatesTheSameDisplayAndExclusionsReversibly() {
        val portrait = GameHandleGeometry(400, 900, 1f, Rect(0, 24, 400, 852), listOf(Rect(160, 0, 240, 24)))
        val landscape = portrait.rotated()
        assertEquals(900, landscape.width)
        assertEquals(400, landscape.height)
        assertEquals(Rect(48, 0, 876, 400), landscape.safe)
        assertEquals(listOf(Rect(876, 160, 900, 240)), landscape.cutouts)
        assertEquals(portrait, landscape.rotated())
        val target = portrait.bounds(false, .73f)
        assertEquals(.73f, portrait.position(target.top.toFloat()), .002f)
    }

    @Test fun runtimeParametersUseTheExactSameGeometryAsPreviewAndKeepLocalBackExclusion() {
        val host = GameModeHost(context, {}, {})
        try {
            val worker = ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(host, "worker")
            worker.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
            val model = GameHandleGeometry.read(context)
            for (right in listOf(false, true)) for (position in listOf(0f, .4f, 1f)) {
                val config = Bundle().apply {
                    putString(GameModeStore.handleSideKey(model.landscape), if (right) "right" else "left")
                    putFloat(GameModeStore.handlePositionKey(model.landscape), position)
                }
                ReflectionHelpers.setField(host, "config", config)
                val expected = model.bounds(right, position)
                val params = host.params()
                val left = if (right) model.width - params.x - params.width else params.x
                assertEquals(expected, Rect(left, params.y, left + params.width, params.y + params.height))
                assertEquals(0, params.fitInsetsTypes)
                assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
                val handle = gameModeHandle(context, right) {}
                handle.layout(0, 0, params.width, params.height)
                assertEquals(listOf(Rect(0, 0, params.width, params.height)), handle.systemGestureExclusionRects)
            }
        } finally { host.close() }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w460dp-h900dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameHandlePositionPreviewTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun detailPageHasSingleSideSwitchAndReusesBothPreviewOrientations() {
        val context = RuntimeEnvironment.getApplication()
        GameModeStore.prefs(context).edit().clear().commit()
        val actual = GameHandleGeometry.read(compose.activity)
        assertFalse("preview model=$actual", actual.bounds(true).isEmpty)
        lateinit var captureView: android.view.View
        compose.setContent {
            captureView = LocalView.current
            ComplementsTheme(dynamic = false) {
                SettingsPage("小白条位置", onBack = {}) { GameHandlePositionScreen() }
            }
        }
        fun screenshot(name: String) {
            compose.waitForIdle()
            compose.runOnIdle {
                val directory = java.io.File(requireNotNull(System.getProperty("nativecomplements.screenshots")))
                directory.mkdirs()
                val view = captureView.rootView
                val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        compose.onNodeWithText("在右侧呼出").assertIsOff().performClick().assertIsOn()
        screenshot("game-handle-portrait")
        compose.onNodeWithText("横屏").performClick()
        compose.onNodeWithText("在右侧呼出").assertIsOff().performClick().assertIsOn()
        screenshot("game-handle-landscape")
        compose.onNodeWithText("在右侧呼出").performClick().assertIsOff()
        compose.onNodeWithText("竖屏").performClick()
        compose.onNodeWithText("在右侧呼出").assertIsOn()
        compose.onNodeWithText("左侧").assertDoesNotExist()
        compose.onNodeWithText("右侧").assertDoesNotExist()
    }

    @Test fun landscapePreviewAttachesToBothFrameEdgesDespiteRotatedSafeInsets() {
        val model = GameHandleGeometry(400, 900, 1f, Rect(0, 24, 400, 852), emptyList())
        val rotated = model.rotated()
        assertTrue(rotated.safe.left > 0)
        var right by mutableStateOf(false)
        lateinit var captureView: android.view.View
        compose.setContent {
            captureView = LocalView.current
            ComplementsTheme(dynamic = false) {
                GameHandlePositionSettings(Bundle().apply {
                    putString(GameModeStore.handleSideKey(true), if (right) "right" else "left")
                }, model)
            }
        }
        compose.onNodeWithText("横屏").performClick()
        compose.onNodeWithText("拖动预览中的", substring = true).assertDoesNotExist()
        compose.onNodeWithText("关闭时在屏幕左侧呼出").assertDoesNotExist()
        for (side in listOf(false, true)) {
            compose.runOnIdle { right = side }
            val bounds = compose.onNodeWithTag("game-handle-position-preview").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle {
                val view = captureView.rootView
                val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                val scale = minOf(bounds.width / rotated.width, bounds.height / rotated.height)
                val edge = bounds.left + if (side) (bounds.width + rotated.width * scale) / 2 else (bounds.width - rotated.width * scale) / 2
                val whiteColumns = (bounds.left.roundToInt() until bounds.right.roundToInt()).filter { x ->
                    (bounds.top.roundToInt() until bounds.bottom.roundToInt()).any { y ->
                        val color = bitmap.getPixel(x, y)
                        android.graphics.Color.red(color) > 252 && android.graphics.Color.green(color) > 252 && android.graphics.Color.blue(color) > 252
                    }
                }
                bitmap.recycle()
                assertTrue("white bar missing", whiteColumns.isNotEmpty())
                val barEdge = if (side) whiteColumns.last() + 1 else whiteColumns.first()
                assertTrue("side=$side frame=$edge bar=$barEdge", abs(edge.roundToInt() - barEdge) <= 2)
            }
        }
    }

    @Test fun dragSliderOrientationSwitchAndResetShareIndependentSavedPositions() {
        val context = RuntimeEnvironment.getApplication()
        GameModeStore.prefs(context).edit().clear().commit()
        val realModel = GameHandleGeometry(400, 900, 1f, Rect(0, 24, 400, 852), emptyList())
        compose.setContent {
            var config by remember { mutableStateOf(GameModeStore.read(context)) }
            DisposableEffect(Unit) {
                val prefs = GameModeStore.prefs(context)
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> config = GameModeStore.read(context) }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }
            ComplementsTheme(dynamic = false) { GameHandlePositionSettings(config, realModel) }
        }
        compose.onNodeWithTag("game-handle-position-preview").performTouchInput {
            swipe(Offset(center.x, height * .4f), Offset(center.x, height * .75f))
        }
        var portrait = Float.NaN
        compose.runOnIdle {
            portrait = GameModeStore.handlePosition(GameModeStore.read(context), false)
            assertTrue(portrait > .5f && portrait <= 1f)
            assertTrue(GameModeStore.handlePosition(GameModeStore.read(context), true).isNaN())
        }
        compose.onNodeWithText("横屏").performClick()
        compose.onNodeWithText("在右侧呼出").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithContentDescription("游戏条上下位置").performTouchInput {
            swipe(Offset(width * .3f, center.y), Offset(width * .7f, center.y))
        }
        compose.runOnIdle {
            val config = GameModeStore.read(context)
            assertEquals(portrait, GameModeStore.handlePosition(config, false), 0f)
            assertTrue(GameModeStore.handlePosition(config, true) > .5f)
            assertEquals("right", GameModeStore.handleSide(config, true))
            assertEquals("left", GameModeStore.handleSide(config, false))
        }
        compose.onNodeWithText("重置横屏默认位置").performClick()
        compose.runOnIdle {
            val config = GameModeStore.read(context)
            assertTrue(GameModeStore.handlePosition(config, true).isNaN())
            assertEquals("left", GameModeStore.handleSide(config, true))
            assertEquals(portrait, GameModeStore.handlePosition(config, false), 0f)
        }
    }
}
