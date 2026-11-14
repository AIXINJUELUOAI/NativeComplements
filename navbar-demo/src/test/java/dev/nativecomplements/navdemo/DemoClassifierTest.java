package dev.nativecomplements.navdemo;

import android.os.Bundle;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE)
public class DemoClassifierTest {
    private int[] flat(int width, int height, int color) {
        int[] pixels = new int[width * height];
        Arrays.fill(pixels, color);
        return pixels;
    }
    private Bundle analyze(int[] pixels) { return DemoClassifier.analyze(pixels, 120, 24, 1); }

    @Test public void flatAndOpaqueBlackAreValidExtensionEvidence() {
        for (int color : new int[]{0xffeeeeee, 0xff000000}) {
            Bundle result = analyze(flat(120, 24, color));
            assertEquals("EXTEND", result.getString("decision"));
            assertEquals(1, result.getDouble("stableRatio"), 0);
            assertEquals(1, result.getDouble("edgeRatio"), 0);
            assertEquals(1, result.getInt("segments"));
            assertTrue(result.getString("algorithm").contains("v1"));
        }
    }
    @Test public void wideLeftAndRightRegionsCanExtendWithoutGlobalColorAgreement() {
        int[] pixels = flat(120, 24, 0xffeeeeee);
        for (int y = 0; y < 24; y++) Arrays.fill(pixels, y * 120 + 48, (y + 1) * 120, 0xff333333);
        Bundle result = analyze(pixels);
        assertEquals("EXTEND", result.getString("decision"));
        assertEquals(2, result.getInt("segments"));
    }
    @Test public void verticalTextureFailsEvenWithUniformLastThreeRows() {
        int[] pixels = flat(120, 24, 0xffffffff);
        for (int y = 0; y < 21; y += 2) Arrays.fill(pixels, y * 120, (y + 1) * 120, 0xff000000);
        Bundle result = analyze(pixels);
        assertEquals("NATIVE", result.getString("decision"));
        assertEquals("vertical_texture", result.getString("reason"));
    }
    @Test public void onePixelBoundaryLineIsNotIgnoredAsMedianOutlier() {
        int[] pixels = flat(120, 24, 0xffffffff);
        Arrays.fill(pixels, 23 * 120, pixels.length, 0xff000000);
        Bundle result = analyze(pixels);
        assertEquals("NATIVE", result.getString("decision"));
        assertEquals("boundary_content", result.getString("reason"));
        assertEquals(1, result.getDouble("stableRatio"), 0);
        assertEquals(2.0 / 3, result.getDouble("edgeRatio"), 0.0001);
    }
    @Test public void boundaryTextAndNarrowVerticalStripeAreRejected() {
        int[] text = flat(120, 24, 0xffffffff);
        text[23 * 120 + 60] = 0xff000000;
        assertEquals("boundary_content", analyze(text).getString("reason"));
        int[] stripe = flat(120, 24, 0xffffffff);
        for (int y = 0; y < 24; y++) stripe[y * 120 + 60] = 0xff000000;
        Bundle result = analyze(stripe);
        assertEquals(1, result.getDouble("edgeRatio"), 0);
        assertEquals("narrow_or_fragmented_regions", result.getString("reason"));
        assertEquals("NATIVE", result.getString("decision"));
    }
    @Test public void normalizedDistanceThresholdHasExplicitInclusiveBoundary() {
        int[] pixels = flat(120, 24, 0xff808080);
        Arrays.fill(pixels, 21 * 120, pixels.length, 0xff909090);
        assertEquals("EXTEND", analyze(pixels).getString("decision"));
        Arrays.fill(pixels, 21 * 120, pixels.length, 0xff919191);
        assertEquals("NATIVE", analyze(pixels).getString("decision"));
    }
    @Test public void slowGradientUsesSegmentAnchorNotNeighborDistance() {
        int[] pixels = new int[256 * 24];
        for (int y = 0; y < 24; y++) for (int x = 0; x < 256; x++)
            pixels[y * 256 + x] = 0xff000000 | (x << 16) | (x << 8) | x;
        Bundle result = DemoClassifier.analyze(pixels, 256, 24, 1);
        assertEquals("NATIVE", result.getString("decision"));
        assertTrue(result.getInt("segments") > 8);
        assertEquals(1, result.getDouble("stableRatio"), 0);
    }
    @Test public void densityScalesGeometryAndMinimumRegionWidth() {
        assertEquals("EXTEND", DemoClassifier.analyze(flat(240, 48, 0xff123456), 240, 48, 2).getString("decision"));
        assertEquals("EXTEND", DemoClassifier.analyze(flat(120, 25, 0xff123456), 120, 25, 1.01f).getString("decision"));
        assertEquals("UNKNOWN", DemoClassifier.analyze(flat(120, 24, 0xff123456), 120, 24, 2).getString("decision"));
    }
    @Test public void malformedOrNonOpaqueInputHasNoPositiveDecision() {
        assertEquals("UNKNOWN", DemoClassifier.analyze(null, 120, 24, 1).getString("decision"));
        assertEquals("UNKNOWN", DemoClassifier.analyze(new int[1], 120, 24, 1).getString("decision"));
        assertEquals("UNKNOWN", DemoClassifier.analyze(new int[0], Integer.MAX_VALUE, Integer.MAX_VALUE, 1).getString("decision"));
        assertEquals("UNKNOWN", DemoClassifier.analyze(new int[0], 0, 24, 1).getString("decision"));
        assertEquals("UNKNOWN", DemoClassifier.analyze(flat(120, 24, 0xffffffff), 120, 24, Float.NaN).getString("decision"));
        assertEquals("UNKNOWN", DemoClassifier.analyze(flat(120, 24, 0xffffffff), 120, 24, 0).getString("decision"));
        assertEquals("UNKNOWN", analyze(flat(120, 24, 0x00ffffff)).getString("decision"));
    }
}
