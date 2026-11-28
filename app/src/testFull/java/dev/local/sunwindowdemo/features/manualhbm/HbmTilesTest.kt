package dev.local.sunwindowdemo.features.manualhbm

import android.content.ComponentName
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HbmTilesTest {
    @Test fun matchesOnlyThisTileInEitherNativeComponentFormat() {
        val tile = ComponentName("dev.nativecomplements.module", "dev.local.sunwindowdemo.features.manualhbm.HbmTileService")
        assertTrue(HbmTiles.listed("wifi,custom(${tile.flattenToString()})\n", tile))
        val short = ComponentName("dev.nativecomplements.module", "dev.nativecomplements.module.HbmTile")
        assertTrue(HbmTiles.listed("custom(dev.nativecomplements.module/.HbmTile)", short))
        assertFalse(HbmTiles.listed("wifi,custom(other/.HbmTile),bt", tile))
    }
}
