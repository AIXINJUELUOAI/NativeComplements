package dev.local.sunwindowdemo

import android.app.Activity
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GameSlidersTest {
    private fun activity() = Robolectric.buildActivity(Activity::class.java).setup()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun refreshNeverWritesAndUserMethodsClampWithoutChangingModeOrRinger() {
        val controller = activity()
        val context = controller.get()
        val writes = mutableListOf<Float>()
        val audio = context.getSystemService(AudioManager::class.java)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 1)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
        val ring = audio.getStreamVolume(AudioManager.STREAM_RING)
        val ringer = audio.ringerMode
        val state = GameSliderState(context, { .4f }, { writes += it })
        state.start(); state.refresh()
        assertTrue(writes.isEmpty())
        assertEquals(3f, state.volume, 0f)
        state.setBrightnessProgress(-20f); state.setBrightnessProgress(1200f)
        assertEquals(listOf(0f, 1f), writes)
        state.setVolumeProgress(Float.MAX_VALUE)
        assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        state.setVolumeProgress(-Float.MAX_VALUE)
        assertEquals(audio.getStreamMinVolume(AudioManager.STREAM_MUSIC), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(ring, audio.getStreamVolume(AudioManager.STREAM_RING))
        assertEquals(ringer, audio.ringerMode)
        assertEquals(1, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE))
        state.close(); controller.pause().stop().destroy()
    }

    @Test fun startCloseAreRepeatableAndExternalChangesRefreshOnlyWhileStarted() {
        val controller = activity()
        val context = controller.get()
        var value = .2f
        var reads = 0
        val state = GameSliderState(context, { reads++; value }, {})
        state.start(); state.start()
        assertTrue(state.observing)
        value = .7f
        context.contentResolver.notifyChange(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), null)
        idle()
        assertEquals(gameBrightnessProgress(.7f).toFloat(), state.brightness, 0f)
        context.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, 2, 0)
        context.sendBroadcast(Intent("android.media.VOLUME_CHANGED_ACTION")); idle()
        assertEquals(2f, state.volume, 0f)
        state.close()
        val closedReads = reads
        context.contentResolver.notifyChange(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), null)
        context.sendBroadcast(Intent("android.media.VOLUME_CHANGED_ACTION")); idle()
        assertEquals(closedReads, reads)
        assertFalse(state.observing)
        state.start(); assertTrue(state.observing)
        state.close(); state.close(); controller.pause().stop().destroy()
    }

    @Test fun gammaRangeAndDraggingRetainPreviewUntilExternalSettingsYield() {
        assertEquals(0f, gameBrightnessLinear(0), .00001f)
        assertEquals(1f, gameBrightnessLinear(1000), .00001f)
        assertEquals(1f / 12f, gameBrightnessLinear(500), .00001f)
        (0..1000 step 50).forEach { assertEquals(it.toDouble(), gameBrightnessProgress(gameBrightnessLinear(it)).toDouble(), 1.0) }
        val controller = activity()
        val context = controller.get()
        var value = .4f
        val writes = mutableListOf<Float>()
        val state = GameSliderState(context, { value }, { writes += it }, { .1f.. .8f })
        state.start(); state.beginBrightness(); state.setBrightnessProgress(500f)
        assertEquals(.1f + .7f / 12f, writes.single(), .00001f)
        state.refresh(); assertEquals(500f, state.brightness, 0f)
        state.endBrightness(); assertEquals(500f, state.brightness, 0f)
        state.beginBrightness(); value = .8f
        context.contentResolver.notifyChange(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), null); idle()
        assertEquals(500f, state.brightness, 0f)
        assertTrue(writes.last().isNaN()); assertNull(state.lastPreview)
        state.endBrightness(); assertEquals(1000f, state.brightness, 0f)
        state.setBrightnessProgress(-10f); state.setBrightnessProgress(1200f)
        assertEquals(.1f, writes[writes.lastIndex - 1], .00001f)
        assertEquals(.8f, writes.last(), .00001f)
        state.close(); controller.pause().stop().destroy()
    }

    @Test fun closeClearsOnlyOwnedPreviewAndNeverPersistsBrightness() {
        val controller = activity()
        val context = controller.get()
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 85)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 1)
        val writes = mutableListOf<Float>()
        val state = GameSliderState(context, { .3f }, { writes += it })
        state.start(); state.close(); assertTrue(writes.isEmpty())
        state.start(); state.setBrightnessProgress(1000f); state.refresh()
        assertEquals(1000f, state.brightness, 0f)
        state.close(); assertTrue(writes.last().isNaN()); assertNull(state.lastPreview)
        val count = writes.size
        state.setBrightnessProgress(500f); assertEquals(count, writes.size)
        assertEquals(85, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
        assertEquals(1, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE))
        controller.pause().stop().destroy()
    }

    @Test fun failedClearRetainsOwnershipUntilRetrySucceeds() {
        val controller = activity()
        var failClear = true
        var attempts = 0
        val state = GameSliderState(controller.get(), { .2f }, {
            if (it.isNaN()) { attempts++; if (failClear) throw SecurityException("clear denied") }
        })
        state.start(); state.setBrightnessProgress(700f); state.close()
        assertTrue(attempts > 0); assertNotNull(state.lastPreview)
        failClear = false
        state.close(); assertNull(state.lastPreview); assertTrue(attempts > 1)
        controller.pause().stop().destroy()
    }

    @Test fun iconsResolveThroughModuleContextRatherThanHostIds() {
        val controller = activity()
        val context = controller.get()
        var requested: String? = null
        val host = object : android.content.ContextWrapper(context) {
            override fun getPackageName() = "com.android.systemui"
            override fun createPackageContext(packageName: String, flags: Int): android.content.Context {
                requested = packageName; return context
            }
        }
        assertNotNull(gameSliderIcon(host, "gm_brightness")); assertEquals(Wire.PKG, requested)
        assertNotNull(gameSliderIcon(host, "gm_volume")); assertNull(gameSliderIcon(host, "not_a_slider_icon"))
        controller.pause().stop().destroy()
    }

    @Test fun volumeDragAndNonFiniteInputsDoNotCauseExternalOverwriteOrWrites() {
        val controller = activity()
        val context = controller.get()
        val writes = mutableListOf<Float>()
        val state = GameSliderState(context, { .4f }, { writes += it })
        state.start(); state.beginVolume(); state.setVolumeProgress(3f)
        context.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0)
        state.refresh(); assertEquals(3f, state.volume, 0f)
        state.endVolume(); assertEquals(1f, state.volume, 0f)
        state.setBrightnessProgress(Float.NaN); state.setVolumeProgress(Float.NaN)
        assertTrue(writes.isEmpty())
        state.close(); controller.pause().stop().destroy()
    }

    @Implements(AudioManager::class)
    class FixedVolumeAudioManager : ShadowAudioManager() {
        @Implementation fun isVolumeFixed(): Boolean = true
    }

    @Test
    @Config(shadows = [FixedVolumeAudioManager::class])
    fun unavailableBrightnessFixedVolumeAndWriteFailuresDisableSafely() {
        val controller = activity()
        val context = controller.get()
        val state = GameSliderState(context, { Float.NaN }, { error("must not write") })
        state.start(); assertFalse(state.brightnessEnabled); assertFalse(state.volumeEnabled)
        state.setBrightnessProgress(500f); state.setVolumeProgress(5f)
        val failing = GameSliderState(context, { .5f }, { throw SecurityException("denied") })
        failing.start(); failing.setBrightnessProgress(500f); assertFalse(failing.brightnessEnabled)
        failing.close(); state.close(); controller.pause().stop().destroy()
    }
}
