package dev.local.sunwindowdemo

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.*
import android.graphics.drawable.*
import android.os.Bundle
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HostBridgeTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun hostSnapshotPreservesExistingOrderAndClearsRemovedSettings() {
        val old = context.getSharedPreferences("host-test", 0)
        old.edit().putString("apps", "com.old.app").putBoolean("enabled", true).putInt("radius", 230).commit()
        val snapshot = Bundle().apply {
            putString("apps", "com.example.two,com.example.one")
            putBoolean("enabled", false)
            putStringArrayList("blocked", arrayListOf("com.example.blocked"))
        }
        ConfigProvider.copy(snapshot, old)
        assertEquals("com.example.two,com.example.one", old.getString("apps", ""))
        assertFalse(old.getBoolean("enabled", true))
        assertFalse(old.contains("radius"))
        assertEquals(setOf("com.example.blocked"), old.getStringSet("blocked", emptySet()))
    }

    @Test fun settingsProviderAllowsHostAndRejectsOtherApplicationUid() {
        val systemUi = PackageInfo().apply {
            packageName = "com.android.systemui"
            applicationInfo = ApplicationInfo().apply { packageName = "com.android.systemui"; uid = 1000 }
        }
        shadowOf(context.packageManager).installPackage(systemUi)
        context.getSharedPreferences("corner", 0).edit().putString("apps", "com.example.first").commit()
        val provider = Robolectric.buildContentProvider(ConfigProvider::class.java).create().get()
        try {
            ShadowBinder.setCallingUid(1000)
            assertEquals("com.example.first", requireNotNull(provider.call("read", null, null)).getString("apps"))
            ShadowBinder.setCallingUid(23456)
            assertThrows(SecurityException::class.java) { provider.call("read", null, null) }
        } finally { ShadowBinder.setCallingUid(Process.myUid()) }
    }

    @Test fun togglePersistsAndNotifiesSystemUiWithoutAccessibilityIntent() {
        val store = WindowPreferenceStore(context)
        store.boolean("enabled", false)
        assertFalse(WindowPreferenceStore(context).read().enabled)
        val signal = shadowOf(context.applicationContext as android.app.Application).broadcastIntents.last()
        assertEquals(ConfigProvider.CHANGED, signal.action)
        assertEquals("com.android.systemui", signal.`package`)
        store.boolean("enabled", true)
        assertTrue(WindowPreferenceStore(context).read().enabled)
    }

    @Test fun shortcutLibraryPersistsBeyondFanLimitWithoutDroppingOrder() {
        val store = WindowPreferenceStore(context)
        val targets = (0 until 12).map { "com.example.app$it" }
        store.apps(targets)
        store.number("fanCount", 2)
        val restored = WindowPreferenceStore(context).read()
        assertEquals(targets, restored.apps)
        assertEquals(2, restored.fanCount)
        store.number("fanCount", 99)
        assertEquals(4, WindowPreferenceStore(context).read().fanCount)
        assertEquals(targets, WindowPreferenceStore(context).read().apps)
    }

    @Test fun shortcutPayloadIsRejectedBeforeChangingSavedOrder() {
        val store = WindowPreferenceStore(context)
        val original = listOf("com.example.first", "com.example.second")
        store.apps(original)
        val oversized = (0 until 40).map { "shortcut:v1:0:com.example.app$it:${"x".repeat(2000)}" }
        assertTrue(oversized.all(ShortcutTarget::validKey))
        assertThrows(IllegalArgumentException::class.java) { dev.local.sunwindowdemo.Config.validateTargets(oversized, oversized.map { "快捷功能" }) }
        assertThrows(IllegalArgumentException::class.java) { store.apps(oversized) }
        assertEquals(original, store.read().apps)
    }

    @Test fun legacyWideIconKeepsAspectRatioAndUsesAdaptiveMask() {
        val bitmap = Bitmap.createBitmap(100, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val source = BitmapDrawable(context.resources, bitmap)
        val normalized = AppCatalog.normalized(context, source) as BitmapDrawable
        val rendered = normalized.bitmap
        assertEquals(192, rendered.width)
        assertEquals(192, rendered.height)
        assertEquals(Color.RED, rendered.getPixel(96, 96))
        assertEquals(Color.TRANSPARENT, rendered.getPixel(0, 0))
        assertEquals(Rect(), source.bounds)
    }
}
