package dev.local.sunwindowdemo

import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BarrageScreenshotTest {
    @Test fun screenshotFocusDoesNotSuspendButOtherOverlaysStillDo() {
        assertFalse(BarrageHost.shouldSuspend(false, null, false, false, false))
        assertFalse(BarrageHost.shouldSuspend(true, "ScreenshotUI", false, false, false))
        assertTrue(BarrageHost.shouldSuspend(true, "NotificationShade", false, false, false))
        assertTrue(BarrageHost.shouldSuspend(true, null, false, false, false))
        assertTrue(BarrageHost.shouldSuspend(true, "ScreenshotUI", true, false, false))
        assertTrue(BarrageHost.shouldSuspend(true, "ScreenshotUI", false, true, false))
        assertTrue(BarrageHost.shouldSuspend(true, "ScreenshotUI", false, false, true))
    }

    @Test fun screenshotDecisionKeepsTheSameVisibleLyricsAndDoesNotRestartIt() {
        val lyrics = BarrageLyricsView(RuntimeEnvironment.getApplication(), "来源：连续弹幕", null) {}
        val width = View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY)
        lyrics.measure(width, height)
        lyrics.layout(0, 0, 800, 80)
        val animatorField = BarrageLyricsView::class.java.getDeclaredField("animator").apply { isAccessible = true }
        val original = animatorField.get(lyrics)
        repeat(3) {
            if (BarrageHost.shouldSuspend(true, "ScreenshotUI", false, false, false)) lyrics.pause() else lyrics.resume()
            assertEquals(View.VISIBLE, lyrics.visibility)
            assertSame(original, animatorField.get(lyrics))
        }
        lyrics.cancel()
    }
}
