package dev.local.sunwindowdemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.test.core.app.ApplicationProvider
import dev.local.sunwindowdemo.core.ui.ComplementsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w460dp-h480dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameQuickComposeTest {
    @get:Rule val compose = createRobolectricComposeRule()
    @Test fun quickFeedbackUsesOneNativePulseWithoutBypassingSettings() {
        var pulses = 0
        var kind = -1
        val view = object : View(ApplicationProvider.getApplicationContext<Context>()) {
            override fun performHapticFeedback(feedbackConstant: Int): Boolean {
                pulses++; kind = feedbackConstant; return true
            }
        }
        gameQuickFeedback(view)
        assertEquals(1, pulses)
        assertEquals(android.view.HapticFeedbackConstants.VIRTUAL_KEY, kind)
    }

    @Test fun materialControlsShareFourColumnCentersAndSlidersUseRealState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var preview: Float? = null
        val sliders = GameSliderState(context, { .25f }, { preview = it })
        val media = GameMediaState(context)
        val entries = QuickActionCatalog.ACTIONS.take(10).mapIndexed { index, entry ->
            GameQuickItem("$index", entry.label(), QuickActionCatalog.icon(context, entry)?.let {
                it.toBitmap(60, 60).asImageBitmap()
            })
        }
        var clicked = ""
        lateinit var capture: View
        compose.setContent {
            capture = LocalView.current
            ComplementsTheme(dynamic = false) {
                DisposableEffect(Unit) { sliders.start(); onDispose { sliders.close(); media.close() } }
                Box(Modifier.size(300.dp, 460.dp)) {
                    GameQuickContent(sliders, media, entries, { clicked = it }, mapOf("0" to true, "1" to false, "2" to null))
                }
            }
        }
        fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        fun sameX(a: String, b: String) { assertEquals(bounds(a).center.x, bounds(b).center.x, .6f) }
        sameX("brightness-icon", "volume-icon")
        sameX("brightness-icon", "media-cover")
        sameX("media-cover", "quick-icon-0")
        sameX("media-previous", "quick-icon-1")
        sameX("media-toggle", "quick-icon-2")
        sameX("media-next", "quick-icon-3")
        assertEquals(36f, bounds("media-cover").width, .5f)
        assertEquals(36f, bounds("media-cover").height, .5f)
        assertEquals(36f, bounds("quick-icon-0").width, .5f)
        assertEquals(bounds("brightness-slider").left, bounds("volume-slider").left, .5f)
        val sections = listOf("brightness-section", "volume-section", "media-section", "quick-section")
        sections.zipWithNext().forEach { (first, next) ->
            assertEquals(bounds(first).left, bounds(next).left, .5f)
            assertEquals(bounds(first).right, bounds(next).right, .5f)
            assertEquals(12f, bounds(next).top - bounds(first).bottom, .5f)
        }
        assertTrue(bounds("brightness-section").right - bounds("brightness-slider").right >= 12f)
        assertEquals(68f, bounds("volume-icon").center.y - bounds("brightness-icon").center.y, .5f)
        compose.onNodeWithTag("brightness-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(600f) }
        compose.runOnIdle { assertNotNull(preview); assertTrue(preview!! in 0f..1f) }
        compose.onNodeWithTag("quick-0").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已开启"))
        compose.onNodeWithTag("quick-1").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已关闭"))
        compose.onNodeWithTag("quick-2").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "状态未知"))
        compose.onNodeWithTag("quick-0").performClick()
        compose.runOnIdle { assertEquals("0", clicked) }
        compose.onNodeWithTag("quick-9").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("quick-0").performScrollTo()
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(capture.width, capture.height, Bitmap.Config.ARGB_8888)
            capture.draw(Canvas(bitmap))
            val directory = java.io.File(System.getProperty("nativecomplements.screenshots"))
            directory.mkdirs()
            java.io.File(directory, "game-quick-material.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
