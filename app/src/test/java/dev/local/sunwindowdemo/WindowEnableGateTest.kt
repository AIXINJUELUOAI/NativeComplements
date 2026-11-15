package dev.local.sunwindowdemo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class WindowEnableGateTest {
    private lateinit var activity: Activity
    private lateinit var controller: WindowController
    @Before fun setup() {
        dev.local.sunwindowdemo.Config.hostPreferences = null
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.getSharedPreferences("corner", Context.MODE_PRIVATE).edit().clear().putBoolean("enabled", false).commit()
        Settings.Global.putInt(activity.contentResolver, "enable_freeform_support", 1)
        controller = WindowController(activity)
    }
    @After fun cleanup() { controller.close() }
    private fun request(): String? = WindowController::class.java.getDeclaredField("enableRequest").apply { isAccessible = true }.get(controller) as String?
    private fun reply(uid: Int = 1000, requestId: String? = request(), complete: Boolean = true, freeform: Boolean = true, conflict: Boolean = false, known: Boolean = true) {
        val intent = Intent(Wire.RESULT).putExtra("protocol", 16).putExtra("event", "STATUS").putExtra("requestId", requestId)
        if (complete) intent.putExtra("capabilitiesKnown", known).putExtra("supportsMultiWindow", true)
            .putExtra("supportsFreeform", freeform).putExtra("preflightReady", true).putExtra("ownershipConflict", conflict)
        WindowController::class.java.getDeclaredMethod("acceptEnableStatus", Intent::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(controller, intent, uid)
    }
    @Test fun checksEveryEnableAndIgnoresUntrustedStaleAndCancelledReplies() {
        controller.boolean("enabled", true)
        assertFalse(controller.preferences.enabled)
        assertTrue(controller.enableChecking)
        val first = request()
        reply(uid = 12345)
        assertFalse(controller.preferences.enabled)
        controller.toggleControl() // A second UI tap cancels the in-flight enable rather than starting another request.
        assertFalse(controller.enableChecking)
        assertNull(request())
        reply(requestId = first)
        assertFalse(controller.preferences.enabled)
        controller.toggleControl()
        reply(requestId = first)
        assertFalse(controller.preferences.enabled)
        reply()
        assertTrue(controller.preferences.enabled)
        controller.toggleControl()
        assertFalse(controller.preferences.enabled)
    }
    @Test fun missingCapabilityTimeoutAndSettingChangedDuringRequestAllRefuseEnable() {
        controller.toggleControl()
        reply(complete = false)
        assertFalse(controller.preferences.enabled)
        assertNotNull(controller.enablePrompt)
        controller.dismissEnablePrompt()
        controller.toggleControl()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(2001))
        assertFalse(controller.enableChecking)
        assertFalse(controller.preferences.enabled)
        controller.toggleControl()
        Settings.Global.putInt(activity.contentResolver, "enable_freeform_support", 0)
        reply()
        assertFalse(controller.preferences.enabled)
        assertTrue(controller.enablePrompt!!.contains("adb shell settings put global enable_freeform_support 1"))
    }
    @Test fun runtimeFalseAndConflictDoNotClaimSettingWriteOrRestartWillFixIt() {
        controller.toggleControl()
        reply(freeform = false)
        assertFalse(controller.preferences.enabled)
        assertTrue(controller.enablePrompt!!.contains("重启不保证解决"))
        assertEquals(1, Settings.Global.getInt(activity.contentResolver, "enable_freeform_support"))
        controller.toggleControl()
        reply(conflict = true, known = false)
        assertFalse(controller.preferences.enabled)
        assertTrue(controller.enablePrompt!!.contains("接管"))
        assertTrue(controller.enablePrompt!!.contains("LSPosed"))
        assertTrue(controller.enablePrompt!!.contains("重启"))
    }
    @Test fun genericStoreCannotBypassEnableCheckAndDisableNeverNeedsCapability() {
        val store = WindowPreferenceStore(activity)
        assertThrows(IllegalArgumentException::class.java) { store.boolean("enabled", true) }
        assertThrows(IllegalArgumentException::class.java) { store.enable(FreeformEnablePolicy.Decision.BACKEND_UNKNOWN) }
        store.enable(FreeformEnablePolicy.Decision.ALLOWED)
        assertTrue(store.read().enabled)
        Settings.Global.putInt(activity.contentResolver, "enable_freeform_support", 0)
        controller.boolean("enabled", false)
        assertFalse(store.read().enabled)
    }
}
