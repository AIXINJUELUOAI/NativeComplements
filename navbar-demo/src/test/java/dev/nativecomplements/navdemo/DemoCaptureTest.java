package dev.nativecomplements.navdemo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class DemoCaptureTest {
    @Test public void narrowInitialInputIsFromTheSameFrameBottomRows() {
        int[] full = {1, 2, 3, 4, 5, 6, 7, 8};
        assertArrayEquals(new int[]{5, 6, 7, 8}, DemoCapture.bottomStrip(full, 2, 4, 2));
        assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6, 7, 8}, full);
    }

    @Test public void invalidGeometryIsNotResizedOrPadded() {
        assertThrows(IllegalArgumentException.class, () -> DemoCapture.bottomStrip(new int[4], 2, 3, 1));
        assertThrows(IllegalArgumentException.class, () -> DemoCapture.bottomStrip(new int[4], 2, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> DemoCapture.bottomStrip(null, 2, 2, 1));
    }
}
