package dev.local.sunwindowdemo.features.navbar;

import android.graphics.Rect;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE)
public class NavbarWindowLayerTest {
    @Test public void fullWindowUsesOnlyActualNavigationBand() {
        Rect frame = new Rect(0, 0, 1080, 2400);
        assertEquals(new Rect(0, 2320, 1080, 2400),
                NavbarWindowLayer.geometry(frame, frame, 80, new Rect()));
    }

    @Test public void intersectsDisplayAndOffsetsFrameAndSurfaceInsetsExactlyOnce() {
        Rect frame = new Rect(150, 300, 700, 1250);
        Rect display = new Rect(100, 200, 1100, 1200);
        Rect insets = new Rect(12, 24, 36, 48);
        assertEquals(new Rect(12, 844, 562, 924),
                NavbarWindowLayer.geometry(frame, display, 80, insets));
        assertEquals(new Rect(150, 300, 700, 1250), frame);
        assertEquals(new Rect(100, 200, 1100, 1200), display);
        assertEquals(new Rect(12, 24, 36, 48), insets);
        // A frame extending left of the display is clipped before local conversion.
        assertEquals(new Rect(112, 844, 1112, 924), NavbarWindowLayer.geometry(
                new Rect(0, 300, 1200, 1250), display, 80, insets));
    }

    @Test public void rejectsWindowsThatDoNotContainTheWholeBottomBand() {
        Rect display = new Rect(0, 0, 1080, 2400);
        assertNull(NavbarWindowLayer.geometry(new Rect(0, 0, 1080, 2320), display, 80, null));
        assertNull(NavbarWindowLayer.geometry(new Rect(0, 2350, 1080, 2400), display, 80, null));
        assertNull(NavbarWindowLayer.geometry(new Rect(1080, 0, 1200, 2400), display, 80, null));
    }

    @Test public void rejectsInvalidGeometryAndAllowsAbsentSurfaceInsets() {
        Rect display = new Rect(0, 0, 100, 200);
        assertEquals(new Rect(0, 180, 100, 200),
                NavbarWindowLayer.geometry(display, display, 20, null));
        assertNull(NavbarWindowLayer.geometry(null, display, 20, null));
        assertNull(NavbarWindowLayer.geometry(display, new Rect(), 20, null));
        assertNull(NavbarWindowLayer.geometry(display, display, 0, null));
        assertNull(NavbarWindowLayer.geometry(display, display, 201, null));
        assertNull(NavbarWindowLayer.geometry(display, display, 20, new Rect(-1, 0, 0, 0)));
    }
}
