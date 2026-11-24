package dev.local.sunwindowdemo

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.os.PowerManager
import android.app.KeyguardManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SuggestionHostTest {
    @Test fun fullscreenOpenDoesNotNeedWindowConfigOrBackendAndWindowOpenRejectsImmediately() {
        val context = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        Shadows.shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        val intent = Intent(Intent.ACTION_VIEW).setPackage("example.target")
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "example.target"
                name = "example.target.Main"
                exported = true
                applicationInfo = ApplicationInfo().apply { packageName = "example.target"; uid = 12345 }
            }
        }
        Shadows.shadowOf(context.packageManager).addResolveInfoForIntent(intent, info)
        val host = SuggestionHost(context)
        val settings = Bundle().apply { putBoolean("enabled", true); putBoolean("window", false) }
        ReflectionHelpers.setField(host, "settings", settings)
        val open = SuggestionHost::class.java.getDeclaredMethod("open", Intent::class.java, String::class.java, Runnable::class.java).apply { isAccessible = true }
        val previous = SystemUiHost.live
        SystemUiHost.live = null
        var completed = 0
        try {
            open.invoke(host, intent, "example.target", Runnable { completed++ })
            assertEquals(1, completed)
            assertEquals("example.target", Shadows.shadowOf(context).nextStartedActivity.`package`)
            settings.putBoolean("window", true)
            open.invoke(host, intent, "example.target", Runnable { completed++ })
            assertEquals(1, completed)
            val attempt = ReflectionHelpers.getField<dev.local.sunwindowdemo.features.suggestions.SuggestionRules.Attempt>(host, "opening")
            assertFalse(attempt.running())
            assertNull(Shadows.shadowOf(context).nextStartedActivity)
        } finally {
            host.close()
            SystemUiHost.live = previous
        }
    }
}
