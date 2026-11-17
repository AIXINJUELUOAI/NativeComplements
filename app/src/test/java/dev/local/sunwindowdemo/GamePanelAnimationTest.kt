package dev.local.sunwindowdemo

import android.animation.ValueAnimator
import android.os.Bundle
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GamePanelAnimationTest {
    @Test fun bothEdgesSlideFromOutsideToRestAndBackWithoutChangingAlpha() {
        for (right in listOf(false, true)) {
            val view = View(RuntimeEnvironment.getApplication())
            var finished = 0
            val direction = if (right) 1 else -1
            val enter = gamePanelSlide(view, right, true, 300) { finished++ }
            assertEquals(300f * direction, view.translationX, 0f)
            assertEquals(1f, view.alpha, 0f)
            assertEquals(240L, enter.duration)
            enter.start(); enter.currentPlayTime = 120
            assertTrue(view.translationX * direction in 0f..299f)
            enter.end()
            assertEquals(0f, view.translationX, 0f)
            assertEquals(1, finished)
            val exit = gamePanelSlide(view, right, false, 300) { finished++ }
            assertEquals(180L, exit.duration)
            exit.start(); exit.end()
            assertEquals(300f * direction, view.translationX, 0f)
            assertEquals(2, finished)
        }
    }

    @Test fun cancelDoesNotRemoveWindowAndExitCanStartMidEntry() {
        val view = View(RuntimeEnvironment.getApplication())
        var finished = 0
        val enter = gamePanelSlide(view, false, true, 300) { finished++ }
        enter.start(); enter.currentPlayTime = 90
        val interrupted = view.translationX
        enter.cancel()
        assertEquals(0, finished)
        val exit = gamePanelSlide(view, false, false, 300) { finished++ }
        exit.start(); exit.currentPlayTime = 0
        assertEquals(interrupted, view.translationX, .1f)
        exit.cancel()
        assertEquals(0, finished)
    }

    @Test fun hostForcedHideCancelsAnimationWithoutItsCompletionCallback() {
        val context = RuntimeEnvironment.getApplication()
        val host = GameModeHost(context, {}, {})
        try {
            val worker = ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(host, "worker")
            worker.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
            var finished = 0
            val animator = gamePanelSlide(View(context), true, false, 300) { finished++ }
            animator.start()
            ReflectionHelpers.setField(host, "panelAnimator", animator)
            ReflectionHelpers.setField(host, "collapsing", true)
            host.environment(null, false, true)
            assertEquals(0, finished)
            assertNull(ReflectionHelpers.getField<ValueAnimator?>(host, "panelAnimator"))
            assertFalse(ReflectionHelpers.getField<Boolean>(host, "collapsing"))
        } finally { host.close() }
    }
}
