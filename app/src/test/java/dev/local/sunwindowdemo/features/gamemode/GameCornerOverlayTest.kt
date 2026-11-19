package dev.local.sunwindowdemo

import android.view.View
import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class GameCornerOverlayTest {
    @Test fun rangeWindowsAreSmallNonTouchableAndNonFocusable() {
        val overlay = GameCornerOverlay(RuntimeEnvironment.getApplication())
        val p = overlay.parameters(100, 1900, 900)
        assertEquals(100, p.width); assertEquals(100, p.height)
        assertEquals(1900, p.x); assertEquals(900, p.y)
        assertTrue(p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS, p.layoutInDisplayCutoutMode)
        overlay.close()
    }
    @Test fun showingAndRevokingRangeWindowsKeepsProtectedAreasClear() {
        val overlay = GameCornerOverlay(RuntimeEnvironment.getApplication())
        try {
            overlay.update(2000, 1000, 10f, intArrayOf(0, 950, 50, 1000), true)
            val views = ReflectionHelpers.getField<List<View>>(overlay, "views")
            assertEquals(2, views.size)
            val view = views.first()
            view.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 100, 100)
            val bitmap = android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            assertEquals(0, android.graphics.Color.alpha(bitmap.getPixel(20, 80)))
            assertTrue(android.graphics.Color.alpha(bitmap.getPixel(60, 80)) > 0)
            assertEquals(0, android.graphics.Color.alpha(bitmap.getPixel(90, 10)))
            overlay.update(2000, 1000, 10f, intArrayOf(), false)
            assertTrue(ReflectionHelpers.getField<List<View>>(overlay, "views").isEmpty())
            overlay.update(2000, 1000, 10f, intArrayOf(), true)
            assertEquals(2, ReflectionHelpers.getField<List<View>>(overlay, "views").size)
            overlay.update(0, 0, 10f, intArrayOf(), true)
            assertTrue(ReflectionHelpers.getField<List<View>>(overlay, "views").isEmpty())
        } finally { overlay.close() }
    }
    @Test fun visualizationNeverCreatesWindowsWhenDisabledOrGeometryRevoked() {
        val overlay = GameCornerOverlay(RuntimeEnvironment.getApplication())
        overlay.update(2000, 1000, 10f, intArrayOf(), false)
        assertTrue(ReflectionHelpers.getField<List<View>>(overlay, "views").isEmpty())
        overlay.update(0, 0, 10f, intArrayOf(), true)
        assertTrue(ReflectionHelpers.getField<List<View>>(overlay, "views").isEmpty())
        overlay.close()
    }
}
