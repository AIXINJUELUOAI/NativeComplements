package dev.local.sunwindowdemo

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GamePanelThemeTest {
    @Test fun onlySurfacePositionsFollowTheHandleWhileContentsKeepTheirOrder() {
        val context = RuntimeEnvironment.getApplication()
        val apps = (1..3).map { AppCatalog.App("example.$it", "应用$it", android.graphics.drawable.ColorDrawable(android.graphics.Color.BLUE)) }
        for (width in listOf(392, 640)) {
            var leftContentWidth = 0
            for (right in listOf(false, true)) {
                val launched = mutableListOf<String>()
                val panel = gameModePanel(context, apps, apps, emptyList(), width, 400, false, { launched.add(it) }, {}, right) as LinearLayout
                repeat(3) {
                    panel.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY))
                    panel.layout(0, 0, width, 400)
                }
                val rail = panel.getChildAt(if (right) 1 else 0) as LinearLayout
                val content = panel.getChildAt(if (right) 0 else 1) as FrameLayout
                val gap = (10 * context.resources.displayMetrics.density).toInt()
                assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, panel.layoutDirection)
                if (right) {
                    assertEquals(0, content.left)
                    assertEquals(width, rail.right)
                    assertEquals(gap, rail.left - content.right)
                    assertEquals(leftContentWidth, content.width)
                } else {
                    assertEquals(0, rail.left)
                    assertEquals(width, content.right)
                    assertEquals(gap, content.left - rail.right)
                    leftContentWidth = content.width
                }
                val pins = (rail.getChildAt(0) as ScrollView).getChildAt(0) as LinearLayout
                apps.forEachIndexed { index, app ->
                    assertEquals("以小窗打开${app.label}", pins.getChildAt(index).contentDescription)
                    pins.getChildAt(index).performClick()
                }
                assertEquals(apps.map { it.pkg }, launched)
                val quick = content.getChildAt(0)
                val arrow = (pins.getChildAt(3) as FrameLayout).getChildAt(0) as ImageButton
                arrow.performClick()
                val library = content.getChildAt(1)
                assertEquals(android.view.View.GONE, quick.visibility)
                assertEquals(android.view.View.VISIBLE, library.visibility)
                arrow.performClick()
                assertSame(quick, content.getChildAt(0))
                assertSame(library, content.getChildAt(1))
                assertEquals(android.view.View.VISIBLE, quick.visibility)
            }
        }
    }

    @Test fun nativeChromeTracksComposePaletteWithoutReplacingContentOrLibrary() {
        val context = RuntimeEnvironment.getApplication()
        val panel = gameModePanel(context, emptyList(), emptyList(), emptyList(), 392, 400, false, {}, {}) as LinearLayout
        val rail = panel.getChildAt(0) as LinearLayout
        val content = panel.getChildAt(1) as FrameLayout
        val quick = content.getChildAt(0) as GameQuickView
        val pins = (rail.getChildAt(0) as ScrollView).getChildAt(0) as LinearLayout
        val arrow = (pins.getChildAt(pins.childCount - 1) as FrameLayout).getChildAt(0) as ImageButton
        val iconDiameter = (36 * context.resources.displayMetrics.density).toInt()
        assertEquals(iconDiameter, arrow.layoutParams.width)
        assertEquals(iconDiameter, arrow.layoutParams.height)
        assertEquals(0, arrow.minimumWidth)
        assertEquals(0, arrow.minimumHeight)
        // Drive the same callback invoked by GameQuickView's configuration-aware Compose SideEffect.
        val update = ReflectionHelpers.getField<(ColorScheme) -> Unit>(quick, "themeChanged")
        fun assertPalette(colors: ColorScheme) {
            val container = gamePanelContainerColor(colors).toArgb()
            assertEquals(container, (rail.background as GradientDrawable).color!!.defaultColor)
            assertEquals(container, (content.background as GradientDrawable).color!!.defaultColor)
            assertEquals(colors.onPrimary.toArgb(), arrow.imageTintList!!.defaultColor)
            val disc = (arrow.background as RippleDrawable).getDrawable(0) as GradientDrawable
            assertEquals(colors.primary.toArgb(), disc.color!!.defaultColor)
            assertSame(quick, content.getChildAt(0))
        }
        val light = lightColorScheme()
        val dark = darkColorScheme()
        update(dark)
        assertPalette(dark)
        update(light)
        assertPalette(light)
        // A library created after a palette change must start with the latest material too.
        arrow.performClick()
        val library = content.getChildAt(1)
        assertEquals(gamePanelContainerColor(light).toArgb(), (library.background as GradientDrawable).color!!.defaultColor)
        update(dark)
        assertPalette(dark)
        assertSame(library, content.getChildAt(1))
        assertEquals(gamePanelContainerColor(dark).toArgb(), (library.background as GradientDrawable).color!!.defaultColor)
        update(light)
        assertPalette(light)
        assertTrue(gamePanelSectionColor(light).luminance() > gamePanelContainerColor(light).luminance())
        assertTrue(gamePanelSectionColor(dark).luminance() > gamePanelContainerColor(dark).luminance())
        assertTrue("light sections need a visible tonal step", gamePanelSectionColor(light).luminance() - gamePanelContainerColor(light).luminance() > .08f)
    }
}
