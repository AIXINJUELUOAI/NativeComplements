package dev.local.sunwindowdemo.home

import org.junit.Assert.*
import org.junit.Test

class HostRestartActionsTest {
    @Test fun commandsAreRestrictedToExactHostsAndResolvedUid() {
        val script = hostRestartScript("com.android.systemui", 101000)
        assertTrue(script.contains("pidof 'com.android.systemui'"))
        val uidCheck = script.indexOf("[ \"\$actual\" = '101000' ] || continue")
        assertTrue(uidCheck >= 0 && uidCheck < script.indexOf("kill -9"))
        assertFalse(script.contains("force-stop"))
        for (pkg in listOf("android", "system_server", "com.android.systemui; reboot", "third.party.launcher")) {
            assertThrows(IllegalArgumentException::class.java) { hostRestartScript(pkg, 1000) }
        }
        assertThrows(IllegalArgumentException::class.java) { hostRestartScript("com.android.systemui", -1) }
    }
}
