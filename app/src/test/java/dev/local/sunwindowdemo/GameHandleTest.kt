package dev.local.sunwindowdemo

import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GameHandleTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, 20, action, x, y, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun handleFollowsDayNightChangesWithoutReplacingViewOrTouchTarget() {
        for (right in listOf(false, true)) {
            val handle = gameModeHandle(context, right) {} as FrameLayout
            val bar = handle.getChildAt(0)
            val shape = bar.background as android.graphics.drawable.GradientDrawable
            for (mode in listOf(android.content.res.Configuration.UI_MODE_NIGHT_NO,
                android.content.res.Configuration.UI_MODE_NIGHT_YES, android.content.res.Configuration.UI_MODE_NIGHT_NO)) {
                handle.dispatchConfigurationChanged(android.content.res.Configuration(context.resources.configuration).apply { uiMode = mode })
                val dark = mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
                assertEquals((WindowPresentation.handleColor(dark) and 0x00ffffff) or 0xb8000000.toInt(), shape.color!!.defaultColor)
                assertSame(bar, handle.getChildAt(0))
                assertEquals(dp(5), bar.layoutParams.width)
                assertEquals(dp(44), bar.layoutParams.height)
            }
        }
    }

    @Test fun largerTransparentTargetKeepsSmallVisualAndOnlyLocalBackExclusion() {
        val handle = gameModeHandle(context, false) {} as FrameLayout
        handle.layout(0, 0, dp(28), dp(88))
        assertEquals(listOf(Rect(0, 0, dp(28), dp(88))), handle.systemGestureExclusionRects)
        assertEquals(dp(5), handle.getChildAt(0).layoutParams.width)
        assertEquals(dp(44), handle.getChildAt(0).layoutParams.height)
        val outside = View(context)
        assertTrue(outside.systemGestureExclusionRects.isEmpty())
        val host = GameModeHost(context, {}, {})
        try {
            val worker = ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(host, "worker")
            worker.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
            val params = host.params()
            assertEquals(dp(28), params.width)
            assertEquals(dp(88), params.height)
            assertEquals(0, params.x)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
            assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS, params.layoutInDisplayCutoutMode)
            ReflectionHelpers.setField(host, "config", Bundle().apply { putString("side", "right") })
            assertEquals(0, host.params().x)
        } finally { host.close() }
    }

    @Test @Config(qualifiers = "w400dp-h900dp-port-mdpi")
    fun portraitHandleIsCenteredAndCutoutStillWinsOverPreferredPosition() {
        val host = GameModeHost(context, {}, {})
        try {
            val bounds = context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            val params = host.params()
            assertEquals((bounds.height() - dp(88)) / 2, params.y)
            assertEquals(0, params.x)
            val preferred = (900 - 88) / 2
            assertEquals(520, dev.local.sunwindowdemo.features.gamemode.GameModePolicy.handleTop(
                400, 900, false, preferred, 28, 88, listOf(Rect(0, preferred, 40, 520))))
            assertEquals(preferred, dev.local.sunwindowdemo.features.gamemode.GameModePolicy.handleTop(
                400, 900, true, preferred, 28, 88, listOf(Rect(0, preferred, 40, 520))))
        } finally { host.close() }
    }

    @Test fun inwardSwipeOpensOnceOnEitherEdgeAndTapStillWorks() {
        for (right in listOf(false, true)) {
            var opens = 0
            val handle = gameModeHandle(context, right) { opens++ }
            val start = dp(14).toFloat()
            val end = start + dp(20) * if (right) -1 else 1
            touch(handle, MotionEvent.ACTION_DOWN, start, 30f)
            touch(handle, MotionEvent.ACTION_MOVE, end, 30f)
            touch(handle, MotionEvent.ACTION_MOVE, end, 30f)
            touch(handle, MotionEvent.ACTION_UP, end, 30f)
            assertEquals(1, opens)
            touch(handle, MotionEvent.ACTION_DOWN, start, 30f)
            touch(handle, MotionEvent.ACTION_UP, start, 30f)
            assertEquals(2, opens)
        }
    }

    @Test fun outwardVerticalCancelAndSecondPointerNeverOpen() {
        var opens = 0
        val handle = gameModeHandle(context, false) { opens++ }
        val start = dp(14).toFloat()
        touch(handle, MotionEvent.ACTION_DOWN, start, 30f)
        touch(handle, MotionEvent.ACTION_MOVE, start - dp(20), 30f)
        touch(handle, MotionEvent.ACTION_UP, start, 30f)
        touch(handle, MotionEvent.ACTION_DOWN, start, 30f)
        touch(handle, MotionEvent.ACTION_MOVE, start, 30f + dp(30))
        touch(handle, MotionEvent.ACTION_MOVE, start + dp(30), 30f)
        touch(handle, MotionEvent.ACTION_UP, start, 30f)
        touch(handle, MotionEvent.ACTION_DOWN, start, 30f)
        touch(handle, MotionEvent.ACTION_CANCEL, start, 30f)
        touch(handle, MotionEvent.ACTION_UP, start, 30f)
        touch(handle, MotionEvent.ACTION_DOWN, start, 30f)
        touch(handle, MotionEvent.ACTION_POINTER_DOWN, start, 30f)
        touch(handle, MotionEvent.ACTION_MOVE, start + dp(30), 30f)
        touch(handle, MotionEvent.ACTION_UP, start, 30f)
        assertEquals(0, opens)
    }
}
