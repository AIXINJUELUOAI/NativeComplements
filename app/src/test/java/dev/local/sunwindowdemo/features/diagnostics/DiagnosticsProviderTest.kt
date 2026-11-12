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
    @Test fun sourceSpoofRawValuesBundlesAndManagementAreRejected() {
        DiagnosticsStore.start()
        rejected { provider.call("events", "system:hook_installed:\n", null) }
        rejected { provider.call("events", "app:hook_failed:secret phone number\n", null) }
        rejected { provider.call("events", "app:arbitrary content:\n", null) }
        rejected { provider.call("events", "app:enabled:\n".repeat(33), null) }
        rejected { provider.call("events", "app:enabled:\n", Bundle()) }
        rejected { provider.call("start", null, null) }
        assertEquals(0, DiagnosticsStore.state().getInt("bytes"))
    }
    @Test fun batchValidationIsAtomicAndStoppedSessionsIgnoreEvents() {
        DiagnosticsStore.start()
        rejected { provider.call("events", "app:enabled:\napp:raw secret:\n", null) }
        assertEquals(0, DiagnosticsStore.state().getInt("bytes"))
        provider.call("events", "app:enabled:\napp:hook_failed:SecurityException\n", null)
        val bytes = DiagnosticsStore.state().getInt("bytes")
        assertTrue(bytes > 0)
        DiagnosticsStore.stop()
        provider.call("events", "app:disabled:\n", null)
        assertEquals(bytes, DiagnosticsStore.state().getInt("bytes"))
    }
    @Test fun settingSnapshotRejectsArbitraryPreferenceKeys() {
        DiagnosticsStore.setting("barrage", true)
        rejected { DiagnosticsStore.setting("clipboard", true) }
        rejected { DiagnosticsStore.setting("phone_number", false) }
    }
}
