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

    @Test fun delegatedEnvironmentIsReadOnlyBoundedAndExpiresClosed() {
        val constructor = SystemUiHost::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        val host = constructor.newInstance(context)
        var foreground: String? = null
        var blocked = false
        var edgeBlocked = false
        var protectedRects = intArrayOf()
        val extras = java.lang.reflect.Proxy.newProxyInstance(HostExtras::class.java.classLoader, arrayOf(HostExtras::class.java)) { _, method, args ->
            if (method.name == "environment") { foreground = args[0] as String; blocked = args[2] as Boolean }
            if (method.name == "edgeEnvironment") edgeBlocked = args[0] as Boolean
            if (method.name == "protectedRects") protectedRects = (args[0] as IntArray).clone()
            if (method.returnType == Boolean::class.javaPrimitiveType) false else null
        }
        fun field(name: String, value: Any) { SystemUiHost::class.java.getDeclaredField(name).apply { isAccessible = true }.set(host, value) }
        field("extras", extras)
        field("wm", context.getSystemService(android.view.WindowManager::class.java))
        field("delegatedToStandalone", true)
        shadowOf(context.getSystemService(android.os.PowerManager::class.java)).setIsInteractive(true)
        shadowOf(context.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(false)
        val update = SystemUiHost::class.java.getDeclaredMethod("updateCompanionEnvironment").apply { isAccessible = true }
        val key = "dev.nativecomplements.window.companion.environment.v1"
        val facts = android.content.Intent().putExtra("environmentAt", android.os.SystemClock.elapsedRealtime())
            .putExtra("environmentUser", Process.myUid() / 100000).putExtra("environmentDisplay", 0)
            .putExtra("foreground", "example.native").putExtra("fullscreenApp", "example.native")
            .putExtra("systemOverlay", false).putExtra("systemOverlayTitle", "").putExtra("imeVisible", false)
            .putExtra("imeTop", Int.MAX_VALUE)
        field("companionFacts", facts)
        val snapshot = org.json.JSONObject().put("at", android.os.SystemClock.elapsedRealtime())
            .put("foreground", "example.owner").put("fullscreenApp", "example.owner").put("title", "").put("system", false).put("keyboard", false)
            .put("screenshot", false).put("panel", false).put("waiting", false).put("imeTop", Int.MAX_VALUE)
            .put("rects", org.json.JSONArray(listOf(0, 0, 1, 1)))
        try {
            val encoded = snapshot.toString()
            System.setProperty(key, encoded); update.invoke(host)
            assertEquals("example.native", foreground); assertFalse(blocked); assertFalse(edgeBlocked)
            assertArrayEquals(intArrayOf(0, 0, 1, 1), protectedRects)
            assertEquals(encoded, System.getProperty(key))
            snapshot.put("at", android.os.SystemClock.elapsedRealtime() - 2501)
            System.setProperty(key, snapshot.toString()); update.invoke(host)
            assertEquals("example.native", foreground); assertFalse(blocked); assertTrue(edgeBlocked)
            assertArrayEquals(intArrayOf(), protectedRects)
            System.setProperty(key, "x".repeat(8193)); update.invoke(host)
            assertFalse(blocked); assertTrue(edgeBlocked)
            assertArrayEquals(intArrayOf(), protectedRects)
            assertEquals("x".repeat(8193), System.getProperty(key))
            facts.putExtra("environmentAt", android.os.SystemClock.elapsedRealtime() - 2501)
            update.invoke(host)
            assertEquals("", foreground); assertTrue(blocked)
            assertFalse(SystemUiHost::class.java.getDeclaredField("backendReady").apply { isAccessible = true }.getBoolean(host))
        } finally { System.clearProperty(key) }
    }

    @Test fun enabledPreferenceProjectsOnlyOwnStartupEligibility() {
        val store = WindowPreferenceStore(context)
        val marker = android.content.ComponentName(context.packageName, "dev.local.sunwindowdemo.core.WindowBackendEligibility")
        store.boolean("enabled", false)
        assertFalse(store.read().enabled)
        assertEquals(android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, context.packageManager.getComponentEnabledSetting(marker))
        store.enable()
        assertTrue(store.read().enabled)
        assertEquals(android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, context.packageManager.getComponentEnabledSetting(marker))
        store.boolean("enabled", false)
        assertEquals(android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, context.packageManager.getComponentEnabledSetting(marker))
    }

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
        assertThrows(IllegalArgumentException::class.java) { store.boolean("enabled", true) }
        // The controller owns explicit confirmation; persistence does not fake a verified capability.
        assertFalse(WindowPreferenceStore(context).read().enabled)
        store.enable()
        assertTrue(WindowPreferenceStore(context).read().enabled)
        val enabledSignal = shadowOf(context.applicationContext as android.app.Application).broadcastIntents.last()
        assertEquals(ConfigProvider.CHANGED, enabledSignal.action)
        assertEquals("com.android.systemui", enabledSignal.`package`)
        store.boolean("doubleClose", true)
        assertTrue(WindowPreferenceStore(context).read().doubleClose)
        store.boolean("doubleClose", false)
        assertFalse(WindowPreferenceStore(context).read().doubleClose)
    }

    @Test fun autoMinimizeDefaultsOnAndPersistsThroughProviderSnapshotAndHostHeartbeat() {
        val store = WindowPreferenceStore(context)
        store.preferences.edit().remove("autoMinimizeOnOpen").commit()
        assertTrue(store.read().autoMinimizeOnOpen)
        val provider = Robolectric.buildContentProvider(ConfigProvider::class.java).create().get()
        val destination = context.getSharedPreferences("auto-mini-host-test", 0)
        val host = object : android.content.ContextWrapper(context) {
            override fun getPackageName() = "com.android.systemui"
        }
        try {
            for (enabled in listOf(false, true)) {
                store.boolean("autoMinimizeOnOpen", enabled)
                assertEquals(enabled, WindowPreferenceStore(context).read().autoMinimizeOnOpen)
                val snapshot = requireNotNull(provider.call("read", null, null))
                assertEquals(enabled, snapshot.getBoolean("autoMinimizeOnOpen", !enabled))
                ConfigProvider.copy(snapshot, destination)
                dev.local.sunwindowdemo.Config.hostPreferences = destination
                assertEquals(enabled, dev.local.sunwindowdemo.Config(host).autoMinimizeOnOpen())
                for (op in listOf("STATUS", "OPEN")) {
                    if (op == "OPEN") Wire.openApp(host, "example.app", false) else Wire.send(host, op)
                    val request = shadowOf(context.applicationContext as android.app.Application).broadcastIntents.last()
                    assertEquals(enabled, request.getBooleanExtra("autoMinimizeOnOpen", !enabled))
                }
                dev.local.sunwindowdemo.Config.hostPreferences = null
            }
            ConfigProvider.copy(Bundle(), destination)
            dev.local.sunwindowdemo.Config.hostPreferences = destination
            assertTrue(dev.local.sunwindowdemo.Config(host).autoMinimizeOnOpen())
        } finally { dev.local.sunwindowdemo.Config.hostPreferences = null }
    }

    @Test fun windowDimPersistsClampsAndTravelsThroughTheHostSnapshotAndCommand() {
        val store = WindowPreferenceStore(context)
        store.preferences.edit().remove("windowDim").commit()
        assertEquals(20, store.read().windowDim)
        store.number("windowDim", 35)
        assertEquals(35, WindowPreferenceStore(context).read().windowDim)
        val destination = context.getSharedPreferences("dim-host-test", 0)
        ConfigProvider.copy(Bundle().apply { putInt("windowDim", store.read().windowDim) }, destination)
        assertEquals(35, destination.getInt("windowDim", -1))
        val host = object : android.content.ContextWrapper(context) {
            override fun getPackageName() = "com.android.systemui"
        }
        Wire.openApp(host, "example.app", false)
        val request = shadowOf(context.applicationContext as android.app.Application).broadcastIntents.last()
        assertEquals(35, request.getIntExtra("windowDim", -1))
        store.number("windowDim", -1)
        assertEquals(0, store.read().windowDim)
        store.number("windowDim", 101)
        assertEquals(100, store.read().windowDim)
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
        assertEquals(5, WindowPreferenceStore(context).read().fanCount)
        assertEquals(targets, WindowPreferenceStore(context).read().apps)
    }

    @Test fun oldShortcutSelectionsArePrunedWithoutChangingAppOrder() {
        val store = WindowPreferenceStore(context)
        val original = listOf("com.example.first", "com.example.second")
        store.apps(original)
        val oldShortcut = "shortcut:v1:0:com.example.first:scan"
        assertFalse(ShortcutTarget.validKey(oldShortcut))
        store.preferences.edit().putString("targets", org.json.JSONArray(listOf(original[0], oldShortcut, original[1])).toString()).commit()
        assertEquals(original, store.read().apps)
        assertEquals(org.json.JSONArray(original).toString(), store.preferences.getString("targets", ""))
        assertThrows(IllegalArgumentException::class.java) { dev.local.sunwindowdemo.Config.validateTargets(listOf(oldShortcut), listOf("Scan")) }
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
