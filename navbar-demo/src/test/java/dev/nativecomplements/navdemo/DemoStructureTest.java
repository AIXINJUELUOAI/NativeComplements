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
public class DemoStructureTest {
    private static final int W = 128, H = 288;
    private int[] flat() {
        int[] pixels = new int[W * H];
        Arrays.fill(pixels, 0xffffffff);
        return pixels;
    }
    private Bundle analyze(int[] pixels) { return DemoStructure.analyze(pixels, W, H, 1); }
    // Layout repeats at 48dp while each card can have different RGB colors.
    private int[] cards() {
        int[] pixels = flat();
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int row = (y / 4) % 12, column = x / 4;
            if ((row >= 2 && row <= 8 && column >= 3 && column <= 10)
                    || (row >= 3 && row <= 4 && column >= 15 && column <= 27)
                    || (row >= 7 && row <= 8 && column >= 15 && column <= 24))
                pixels[y * W + x] = (y / 48) % 2 == 0 ? 0xffff0000 : 0xff00a000;
        }
        return pixels;
    }
    @Test public void repeatedCardsMatchLayoutDespiteDifferentColors() {
        Bundle result = analyze(cards());
        assertEquals("NATIVE", result.getString("reviewDecision"));
        assertEquals("repeated_two_dimensional_layout", result.getString("reviewReason"));
        assertEquals(1, result.getDouble("structureScore"), 0);
        assertTrue(result.getInt("structureOffsetDp") >= 48);
        assertTrue(result.getString("structureAlgorithm").contains("IoU>=0.85"));
    }
    @Test public void uniformWhiteIsNotARepeatedStructure() {
        Bundle result = analyze(flat());
        assertEquals("INCONCLUSIVE", result.getString("reviewDecision"));
        assertEquals(0, result.getDouble("structureScore"), 0);
        assertEquals(0, result.getDouble("structureTexture"), 0);
    }
    @Test public void onlyVerticalColumnsOrHorizontalRowsAreNotTwoDimensionalEvidence() {
        for (boolean vertical : new boolean[]{true, false}) {
            int[] pixels = flat();
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++)
                if (((vertical ? x : y) / 8) % 2 == 0) pixels[y * W + x] = 0xff000000;
            assertEquals("INCONCLUSIVE", analyze(pixels).getString("reviewDecision"));
        }
    }
    @Test public void isolatedFixedBottomBackgroundWithListAboveIsNotRepeatedContent() {
        int[] pixels = cards();
        Arrays.fill(pixels, (H - 48) * W, pixels.length, 0xff222222);
        Bundle result = analyze(pixels);
        assertEquals("INCONCLUSIVE", result.getString("reviewDecision"));
        assertEquals("isolated_background_candidate", result.getString("reviewReason"));
    }
    @Test public void differentBottomStructureDoesNotMeanExtension() {
        int[] pixels = cards();
        for (int y = H - 48; y < H; y++) for (int x = 0; x < W; x++)
            pixels[y * W + x] = ((x / 4 + y / 4) % 2 == 0) ? 0xff0000ff : 0xffffff00;
        assertEquals("INCONCLUSIVE", analyze(pixels).getString("reviewDecision"));
    }
    @Test public void singleReferenceAtOneHundredSixtyDpPeriodIsEnough() {
        int height = 320;
        int[] pixels = new int[W * height];
        Arrays.fill(pixels, 0xffffffff);
        for (int y = 0; y < height; y++) for (int x = 0; x < W; x++) {
            int r = (y % 160) / 4, c = x / 4;
            if ((r >= 30 && r <= 36 && c >= 3 && c <= 10)
                    || (r >= 31 && r <= 32 && c >= 15 && c <= 27)
                    || (r >= 35 && r <= 36 && c >= 15 && c <= 24))
                pixels[y * W + x] = y < 160 ? 0xffff0000 : 0xff00a000;
        }
        Bundle result = DemoStructure.analyze(pixels, W, height, 1);
        assertEquals("NATIVE", result.getString("reviewDecision"));
        assertEquals(160, result.getInt("structureOffsetDp"));
    }
    @Test public void samePixelsCouldBeListFooterSoFlatBandIsOnlyCandidate() {
        int[] footer = cards();
        Arrays.fill(footer, (H - 40) * W, footer.length, 0xff222222);
        assertEquals("INCONCLUSIVE", analyze(footer).getString("reviewDecision"));
        assertEquals("isolated_background_candidate", analyze(footer).getString("reviewReason"));
    }
    @Test public void oneOrTwoPixelPhaseShiftDoesNotTurnBlankIntoSimilarity() {
        for (int shift : new int[]{1, 2}) {
            int[] source = cards(), shifted = flat();
            System.arraycopy(source, 0, shifted, shift * W, (H - shift) * W);
            String decision = analyze(shifted).getString("reviewDecision");
            assertTrue(decision.equals("NATIVE") || decision.equals("INCONCLUSIVE"));
            assertEquals("INCONCLUSIVE", analyze(flat()).getString("reviewDecision"));
        }
    }
    @Test public void giantBottomBlankDoesNotGetPositiveExtensionEvidence() {
        int[] pixels = cards();
        Arrays.fill(pixels, (H - 100) * W, pixels.length, 0xff222222);
        assertEquals("INCONCLUSIVE", analyze(pixels).getString("reviewDecision"));
    }
    @Test public void invalidInputAndShortEvidenceAreInconclusive() {
        assertEquals("INCONCLUSIVE", DemoStructure.analyze(null, W, H, 1).getString("reviewDecision"));
        assertEquals("INCONCLUSIVE", DemoStructure.analyze(flat(), W, H, Float.NaN).getString("reviewDecision"));
        assertEquals("INCONCLUSIVE", DemoStructure.analyze(new int[128 * 100], 128, 100, 1).getString("reviewDecision"));
        assertEquals("INCONCLUSIVE", DemoStructure.analyze(new int[128 * 400], 128, 400, 1).getString("reviewDecision"));
    }
}
