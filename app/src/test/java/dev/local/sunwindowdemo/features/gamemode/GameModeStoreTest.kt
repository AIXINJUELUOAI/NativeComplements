package dev.local.sunwindowdemo.features.gamemode

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GameModeStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clear() { GameModeStore.prefs(context).edit().clear().commit() }

    @Test fun cornerOptInAndClampedSizeAreIndependentOfPanel() {
        assertFalse(GameModeStore.read(context).getBoolean("corner_rejection"))
        assertFalse(GameModeStore.read(context).getBoolean("corner_show_regions"))
        GameModeStore.showCornerRegions(context, true)
        assertTrue(GameModeStore.read(context).getBoolean("corner_show_regions"))
        assertFalse(GameModeStore.read(context).getBoolean("enabled"))
        val bounds = context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        val shortDp = minOf(bounds.width(), bounds.height()) / context.resources.displayMetrics.density
        fun currentDp() = GameModeStore.read(context).getFloat("corner_radius_percent") * shortDp / 100f
        assertEquals((shortDp * .1f).coerceIn(50f, 200f), currentDp(), .0001f)
        GameModeStore.prefs(context).edit().putInt("corner_radius_percent", 13).commit()
        assertEquals((shortDp * .13f).coerceIn(50f, 200f), currentDp(), .0001f)
        GameModeStore.cornerRadius(context, 123.375f / shortDp * 100f)
        assertEquals(123.375f, currentDp(), .0001f)
        GameModeStore.cornerRejection(context, true)
        GameModeStore.panel(context, false)
        assertTrue(GameModeStore.read(context).getBoolean("corner_rejection"))
        assertFalse(GameModeStore.read(context).getBoolean("enabled"))
        GameModeStore.cornerRadius(context, 100f)
        assertEquals(200f, currentDp(), .0001f)
        GameModeStore.cornerRadius(context, -1f)
        assertEquals(50f, currentDp(), .0001f)
        GameModeStore.cornerRadius(context, Float.NaN)
        assertEquals((shortDp * .1f).coerceIn(50f, 200f), currentDp(), .0001f)
    }

    @Test fun legacyGamesStayGamesAndQuickAppsRemainIndependent() {
        val prefs = GameModeStore.prefs(context)
        prefs.edit().putStringSet("games", setOf("example.legacy")).commit()
        assertEquals(listOf("example.legacy"), GameModeStore.read(context).getStringArrayList("apps"))
        assertTrue(GameModeStore.read(context).getStringArrayList("quick_apps")!!.isEmpty())
        GameModeStore.quickApp(context, "example.chat", true)
        GameModeStore.select(context, "example.game", true)
        assertEquals(setOf("example.legacy", "example.game"), GameModeStore.read(context).getStringArrayList("apps")!!.toSet())
        assertEquals(listOf("example.chat"), GameModeStore.read(context).getStringArrayList("quick_apps"))
        GameModeStore.select(context, "example.legacy", false)
        GameModeStore.select(context, "example.game", false)
        assertTrue(GameModeStore.read(context).getStringArrayList("apps")!!.isEmpty())
        assertEquals(listOf("example.chat"), GameModeStore.read(context).getStringArrayList("quick_apps"))
    }

    @Test fun quickListsRetainOrderAndRejectInvalidInputWithoutChangingGames() {
        GameModeStore.select(context, "example.game", true)
        GameModeStore.quickApp(context, "example.alpha", true)
        GameModeStore.quickApp(context, "example.beta", true)
        GameModeStore.quickApp(context, "example.alpha", true)
        GameModeStore.moveQuickApp(context, "example.beta", -1)
        assertEquals(listOf("example.beta", "example.alpha"), GameModeStore.read(context).getStringArrayList("quick_apps"))
        GameModeStore.moveQuickApp(context, "example.beta", -1)
        GameModeStore.moveQuickApp(context, "example.beta", 5)
        assertEquals(listOf("example.beta", "example.alpha"), GameModeStore.read(context).getStringArrayList("quick_apps"))
        GameModeStore.prefs(context).edit().putString("quick", "mute,screenshot").commit()
        GameModeStore.moveQuick(context, "screenshot", -1)
        assertEquals(listOf("screenshot", "mute"), GameModeStore.read(context).getStringArrayList("quick"))
        assertThrows(IllegalArgumentException::class.java) { GameModeStore.quickApp(context, "bad", true) }
        GameModeStore.quickApp(context, "example.beta", false)
        assertEquals(listOf("example.alpha"), GameModeStore.read(context).getStringArrayList("quick_apps"))
        assertEquals(listOf("example.game"), GameModeStore.read(context).getStringArrayList("apps"))
    }
}
