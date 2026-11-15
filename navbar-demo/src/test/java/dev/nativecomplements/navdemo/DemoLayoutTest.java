package dev.nativecomplements.navdemo;

import android.os.Bundle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Synthetic layouts at density 1 (1px = 1dp). Real-page accuracy is checked offline against screenshots. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 36, manifest = Config.NONE)
public class DemoLayoutTest {
    private static final int W = 360, H = 320, WHITE = 0xffffffff, GREY = 0xfff2f2f2, INK = 0xff222222;
    private final int[] noNav = new int[0];

    private static int[] page(int color) { int[] p = new int[W * H]; java.util.Arrays.fill(p, color); return p; }
    private static void rect(int[] p, int x0, int y0, int x1, int y1, int color) {
        for (int y = Math.max(0, y0); y < Math.min(H, y1); y++) for (int x = Math.max(0, x0); x < Math.min(W, x1); x++) p[y * W + x] = color;
    }
    private Bundle run(int[] p) { return DemoLayout.analyze(p, W, H, 1f, noNav, W, 0); }

    /** Grey list of identical rows (avatar + two text lines), cut by the boundary. */
    private static int[] list(int offset) {
        int[] p = page(WHITE);
        for (int top = -offset; top < H; top += 72) {
            rect(p, 16, top + 12, 56, top + 52, INK);
            rect(p, 70, top + 16, 260, top + 28, INK);
            rect(p, 70, top + 36, 200, top + 46, INK);
            rect(p, 0, top + 70, W, top + 71, GREY);
        }
        return p;
    }

    /** Feed above a white bottom bar with five evenly spaced icon+label items and a separator. */
    private static int[] navigationBar() {
        int[] p = page(GREY);
        for (int top = 0; top < H - 56; top += 40) rect(p, 12, top + 8, 300, top + 20, INK);
        rect(p, 0, H - 57, W, H, WHITE);
        rect(p, 0, H - 57, W, H - 56, 0xffdddddd);
        for (int i = 0; i < 5; i++) {
            int cx = W / 10 + i * W / 5;
            rect(p, cx - 12, H - 50, cx + 12, H - 26, INK);
            rect(p, cx - 14, H - 20, cx + 14, H - 10, INK);
        }
        return p;
    }

    @Test public void fixedBottomBarExtends() {
        Bundle r = run(navigationBar());
        assertEquals(r.getString("reviewReason"), "EXTEND", r.getString("reviewDecision"));
    }

    /** White navigation bar directly under white content, no separator line (美团/闲鱼 style). */
    private static int[] seamlessNavigationBar(boolean translucent) {
        int[] p = list(13);
        rect(p, 0, H - 52, W, H, WHITE);
        if (translucent) for (int y = H - 52; y < H; y++) for (int x = 0; x < W; x++)
            if (((x / 3) + (y / 2)) % 4 == 0) p[y * W + x] = 0xfff4f4f4;   // blurred content behind
        for (int i = 0; i < 4; i++) {
            int cx = W / 8 + i * W / 4;
            rect(p, cx - 12, H - 46, cx + 12, H - 22, INK);
            rect(p, cx - 14, H - 18, cx + 14, H - 8, INK);
        }
        return p;
    }

    @Test public void seamlessNavigationBarExtends() {
        Bundle r = run(seamlessNavigationBar(false));
        assertEquals(r.getString("reviewReason") + " " + r.getString("layoutFeatures"), "EXTEND", r.getString("reviewDecision"));
        assertEquals("navbar_items", r.getString("reviewReason"));
    }

    @Test public void translucentNavigationBarFloats() {
        Bundle r = run(seamlessNavigationBar(true));
        assertEquals(r.getString("layoutFeatures"), "translucent_bar", r.getString("reviewReason"));
        assertEquals("NATIVE", r.getString("reviewDecision"));
    }

    @Test public void listCutByBoundaryFloats() {
        for (int offset : new int[]{0, 9, 23, 41, 60}) {
            Bundle r = run(list(offset));
            assertEquals("offset " + offset + " " + r.getString("reviewReason") + " " + r.getString("layoutFeatures"),
                    "NATIVE", r.getString("reviewDecision"));
        }
    }

    @Test public void blankTailExtends() {
        int[] p = page(GREY);
        rect(p, 12, 20, 340, 120, WHITE);
        Bundle r = run(p);
        assertEquals("EXTEND", r.getString("reviewDecision"));
        assertEquals("blank_bottom", r.getString("reviewReason"));
    }

    @Test public void appDrawingUnderNavigationFloats() {
        int[] nav = new int[W * 24];
        for (int i = 0; i < nav.length; i++) nav[i] = (i / 7) % 3 == 0 ? INK : WHITE;
        Bundle r = DemoLayout.analyze(navigationBar(), W, H, 1f, nav, W, 24);
        assertEquals("NATIVE", r.getString("reviewDecision"));
        assertEquals("app_draws_under_navigation", r.getString("reviewReason"));
        int[] flat = new int[W * 24]; java.util.Arrays.fill(flat, WHITE);
        assertEquals("EXTEND", DemoLayout.analyze(navigationBar(), W, H, 1f, flat, W, 24).getString("reviewDecision"));
    }

    @Test public void singleLeftHeaderAboveGapFloats() {
        int[] p = list(0);
        rect(p, 0, H - 60, W, H, WHITE);
        rect(p, 16, H - 30, 90, H - 16, INK);
        assertEquals("NATIVE", run(p).getString("reviewDecision"));
    }

    @Test public void invalidInputIsInconclusive() {
        assertEquals("INCONCLUSIVE", DemoLayout.analyze(null, W, H, 1f, noNav, W, 0).getString("reviewDecision"));
        assertEquals("INCONCLUSIVE", DemoLayout.analyze(page(WHITE), W, H, Float.NaN, noNav, W, 0).getString("reviewDecision"));
        assertEquals("INCONCLUSIVE", DemoLayout.analyze(page(WHITE), W, H, 1f, null, W, 0).getString("reviewDecision"));
        assertEquals("INCONCLUSIVE", DemoLayout.analyze(new int[W * 400], W, 400, 1f, noNav, W, 0).getString("reviewDecision"));
    }
}
