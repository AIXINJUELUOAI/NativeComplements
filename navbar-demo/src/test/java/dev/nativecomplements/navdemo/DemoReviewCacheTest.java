package dev.nativecomplements.navdemo;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class DemoReviewCacheTest {
    @Test public void absoluteTtlDoesNotRenewAndEpochOrWindowNeverReuses() {
        DemoReviewCache<String> cache = new DemoReviewCache<>();
        int[] strip = {0xff123456};
        cache.put("window", 1, strip, "review", 100, false);
        assertEquals("review", cache.get("window", 1, strip, 3100));
        assertNull(cache.get("window", 1, strip, 3101));
        assertNull(cache.get("other", 1, strip, 101));
        assertNull(cache.get("window", 2, strip, 101));
        assertNull(cache.get("window", 1, strip, 99));
        cache.put("window", 1, strip, "failed", 100, true);
        assertEquals("failed", cache.get("window", 1, strip, 1100));
        assertNull(cache.get("window", 1, strip, 1101));
        cache.clear();
        assertNull(cache.get("window", 1, strip, 100));
    }

    @Test public void largeRoiRateLimitSurvivesCacheInvalidation() {
        DemoReviewCache<String> cache = new DemoReviewCache<>();
        assertTrue(cache.begin(100));
        assertFalse(cache.begin(1099));
        cache.clear();
        assertFalse(cache.begin(1099));
        assertTrue(cache.begin(1100));
        assertFalse(cache.begin(1101));
    }

    @Test public void rgbToleranceAndOnePercentAreInclusiveButAlphaIsNot() {
        int[] first = new int[100], second = new int[100];
        Arrays.fill(first, 0xff101010);
        Arrays.fill(second, 0xff181818);
        assertTrue(DemoReviewCache.similar(first, second));
        second[0] = 0xff808080;
        assertTrue(DemoReviewCache.similar(first, second));
        second[1] = 0xff808080;
        assertFalse(DemoReviewCache.similar(first, second));
        second[1] = 0xfe101010;
        assertFalse(DemoReviewCache.similar(first, second));
        assertFalse(DemoReviewCache.similar(new int[0], new int[0]));
    }
}
