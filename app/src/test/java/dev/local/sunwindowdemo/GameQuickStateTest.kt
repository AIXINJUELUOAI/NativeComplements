package dev.local.sunwindowdemo

import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "notnight")
class GameQuickStateTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun snapshotsUseRealPlatformStateAndNeverInventActionStates() {
        Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 1)
        Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 1)
        context.getSystemService(WifiManager::class.java).isWifiEnabled = true
        context.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        val state = GameQuickState(context)
        assertEquals(GameQuickState.TOGGLE_IDS, state.states.keys)
        assertTrue(state.states.values.all { it == null })
        state.start()
        assertEquals(true, state.states["wifi"])
        assertEquals(true, state.states["rotation"])
        assertEquals(true, state.states["autobrightness"])
        assertEquals(true, state.states["airplane"])
        assertEquals(true, state.states["mute"])
        assertEquals(false, state.states["darkmode"])
        assertEquals(context.getSystemService(PowerManager::class.java).isPowerSaveMode, state.states["batterysaver"])
        assertFalse(state.states.containsKey("record"))
        assertFalse(state.states.containsKey("screenshot"))
        assertFalse(state.states.containsKey("current"))
        assertNull(state.states["flashlight"])
        state.close()
    }

    @Test fun settingsAndBroadcastsRefreshOnlyWhileStartedAndRestartIsSupported() {
        Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
        val state = GameQuickState(context)
        state.start(); state.start()
        assertTrue(state.observing)
        Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1)
        idle()
        assertEquals(false, state.states["rotation"])
        context.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        context.sendBroadcast(Intent("android.media.VOLUME_CHANGED_ACTION")); idle()
        assertEquals(true, state.states["mute"])
        state.close(); state.close()
        assertFalse(state.observing)
        val closed = state.states
        Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
        context.sendBroadcast(Intent("android.media.VOLUME_CHANGED_ACTION")); idle()
        assertEquals(closed, state.states)
        state.start()
        assertEquals(true, state.states["rotation"])
        state.close()
    }

    @Test fun exceptionsAndMissingSettingsRemainUnknownRatherThanFalse() {
        val unavailable = object : android.content.ContextWrapper(context) {
            override fun getSystemService(name: String): Any? { throw SecurityException("denied") }
        }
        Settings.System.putString(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, null)
        Settings.System.putString(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, null)
        Settings.Global.putString(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, null)
        val state = GameQuickState(unavailable)
        state.start()
        listOf("wifi", "mute", "dnd", "batterysaver", "flashlight", "rotation", "autobrightness", "airplane").forEach {
            assertNull(it, state.states[it])
        }
        state.close()
    }

    @Test fun torchCallbacksIgnoreWrongCameraAndLateCallbacksAfterClose() {
        val state = GameQuickState(context)
        state.start()
        org.robolectric.util.ReflectionHelpers.setField(state, "torchCamera", "back")
        state.torchCallback.onTorchModeChanged("front", true)
        assertNull(state.states["flashlight"])
        state.torchCallback.onTorchModeChanged("back", true)
        assertEquals(true, state.states["flashlight"])
        state.torchCallback.onTorchModeChanged("back", false)
        assertEquals(false, state.states["flashlight"])
        state.torchCallback.onTorchModeUnavailable("back")
        assertNull(state.states["flashlight"])
        state.close()
        state.torchCallback.onTorchModeChanged("back", true)
        assertNull(state.states["flashlight"])
    }
}
