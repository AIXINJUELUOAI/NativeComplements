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
    private static final int W = 128, H = 320;
    private int[] flat() { int[] p = new int[W * H]; Arrays.fill(p, -1); return p; }
    private Bundle analyze(int[] p) { return DemoStructure.analyze(p, W, H, 1); }
    private int[] cards(boolean texture) {
        int[] p = flat();
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int phase = y % 160;
            boolean image = x >= 8 && x < 40 && phase >= 16;
            boolean text = x >= 64 && x < 104 && ((phase >= 24 && phase < 44)
                    || (phase >= 64 && phase < 84) || phase >= 104);
            if (image || text) p[y * W + x] = texture && image
                    ? (((x / 8 + y / 8) % 2 == 0) ? 0xffb02020 : 0xff2040b0)
                    : (y < 160 ? 0xff208030 : 0xffb03070);
        }
        return p;
    }
    @Test public void differentImageContentsSameOneHundredSixtyDpLayoutReachBottom() {
        Bundle b = analyze(cards(true));
        assertEquals("NATIVE", b.getString("reviewDecision"));
        assertEquals("repeated_foreground_envelopes_reach_bottom", b.getString("reviewReason"));
        assertTrue(b.getDouble("structureScore") >= .78);
        assertTrue(b.getString("structureAlgorithm").contains("layout-envelope-v4"));
    }
    @Test public void sameImageColorsDifferentBottomLayoutIsNotEnough() {
        int[] p = cards(false);
        for (int y = 224; y < H; y++) {
            Arrays.fill(p, y * W, (y + 1) * W, -1);
            Arrays.fill(p, y * W + 112, (y + 1) * W, 0xff208030);
        }
        assertEquals("INCONCLUSIVE", analyze(p).getString("reviewDecision"));
    }
    @Test public void avatarsAndTailButtonsHaveRepeatableEnvelopes() {
        int[] p = flat();
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int r = y % 80;
            if ((x >= 8 && x < 32 && r >= 8 && r < 72)
                    || (x >= 48 && x < 88 && ((r >= 12 && r < 32) || r >= 48))
                    || (x >= 108 && x < 120 && r >= 48)) p[y * W + x] = 0xff202020;
        }
        assertEquals("NATIVE", analyze(p).getString("reviewDecision"));
    }
    @Test public void croppedContinuationCanMatchWithoutWholeCardVisible() {
        int[] source = cards(true), p = flat();
        System.arraycopy(source, 8 * W, p, 0, (H - 8) * W);
        System.arraycopy(source, 152 * W, p, (H - 8) * W, 8 * W);
        assertEquals("NATIVE", analyze(p).getString("reviewDecision"));
    }
    @Test public void uniformAndOneDimensionalStripesNeverProveLayout() {
        assertEquals("INCONCLUSIVE", analyze(flat()).getString("reviewDecision"));
        for (boolean vertical : new boolean[]{true, false}) {
            int[] p = flat();
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++)
                if (((vertical ? x : y) / 8) % 2 == 0) p[y * W + x] = 0xff000000;
            assertEquals("INCONCLUSIVE", analyze(p).getString("reviewDecision"));
        }
    }
    @Test public void fixedBottomRegionAndCardFooterCannotForceDecision() {
        for (int color : new int[]{-1, 0xff303030}) {
            int[] p = cards(true);
            Arrays.fill(p, (H - 40) * W, p.length, color);
            assertEquals("INCONCLUSIVE", analyze(p).getString("reviewDecision"));
        }
    }
    @Test public void thinLinesOrSmallPhaseShiftsCannotTurnBlankIntoLayout() {
        int[] p = flat();
        for (int y = 0; y < H; y += 40) Arrays.fill(p, y * W, (y + 1) * W, 0xff000000);
        assertEquals("INCONCLUSIVE", analyze(p).getString("reviewDecision"));
        for (int shift : new int[]{1, 2}) {
            int[] q = flat(); System.arraycopy(p, 0, q, shift * W, (H - shift) * W);
            assertEquals("INCONCLUSIVE", analyze(q).getString("reviewDecision"));
        }
    }
    private int[] spacedRows() {
        int[] p = flat();
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int phase = y % 96;
            if (phase >= 8 && phase < 72 && ((x >= 8 && x < 20) || (x >= 84 && x < 108)))
                p[y * W + x] = 0xff202020;
        }
        return p;
    }
    @Test public void rowPitchAllowsMatchingShortTrailingBlank() {
        int[] p = spacedRows();
        // Shift to end at a complete row plus its normal 24dp row gap.
        int[] q = flat();
        System.arraycopy(p, 32 * W, q, 0, (H - 32) * W);
        assertEquals("NATIVE", analyze(q).getString("reviewDecision"));
    }
    @Test public void repeatedContentAboveFixedTabOrButtonBarDoesNotMatchItsPitch() {
        for (boolean tabs : new boolean[]{true, false}) for (int barHeight : new int[]{24, 32, 48}) {
            int[] p = spacedRows();
            int top = H - barHeight;
            Arrays.fill(p, top * W, p.length, -1);
            for (int y = top + 4; y < H - 4; y++) for (int x = 0; x < W; x++)
                if (tabs ? ((x >= 12 && x < 24) || (x >= 56 && x < 68) || (x >= 100 && x < 112))
                        : (x >= 36 && x < 100)) p[y * W + x] = 0xff606060;
            assertEquals("INCONCLUSIVE", analyze(p).getString("reviewDecision"));
        }
    }
    @Test public void invalidInputIsInconclusive() {
        assertEquals("INCONCLUSIVE", DemoStructure.analyze(null, W, H, 1).getString("reviewDecision"));
        assertEquals("INCONCLUSIVE", DemoStructure.analyze(flat(), W, H, Float.NaN).getString("reviewDecision"));
        assertEquals("INCONCLUSIVE", DemoStructure.analyze(new int[128 * 400], 128, 400, 1).getString("reviewDecision"));
    }
}
