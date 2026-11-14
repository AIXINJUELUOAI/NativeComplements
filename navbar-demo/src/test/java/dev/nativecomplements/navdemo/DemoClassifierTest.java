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
    private int[] flat(int color) {
        int[] pixels = new int[120 * 24];
        Arrays.fill(pixels, color);
        return pixels;
    }
    private Bundle analyze(int[] pixels) { return DemoClassifier.analyze(pixels, 120, 24, 1); }
    private int[] split(int backgroundPercent) {
        int[] pixels = flat(0xff000000);
        for (int y = 0; y < 24; y++)
            Arrays.fill(pixels, y * 120, y * 120 + 120 * backgroundPercent / 100, 0xffffffff);
        return pixels;
    }

    @Test public void flatAndOpaqueBlackAreValid() {
        for (int color : new int[]{0xffeeeeee, 0xff000000}) {
            Bundle result = analyze(flat(color));
            assertEquals("EXTEND", result.getString("decision"));
            assertEquals(1, result.getDouble("dominantRatio"), 0);
            assertEquals(360, result.getInt("analyzedPixels"));
            assertTrue(result.getString("algorithm").contains("v2"));
            assertFalse(result.containsKey("edgeRatio"));
        }
    }
    @Test public void eightyPercentBackgroundAllowsTextAndNarrowContent() {
        Bundle result = analyze(split(80));
        assertEquals("EXTEND", result.getString("decision"));
        assertEquals(0.8, result.getDouble("dominantRatio"), 0.0001);
    }
    @Test public void decisionThresholdsAreInclusiveAndMiddleIsUnknown() {
        assertEquals("EXTEND", analyze(split(70)).getString("decision"));
        assertEquals("UNKNOWN", analyze(split(60)).getString("decision"));
        assertEquals(0.6, analyze(split(60)).getDouble("dominantRatio"), 0.0001);
        assertEquals("NATIVE", analyze(split(50)).getString("decision"));
        assertEquals(0.5, analyze(split(50)).getDouble("dominantRatio"), 0.0001);
    }
    @Test public void variedTextureHasNoDominantNeighborhood() {
        int[] pixels = flat(0xff000000);
        java.util.Random random = new java.util.Random(721);
        for (int i = 0; i < pixels.length; i++) pixels[i] = 0xff000000 | random.nextInt(0x1000000);
        Bundle result = analyze(pixels);
        assertEquals("NATIVE", result.getString("decision"));
        assertTrue(result.getDouble("dominantRatio") < 0.5);
    }
    @Test public void lowerTwelveDpAndBoundaryNeverVetoUpperBand() {
        int[] pixels = flat(0xffffffff);
        Arrays.fill(pixels, 12 * 120, pixels.length, 0xff000000);
        pixels[pixels.length - 1] = 0; // Even lower-band alpha is outside analyzed evidence.
        assertEquals("EXTEND", analyze(pixels).getString("decision"));
        assertEquals(1, analyze(pixels).getDouble("dominantRatio"), 0);
    }
    @Test public void realNeighborhoodCrossesQuantizationBinBoundary() {
        int[] pixels = flat(0xff0f0f0f);
        for (int y = 0; y < 12; y++) Arrays.fill(pixels, y * 120 + 60, (y + 1) * 120, 0xff101010);
        assertEquals(1, analyze(pixels).getDouble("dominantRatio"), 0);
        assertEquals("EXTEND", analyze(pixels).getString("decision"));
    }
    @Test public void channelToleranceIsInclusiveWithoutTransitiveColorMerging() {
        int[] pixels = flat(0xff808080);
        for (int y = 0; y < 12; y++) Arrays.fill(pixels, y * 120 + 60, (y + 1) * 120, 0xff989898);
        assertEquals(1, analyze(pixels).getDouble("dominantRatio"), 0);
        for (int y = 0; y < 12; y++) Arrays.fill(pixels, y * 120 + 60, (y + 1) * 120, 0xff999999);
        assertEquals(0.5, analyze(pixels).getDouble("dominantRatio"), 0);
    }
    @Test public void densityAndStrideDetermineAnalyzedPixelCount() {
        int[] pixels = new int[121 * 25];
        Arrays.fill(pixels, 0xff123456);
        Bundle result = DemoClassifier.analyze(pixels, 121, 25, 1.01f);
        assertEquals("EXTEND", result.getString("decision"));
        assertEquals(61 * 7, result.getInt("analyzedPixels"));
    }
    @Test public void malformedOrNonOpaqueEvidenceIsUnknown() {
        assertEquals("UNKNOWN", DemoClassifier.analyze(null, 120, 24, 1).getString("decision"));
        assertEquals("UNKNOWN", DemoClassifier.analyze(new int[1], 120, 24, 1).getString("decision"));
        assertEquals("UNKNOWN", DemoClassifier.analyze(new int[0], Integer.MAX_VALUE, Integer.MAX_VALUE, 1).getString("decision"));
        assertEquals("UNKNOWN", DemoClassifier.analyze(new int[0], 0, 24, 1).getString("decision"));
        assertEquals("UNKNOWN", DemoClassifier.analyze(flat(-1), 120, 24, Float.NaN).getString("decision"));
        assertEquals("UNKNOWN", DemoClassifier.analyze(flat(-1), 120, 24, 0).getString("decision"));
        assertEquals("UNKNOWN", DemoClassifier.analyze(flat(-1), 120, 24, 2).getString("decision"));
        int[] pixels = flat(-1);
        pixels[121] = 0; // Alpha validation also covers pixels skipped by stride.
        assertEquals("non_opaque_pixels", analyze(pixels).getString("reason"));
        assertEquals(0, analyze(pixels).getInt("analyzedPixels"));
    }
}
