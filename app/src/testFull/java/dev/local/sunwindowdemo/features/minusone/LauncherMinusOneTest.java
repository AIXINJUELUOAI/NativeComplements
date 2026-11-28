package dev.local.sunwindowdemo.features.minusone;

import android.graphics.RectF;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class LauncherMinusOneTest {
    @Test public void expandedFeedHidesTheWholeTopShadowInsteadOfMovingItsEdge() {
        var bounds = new RectF(12, 0, 1200, 200);
        LauncherMinusOne.hideTopScrim(bounds);
        assertTrue(bounds.isEmpty());
        assertEquals(12, bounds.left, .01f);
        assertEquals(200, bounds.bottom, .01f);
    }
}
