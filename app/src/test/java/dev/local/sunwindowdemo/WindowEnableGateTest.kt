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
    private fun drainEnabledSave() {
        val worker = WindowController::class.java.getDeclaredField("enabledWorker").apply { isAccessible = true }.get(controller) as java.util.concurrent.ExecutorService
        worker.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }
    @After fun cleanup() { drainEnabledSave(); controller.close() }
    private fun request(): String? = WindowController::class.java.getDeclaredField("enableRequest").apply { isAccessible = true }.get(controller) as String?
    private fun reply(uid: Int = 1000, requestId: String? = request(), complete: Boolean = true, freeform: Boolean = true, conflict: Boolean = false, known: Boolean = true) {
        val intent = Intent(Wire.RESULT).putExtra("protocol", 16).putExtra("event", "STATUS").putExtra("requestId", requestId)
        if (complete) intent.putExtra("capabilitiesKnown", known).putExtra("supportsMultiWindow", true)
            .putExtra("supportsFreeform", freeform).putExtra("preflightReady", true).putExtra("ownershipConflict", conflict)
        WindowController::class.java.getDeclaredMethod("acceptEnableStatus", Intent::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(controller, intent, uid)
    }
    @Test fun checksEveryEnableAndIgnoresUntrustedStaleAndCancelledReplies() {
        controller.confirmEnablePrompt() // No pending warning is not an enable action.
        assertFalse(controller.preferences.enabled)
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
        drainEnabledSave()
        assertTrue(controller.preferences.enabled)
        controller.toggleControl()
        drainEnabledSave()
        assertFalse(controller.preferences.enabled)
    }
    @Test fun missingCapabilityTimeoutAndSettingChangesWarnUntilUserConfirms() {
        controller.toggleControl()
        reply(complete = false)
        assertFalse(controller.preferences.enabled)
        assertNotNull(controller.enablePrompt)
        controller.dismissEnablePrompt()
        controller.confirmEnablePrompt()
        assertFalse(controller.preferences.enabled)
        controller.toggleControl()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(2001))
        assertFalse(controller.enableChecking)
        assertFalse(controller.preferences.enabled)
        assertNotNull(controller.enablePrompt)
        controller.confirmEnablePrompt()
        drainEnabledSave()
        assertTrue(controller.preferences.enabled)
        controller.toggleControl()
        drainEnabledSave()
        controller.toggleControl()
        Settings.Global.putInt(activity.contentResolver, "enable_freeform_support", 0)
        reply()
        assertFalse(controller.preferences.enabled)
        assertTrue(controller.enablePrompt!!.contains("adb shell settings put global enable_freeform_support 1"))
        controller.confirmEnablePrompt()
        drainEnabledSave()
        assertTrue(controller.preferences.enabled)
        assertNull(controller.enablePrompt)
        assertEquals(0, Settings.Global.getInt(activity.contentResolver, "enable_freeform_support"))
        controller.toggleControl()
        drainEnabledSave()
        Settings.Global.putString(activity.contentResolver, "enable_freeform_support", null)
        controller.toggleControl()
        drainEnabledSave()
        assertFalse(controller.preferences.enabled)
        assertNotNull(controller.enablePrompt)
        controller.confirmEnablePrompt()
        drainEnabledSave()
        assertTrue(controller.preferences.enabled)
        assertNull(Settings.Global.getString(activity.contentResolver, "enable_freeform_support"))
    }
    @Test fun runtimeFalseAndConflictDoNotClaimSettingWriteOrRestartWillFixIt() {
        controller.toggleControl()
        reply(freeform = false)
        assertFalse(controller.preferences.enabled)
        assertTrue(controller.enablePrompt!!.contains("未检测到多窗口能力"))
        assertFalse(controller.enablePrompt!!.contains("重启后即可"))
        assertEquals(1, Settings.Global.getInt(activity.contentResolver, "enable_freeform_support"))
        controller.toggleControl()
        reply(conflict = true, known = false)
        assertFalse(controller.preferences.enabled)
        assertTrue(controller.enablePrompt!!.contains("接管"))
        assertTrue(controller.enablePrompt!!.contains("小窗开关"))
        assertFalse(controller.enablePrompt!!.contains("本次启动不会热转移"))
        assertTrue(controller.enablePrompt!!.contains("重启"))
        controller.confirmEnablePrompt()
        drainEnabledSave()
        assertTrue(controller.preferences.enabled) // Diagnostics do not replace backend ownership enforcement.
        assertEquals(1, Settings.Global.getInt(activity.contentResolver, "enable_freeform_support"))
    }
    @Test fun genericStoreKeepsExplicitEnableAndDisableNeverNeedsCapability() {
        val store = WindowPreferenceStore(activity)
        assertThrows(IllegalArgumentException::class.java) { store.boolean("enabled", true) }
        store.enable()
        assertTrue(store.read().enabled)
        Settings.Global.putInt(activity.contentResolver, "enable_freeform_support", 0)
        controller.boolean("enabled", false)
        drainEnabledSave()
        assertFalse(store.read().enabled)
    }
}
