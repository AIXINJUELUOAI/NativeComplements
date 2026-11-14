package dev.local.sunwindowdemo.features.navbar;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE)
public class NavbarLatencyTest {
    private static final String PAGE = "com.taobao.taobao/com.taobao.tao.TBMainActivity";

    @Test public void cacheKeySeparatesModeHeightThemeAndOrientation() {
        String key = NavbarColorCache.key(PAGE, NavbarStore.COLOR, 73, false, false);
        assertNotEquals(key, NavbarColorCache.key(PAGE, NavbarStore.COLOR, 73, true, false));
        assertNotEquals(key, NavbarColorCache.key(PAGE, NavbarStore.COLOR, 90, false, false));
        assertNotEquals(key, NavbarColorCache.key(PAGE, NavbarStore.COLOR, 73, false, true));
        assertNotEquals(key, NavbarColorCache.key(PAGE + "2", NavbarStore.COLOR, 73, false, false));
        assertEquals(key, NavbarColorCache.key(PAGE, NavbarStore.COLOR, 73, false, false));
        assertNull(NavbarColorCache.key(null, NavbarStore.COLOR, 73, false, false));
        assertNull(NavbarColorCache.key(PAGE, NavbarStore.COLOR, 0, false, false));
    }

    @Test public void cacheIsBoundedAndKeepsRecentlyUsedPages() {
        NavbarColorCache cache = new NavbarColorCache();
        String first = NavbarColorCache.key(PAGE, NavbarStore.COLOR, 73, false, false);
        cache.put(first, 0xffffffff);
        for (int index = 0; index < NavbarColorCache.CAPACITY - 1; index++)
            cache.put(NavbarColorCache.key("page/" + index, NavbarStore.COLOR, 73, false, false), 0xff000000 | index);
        assertEquals(Integer.valueOf(0xffffffff), cache.get(first));
        cache.put(NavbarColorCache.key("page/extra", NavbarStore.COLOR, 73, false, false), 0xff123456);
        assertEquals(NavbarColorCache.CAPACITY, cache.size());
        // The first page was read most recently, so the oldest untouched page was evicted instead.
        assertEquals(Integer.valueOf(0xffffffff), cache.get(first));
        assertNull(cache.get(NavbarColorCache.key("page/0", NavbarStore.COLOR, 73, false, false)));
    }

    @Test public void cacheRejectsMissingKeysAndTranslucentColors() {
        NavbarColorCache cache = new NavbarColorCache();
        cache.put(null, 0xffffffff);
        String key = NavbarColorCache.key(PAGE, NavbarStore.COLOR, 73, false, false);
        cache.put(key, 0x80ffffff);
        cache.put(key, 0);
        assertEquals(0, cache.size());
        assertNull(cache.get(null));
        cache.put(key, 0xff222222);
        cache.clear();
        assertNull(cache.get(key));
    }

    @Test public void seededPageColorNeedsConfirmationBeforeChanging() {
        NavbarColors.Stability stability = new NavbarColors.Stability();
        stability.seed(0xffffffff);
        assertEquals(0xffffffff, stability.applied());
        assertEquals(0xffffffff, stability.choose(0xfff8f8f8));
        assertFalse(stability.needsConfirmation());
        assertEquals(0xffffffff, stability.choose(0xff202020));
        assertTrue(stability.needsConfirmation());
        assertEquals(0xff202020, stability.choose(0xff202020));
        assertFalse(stability.needsConfirmation());
    }

    @Test public void firstSampleOfANewWindowAppliesWithoutConfirmation() {
        NavbarColors.Stability stability = new NavbarColors.Stability();
        stability.choose(0xffffffff);
        stability.reset();
        assertEquals(0xff202020, stability.choose(0xff202020));
        assertFalse(stability.needsConfirmation());
    }

    @Test public void switchHoldOnlyCoversAnActiveFrameBeforeTheFirstSample() {
        assertTrue(NavbarSystem.holdsSwitch(false, true, 1000, 1000));
        assertTrue(NavbarSystem.holdsSwitch(false, true, 1000, 1000 + NavbarSystem.SWITCH_HOLD_MS));
        assertFalse(NavbarSystem.holdsSwitch(false, true, 1000, 1001 + NavbarSystem.SWITCH_HOLD_MS));
        assertFalse(NavbarSystem.holdsSwitch(true, true, 1000, 1100));
        assertFalse(NavbarSystem.holdsSwitch(false, false, 1000, 1100));
        assertFalse(NavbarSystem.holdsSwitch(false, true, 1000, 999));
        assertTrue(NavbarSystem.SWITCH_HOLD_MS < NavbarSystem.FRAME_TIMEOUT_MS);
        assertTrue(NavbarSystem.FIRST_DRAW_MS < NavbarSystem.COALESCE_MS);
    }
}
