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
