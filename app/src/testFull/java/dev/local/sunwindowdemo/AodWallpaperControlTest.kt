package dev.local.sunwindowdemo

import dev.local.sunwindowdemo.navigation.AodWallpaperControl
import org.junit.Assert.*
import org.junit.Test

class AodWallpaperControlTest {
    @Test fun rootReadbackAndCommandsNeverAssumeSuccess() {
        assertTrue(AodWallpaperControl.parse("root banner\nNC_CAP=true\nNC_SETTING=1\n").enabled)
        assertFalse(AodWallpaperControl.parse("NC_CAP=false\nNC_SETTING=1\n").enabled)
        assertTrue(AodWallpaperControl.parse("NC_CAP=true\nNC_SETTING=null\n").enabled)
        val partial = AodWallpaperControl.parse("NC_CAP=false\nNC_SETTING=1\n")
        assertTrue(partial.wallpaper)
        assertFalse(partial.enabled)
        assertFalse(AodWallpaperControl.parse("NC_CAP=true\nNC_SETTING=0\n").wallpaper)
        for (output in listOf("permission denied", "NC_CAP=true\nNC_SETTING=garbage",
            "NC_CAP=true\nNC_CAP=false\nNC_SETTING=1")) {
            assertThrows(IllegalArgumentException::class.java) { AodWallpaperControl.parse(output) }
        }
        val enable = AodWallpaperControl.changeScript(10, true)
        assertTrue(enable.contains("0x12 0x1 &&"))
        assertTrue(enable.contains("cmd overlay enable --user 10 com.android.shell:dev.nativecomplements.aodwallpaper &&"))
        assertTrue(enable.contains("settings --user 10 put secure doze_always_on_wallpaper_enabled 1"))
        val disable = AodWallpaperControl.changeScript(10, false)
        assertTrue(disable.contains("cmd overlay disable --user 10"))
        assertTrue(disable.contains("settings --user 10 put secure doze_always_on_wallpaper_enabled 0"))
        assertFalse(disable.contains("settings delete"))
        assertFalse(enable.contains("put secure doze_always_on "))
    }
}
