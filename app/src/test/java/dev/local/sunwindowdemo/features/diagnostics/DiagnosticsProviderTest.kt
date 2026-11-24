package dev.local.sunwindowdemo.features.diagnostics

import android.content.pm.ProviderInfo
import android.os.Bundle
import android.os.Process
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsProviderTest {
    private lateinit var provider: DiagnosticsProvider
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        provider = DiagnosticsProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "diagnostics-test" })
        ShadowBinder.setCallingUid(context.applicationInfo.uid)
        DiagnosticsStore.clear()
    }
    @After fun cleanup() {
        DiagnosticsStore.clear()
        ShadowBinder.setCallingUid(Process.myUid())
    }
    private fun rejected(action: () -> Unit) {
        try { action(); fail("must reject") } catch (_: IllegalArgumentException) { }
    }
    @Test fun unknownUidCannotReadOrWrite() {
        ShadowBinder.setCallingUid(54321)
        try { provider.call("state", null, null); fail("must reject UID") }
        catch (_: SecurityException) { }
    }
    @Test fun onlyActualSystemHomeUidCanReportLauncherEvents() {
        val context = RuntimeEnvironment.getApplication()
        val pm = org.robolectric.Shadows.shadowOf(context.packageManager)
        val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        fun installHome(name: String, uid: Int) {
            pm.installPackage(android.content.pm.PackageInfo().apply {
                packageName = name
                applicationInfo = android.content.pm.ApplicationInfo().apply {
                    packageName = name; this.uid = uid
                    flags = android.content.pm.ApplicationInfo.FLAG_SYSTEM
                }
            })
            pm.addResolveInfoForIntent(homeIntent, android.content.pm.ResolveInfo().apply {
                activityInfo = android.content.pm.ActivityInfo().apply {
                    packageName = name; this.name = "$name.Home"
                }
                isDefault = true
            })
        }
        installHome("com.android.launcher3", 54321)
        ShadowBinder.setCallingUid(54321)
        provider.call("state", null, null)
        // Installed/allowlisted but no longer actual HOME must not retain bridge access.
        pm.removeResolveInfosForIntent(homeIntent, "com.android.launcher3")
        installHome("sample.other.home", 54322)
        try { provider.call("state", null, null); fail("inactive HOME UID") }
        catch (_: SecurityException) { }
        ShadowBinder.setCallingUid(54322)
        try { provider.call("state", null, null); fail("third-party HOME UID") }
        catch (_: SecurityException) { }
    }
    @Test fun phoneEventsRequireSameUserRealSystemPackage() {
        val context = RuntimeEnvironment.getApplication()
        val pm = org.robolectric.Shadows.shadowOf(context.packageManager)
        fun installPhone(uid: Int, flags: Int) {
            pm.installPackage(android.content.pm.PackageInfo().apply {
                packageName = "com.android.phone"
                applicationInfo = android.content.pm.ApplicationInfo().apply {
                    packageName = "com.android.phone"; this.uid = uid; this.flags = flags
                }
            })
        }
        installPhone(54321, 0)
        ShadowBinder.setCallingUid(54321)
        assertThrows(SecurityException::class.java) { provider.call("state", null, null) }
        installPhone(54321, android.content.pm.ApplicationInfo.FLAG_SYSTEM)
        DiagnosticsStore.start()
        provider.call("events", "phone:sms_blocked:", null)
        assertTrue(DiagnosticsStore.state().getInt("eventBytes") > 0)
        installPhone(154321, android.content.pm.ApplicationInfo.FLAG_SYSTEM)
        ShadowBinder.setCallingUid(154321)
        assertThrows(SecurityException::class.java) { provider.call("state", null, null) }
    }
    @Test fun sourceSpoofRawValuesBundlesAndManagementAreRejected() {
        DiagnosticsStore.start()
        rejected { provider.call("events", "system:hook_installed:\n", null) }
        rejected { provider.call("events", "app:hook_failed:secret phone number\n", null) }
        rejected { provider.call("events", "app:arbitrary content:\n", null) }
        rejected { provider.call("events", "app:enabled:\n".repeat(33), null) }
        rejected { provider.call("events", "app:enabled:\n", Bundle()) }
        rejected { provider.call("start", null, null) }
        assertEquals(0, DiagnosticsStore.state().getInt("eventBytes"))
    }
    @Test fun batchValidationIsAtomicAndStoppedSessionsIgnoreEvents() {
        DiagnosticsStore.start()
        rejected { provider.call("events", "app:enabled:\napp:raw secret:\n", null) }
        assertEquals(0, DiagnosticsStore.state().getInt("eventBytes"))
        provider.call("events", "app:enabled:\napp:hook_failed:SecurityException\n", null)
        val bytes = DiagnosticsStore.state().getInt("eventBytes")
        assertTrue(bytes > 0)
        DiagnosticsStore.stop()
        provider.call("events", "app:disabled:\n", null)
        assertEquals(bytes, DiagnosticsStore.state().getInt("eventBytes"))
    }
    @Test fun settingSnapshotRejectsArbitraryPreferenceKeys() {
        DiagnosticsStore.setting("barrage", true)
        rejected { DiagnosticsStore.setting("clipboard", true) }
        rejected { DiagnosticsStore.setting("phone_number", false) }
    }
}
