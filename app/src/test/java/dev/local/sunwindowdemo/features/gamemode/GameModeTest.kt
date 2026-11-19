package dev.local.sunwindowdemo

import dev.local.sunwindowdemo.features.gamemode.GameModePolicy
import dev.local.sunwindowdemo.features.gamemode.GameModeProvider
import dev.local.sunwindowdemo.features.gamemode.GameModeStore

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
class GameModeTest {
    private lateinit var provider: GameModeProvider

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        provider = GameModeProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = GameModeStore.AUTHORITY })
        ShadowBinder.setCallingUid(context.applicationInfo.uid)
        GameModeStore.prefs(context).edit().clear().commit()
    }

    @After fun cleanup() { ShadowBinder.setCallingUid(Process.myUid()) }

    private fun read(): Bundle = requireNotNull(provider.call("read", null, null))

    @Test fun unknownUidCannotRead() {
        ShadowBinder.setCallingUid(54321)
        try { read(); fail("must reject UID") } catch (_: SecurityException) {}
    }

    @Test fun selectionAndSideRoundTrip() {
        val context = RuntimeEnvironment.getApplication()
        GameModeStore.enabled(context, true)
        GameModeStore.side(context, GameModeStore.SIDE_RIGHT)
        GameModeStore.brightness(context, true)
        GameModeStore.select(context, "example.game", true)
        GameModeStore.quick(context, "screenshot", false)
        val snapshot = read()
        assertTrue(snapshot.getBoolean("enabled"))
        assertEquals(GameModeStore.SIDE_RIGHT, snapshot.getString("side"))
        assertTrue(snapshot.getBoolean("brightness"))
        assertTrue(snapshot.getStringArrayList("apps")!!.contains("example.game"))
        assertFalse(snapshot.getStringArrayList("quick")!!.contains("screenshot"))
    }

    @Test fun unknownSideFallsBackToLeft() {
        assertEquals(GameModeStore.SIDE_LEFT, GameModeStore.side("middle"))
    }

    @Test fun gamingRequiresFullscreenForeground() {
        assertTrue(GameModePolicy.gaming(true, true, true, true, false))
        assertFalse(GameModePolicy.gaming(true, true, false, true, false))
        assertFalse(GameModePolicy.gaming(true, false, true, true, false))
        assertFalse(GameModePolicy.gaming(true, true, true, false, false))
        assertFalse(GameModePolicy.gaming(true, true, true, true, true))
    }

    @Test fun retiredBrightnessOnlyRestoresLegacyLeaseAndNeverAcquiresAnother() {
        val context = RuntimeEnvironment.getApplication()
        val power = context.getSystemService(android.os.PowerManager::class.java)
        org.robolectric.Shadows.shadowOf(power).setIsInteractive(true)
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(false)
        val resolver = context.contentResolver
        val key = android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE
        android.provider.Settings.System.putInt(resolver, key, 0)
        context.getSharedPreferences("game_mode_host", 0).edit().putBoolean("brightness_hold", true).commit()
        val host = dev.local.sunwindowdemo.GameModeHost(context, {}, {})
        val config = Bundle().apply {
            putBoolean("enabled", true); putBoolean("panel", false); putBoolean("brightness", true)
            putStringArrayList("apps", arrayListOf("example.game"))
        }
        try {
            val worker = org.robolectric.util.ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(host, "worker")
            worker.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            org.robolectric.util.ReflectionHelpers.setField(host, "config", config)
            repeat(2) { host.environment("example.game", true, false) }
            assertEquals(1, android.provider.Settings.System.getInt(resolver, key))
            assertFalse(context.getSharedPreferences("game_mode_host", 0).getBoolean("brightness_hold", true))
            android.provider.Settings.System.putInt(resolver, key, 0)
            host.environment("", false, false)
            host.environment("example.game", true, false)
            assertEquals(0, android.provider.Settings.System.getInt(resolver, key))
        } finally { host.close() }
        assertEquals(0, android.provider.Settings.System.getInt(resolver, key))
    }

    @Test fun staleAsyncGameSettingsCannotReauthorizeCornerAfterNewerReload() {
        val context = RuntimeEnvironment.getApplication()
        val host = dev.local.sunwindowdemo.GameModeHost(context, {}, {})
        try {
            org.robolectric.util.ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(host, "worker")
                .submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
            org.robolectric.util.ReflectionHelpers.setField(host, "loadRevision", 2)
            // Revision 1 has posted its result, but a newer reload has already revoked authorization.
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(org.robolectric.util.ReflectionHelpers.getField<Boolean>(host, "cornerConfigLive"))
        } finally { host.close() }
    }

    @Test fun cornerNeedsLiveConfigAndActualForegroundButNotPanel() {
        val context = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.os.PowerManager::class.java)).setIsInteractive(true)
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(false)
        val host = dev.local.sunwindowdemo.GameModeHost(context, {}, {})
        try {
            org.robolectric.util.ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(host, "worker")
                .submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val config = Bundle().apply {
                putBoolean("enabled", true); putBoolean("panel", false); putBoolean("corner_rejection", true)
                putInt("corner_radius_percent", 10); putStringArrayList("apps", arrayListOf("example.game"))
            }
            org.robolectric.util.ReflectionHelpers.setField(host, "config", config)
            org.robolectric.util.ReflectionHelpers.setField(host, "cornerConfigLive", true)
            host.environment("example.game", true, false, "example.game")
            assertTrue(host.blocksCorner(1f, 1f))
            config.putBoolean("panel", true); config.putInt("corner_radius_percent", 24)
            host.environment("example.game", true, false, "example.game")
            val handle = host.params()
            assertFalse(host.blocksCorner(1f, handle.y + 1f))
            val exclusions = org.robolectric.util.ReflectionHelpers.getField<IntArray>(host, "cornerExclusions")
            assertTrue(exclusions.size >= 4)
            config.putBoolean("panel", false)
            host.environment("example.game", true, false, "example.other")
            assertFalse(host.blocksCorner(1f, 1f))
            host.environment("example.game", true, true, "example.game")
            assertFalse(host.blocksCorner(1f, 1f))
            host.environment("example.game", false, false, "example.game")
            assertFalse(host.blocksCorner(1f, 1f))
            org.robolectric.util.ReflectionHelpers.setField(host, "cornerConfigLive", false)
            host.environment("example.game", true, false, "example.game")
            assertFalse(host.blocksCorner(1f, 1f))
        } finally { host.close() }
    }

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun panelReusesMenuMaterialAndShowsAllTwelveFunctionsInScrollableGrid() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try {
            val context = activity.get()
            val catalog = listOf(AppCatalog.App("example.alpha", "应用甲", android.graphics.drawable.ColorDrawable(android.graphics.Color.BLUE)),
                AppCatalog.App("example.beta", "应用乙", android.graphics.drawable.ColorDrawable(android.graphics.Color.RED)))
            var launched = ""
            val panel = gameModePanel(context, catalog.take(1), catalog, QuickActionCatalog.ACTIONS,
                392, 360, false, { launched = it }, {}) as android.widget.LinearLayout
            context.setContentView(panel)
            panel.measure(android.view.View.MeasureSpec.makeMeasureSpec(392, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(360, android.view.View.MeasureSpec.EXACTLY))
            panel.layout(0, 0, 392, panel.measuredHeight)
            panel.measure(android.view.View.MeasureSpec.makeMeasureSpec(392, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(360, android.view.View.MeasureSpec.EXACTLY))
            panel.layout(0, 0, 392, 360)
            assertNull(panel.background)
            val rail = panel.getChildAt(0) as android.widget.LinearLayout
            val content = panel.getChildAt(1) as android.widget.FrameLayout
            assertTrue(rail.background is android.graphics.drawable.GradientDrawable)
            assertTrue(content.background is android.graphics.drawable.GradientDrawable)
            val quickPage = content.getChildAt(0) as GameQuickView
            val bitmap = android.graphics.Bitmap.createBitmap(392, panel.measuredHeight, android.graphics.Bitmap.Config.ARGB_8888)
            panel.draw(android.graphics.Canvas(bitmap))
            val directory = java.io.File(System.getProperty("nativecomplements.screenshots"))
            directory.mkdirs()
            java.io.File(directory, "game-mode-panel.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val pins = (rail.getChildAt(0) as android.widget.ScrollView).getChildAt(0) as android.widget.LinearLayout
            pins.getChildAt(0).performClick()
            assertEquals("example.alpha", launched)
            assertTrue(((pins.getChildAt(0) as android.widget.FrameLayout).getChildAt(0) as android.widget.ImageView).clipToOutline)
            val arrowSlot = pins.getChildAt(pins.childCount - 1) as android.widget.FrameLayout
            assertEquals(pins.getChildAt(0).height, arrowSlot.height)
            val arrow = arrowSlot.getChildAt(0) as android.widget.ImageButton
            val appIcon = (pins.getChildAt(0) as android.widget.FrameLayout).getChildAt(0)
            assertEquals(appIcon.width, arrow.width)
            assertEquals(appIcon.height, arrow.height)
            assertEquals(0f, arrow.rotation, 0f)
            assertEquals(arrow.width, arrow.height)
            val circle = (arrow.background as android.graphics.drawable.RippleDrawable).getDrawable(0) as android.graphics.drawable.GradientDrawable
            assertEquals(android.graphics.drawable.GradientDrawable.OVAL, circle.shape)
            arrow.performClick()
            assertEquals(180f, arrow.rotation, 0f)
            assertEquals(android.view.View.GONE, quickPage.visibility)
            assertFalse(quickPage.sliders.observing)
            val library = content.getChildAt(1) as android.widget.LinearLayout
            assertEquals(android.view.View.VISIBLE, library.visibility)
            assertEquals(1, library.childCount) // body only: no title/manage/recommendations
            panel.measure(android.view.View.MeasureSpec.makeMeasureSpec(392, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(360, android.view.View.MeasureSpec.EXACTLY))
            panel.layout(0, 0, 392, 360)
            assertEquals(360, content.height)
            panel.draw(android.graphics.Canvas(bitmap))
            java.io.File(directory, "game-mode-library.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            arrow.performClick()
            assertEquals(0f, arrow.rotation, 0f)
            assertEquals(android.view.View.VISIBLE, quickPage.visibility)
            assertTrue(quickPage.sliders.observing)
            assertSame(quickPage, content.getChildAt(0))
            quickPage.suspendControls()
            assertFalse(quickPage.sliders.observing)
            arrow.performClick(); arrow.performClick()
            assertFalse(quickPage.sliders.observing) // exiting card must not resume during its slide-out
        } finally { activity.close() }
    }

    @Test fun sixPinnedAppsAndArrowShareSevenEqualSlots() {
        val context = RuntimeEnvironment.getApplication()
        val apps = (1..6).map { AppCatalog.App("example.$it", "应用$it", android.graphics.drawable.ColorDrawable(android.graphics.Color.BLUE)) }
        val panel = gameModePanel(context, apps, apps, QuickActionCatalog.ACTIONS, 392, 400, false, {}, {}) as android.widget.LinearLayout
        repeat(3) {
            panel.measure(android.view.View.MeasureSpec.makeMeasureSpec(392, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY))
            panel.layout(0, 0, 392, 400)
        }
        val rail = panel.getChildAt(0) as android.widget.LinearLayout
        val scroll = rail.getChildAt(0) as android.widget.ScrollView
        val column = scroll.getChildAt(0) as android.widget.LinearLayout
        assertEquals(7, column.childCount)
        repeat(7) { assertEquals(column.getChildAt(0).height, column.getChildAt(it).height) }
        val remainder = scroll.height - column.getChildAt(0).height * 7
        assertTrue("viewport=${scroll.height}, slot=${column.getChildAt(0).height}, remainder=$remainder", remainder in 0..6)
    }

    @Test fun collapsedWindowUsesPhysicalEdgeAndCutoutAlways() {
        val context = RuntimeEnvironment.getApplication()
        val host = dev.local.sunwindowdemo.GameModeHost(context, {}, {})
        try {
            val params = host.params()
            assertEquals(0, params.x)
            assertEquals(android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS, params.layoutInDisplayCutoutMode)
            assertEquals(0, params.fitInsetsTypes)
        } finally { host.close() }
    }

    @Test @Config(qualifiers = "w900dp-h400dp-land-mdpi")
    fun expandedCardMatchesLandscapeMenuPixelsAndUsesFixedSizeForBothPages() {
        val context = RuntimeEnvironment.getApplication()
        val host = GameModeHost(context, {}, {})
        try {
            val card = host.expandedBounds()
            val metrics = context.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics
            val placement = AppList.placement(context, metrics)
            assertEquals(Math.round(placement.height() * placement.scale()), card.height())
            val density = context.resources.displayMetrics.density
            val rail = (64 * density).toInt()
            val gap = (10 * density).toInt()
            assertEquals(Math.round(placement.width() * placement.scale() * 1.2f), card.width() - rail - gap)
            val panel = gameModePanel(context, emptyList(), emptyList(), QuickActionCatalog.ACTIONS,
                card.width(), card.height(), false, {}, {}) as android.widget.LinearLayout
            panel.measure(android.view.View.MeasureSpec.makeMeasureSpec(card.width(), android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(card.height(), android.view.View.MeasureSpec.EXACTLY))
            panel.layout(0, 0, card.width(), card.height())
            assertEquals(Math.round(placement.width() * placement.scale() * 1.2f), panel.getChildAt(1).width)
            assertEquals(Math.round(placement.height() * placement.scale()), panel.getChildAt(1).height)
            org.robolectric.util.ReflectionHelpers.setField(host, "expanded", true)
            val params = host.params()
            assertEquals(card.width(), params.width)
            assertEquals(card.height(), params.height)
            assertTrue(metrics.bounds.contains(card))
        } finally { host.close() }
    }

    @Test @Config(qualifiers = "w400dp-h900dp-port-mdpi")
    fun portraitCardUsesLandscapeShortHeightAndCentersInsideSafeArea() {
        val context = RuntimeEnvironment.getApplication()
        val host = GameModeHost(context, {}, {})
        try {
            val metrics = context.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics
            val bounds = metrics.bounds
            val safe = metrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.displayCutout() or android.view.WindowInsets.Type.systemBars())
            val card = host.expandedBounds()
            val expected = minOf(bounds.width() - 8, bounds.height() - safe.top - safe.bottom - 24)
            assertEquals(expected, card.height())
            assertTrue(card.height() < minOf(bounds.width(), bounds.height()))
            assertEquals(safe.top + (bounds.height() - safe.top - safe.bottom - card.height()) / 2, card.top)
            assertTrue(card.top >= safe.top)
            assertTrue(card.bottom <= bounds.height() - safe.bottom)
            // With no bars or cutouts the corresponding 900x400 landscape menu has 4dp margins.
            val landscape = Geometry.place(400, false, 0, 0, 900, 400, 4)
            assertEquals(Math.round(landscape.height() * landscape.scale()), card.height())
            org.robolectric.util.ReflectionHelpers.setField(host, "expanded", true)
            assertEquals(card.height(), host.params().height)
            assertEquals(card.top, host.params().y)
        } finally { host.close() }
    }

    @Test fun handleStaysOnEdgeAndMovesBelowCutout() {
        assertEquals(28, GameModePolicy.handleTop(1000, 500, false, 28, 14, 64, emptyList()))
        assertEquals(120, GameModePolicy.handleTop(1000, 500, false, 28, 14, 64, listOf(android.graphics.Rect(0, 10, 80, 120))))
        assertEquals(28, GameModePolicy.handleTop(1000, 500, true, 28, 14, 64, listOf(android.graphics.Rect(0, 10, 80, 120))))
    }
}
