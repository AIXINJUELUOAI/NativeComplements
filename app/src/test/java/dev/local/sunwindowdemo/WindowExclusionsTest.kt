package dev.local.sunwindowdemo

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowBinder
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config as RoboConfig

@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [35])
class WindowExclusionsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun reset() {
        Config.hostPreferences = null
        ShadowBinder.setCallingUid(context.applicationInfo.uid)
        context.getSharedPreferences("corner", 0).edit().clear().commit()
    }

    @After fun releaseHostSnapshot() {
        Config.hostPreferences = null
        ShadowBinder.setCallingUid(android.os.Process.myUid())
    }

    @Test fun defaultsEmptyAndPersistsOnlyValidPackagesWithoutChangingGestureBlockList() {
        val store = WindowPreferenceStore(context)
        assertTrue(WindowPreferences().windowExcludedPackages.isEmpty())
        assertTrue(store.read().windowExcludedPackages.isEmpty())
        store.blocked(setOf("example.foreground"))
        store.windowExcludedPackages(setOf("com.android.camera", "example.target", "", "bad/package", "single"))
        assertEquals(setOf("com.android.camera", "example.target"), WindowPreferenceStore(context).read().windowExcludedPackages)
        assertEquals(setOf("example.foreground"), store.read().blocked)
        // Snapshot reads also sanitize stale/malformed stored values without inventing defaults.
        store.preferences.edit().putStringSet("windowExcludedPackages", setOf("example.target", "bad/package")).commit()
        assertEquals(setOf("example.target"), Config(context).windowExcludedPackages())
        store.windowExcludedPackages(emptySet())
        assertTrue(WindowPreferenceStore(context).read().windowExcludedPackages.isEmpty())
    }

    @Test fun listTravelsThroughProviderSnapshotAndEveryCommandSender() {
        val selected = setOf("com.android.camera", "example.target")
        val store = WindowPreferenceStore(context)
        store.windowExcludedPackages(selected)
        val snapshot = Robolectric.buildContentProvider(ConfigProvider::class.java).create().get()
            .call("read", null, null)
        assertNotNull(snapshot)
        assertEquals(selected, snapshot!!.getStringArrayList("windowExcludedPackages")!!.toSet())
        val hostPrefs = context.getSharedPreferences("window-exclusions-host-test", 0)
        ConfigProvider.copy(snapshot, hostPrefs)
        Config.hostPreferences = hostPrefs
        val host = object : ContextWrapper(context) {
            override fun getPackageName() = "com.android.systemui"
        }
        for (sender in listOf(context, host)) {
            Wire.openApp(sender, "example.target", false)
            val request = shadowOf(context.applicationContext as Application).broadcastIntents.last()
            assertEquals(selected, request.getStringArrayListExtra("windowExcludedPackages")!!.toSet())
        }
        // A fresh/older empty snapshot must clear exclusions rather than keep a stale host list.
        ConfigProvider.copy(Bundle(), hostPrefs)
        Wire.openApp(host, "example.target", false)
        val request = shadowOf(context.applicationContext as Application).broadcastIntents.last()
        assertTrue(request.getStringArrayListExtra("windowExcludedPackages")!!.isEmpty())
    }
}
