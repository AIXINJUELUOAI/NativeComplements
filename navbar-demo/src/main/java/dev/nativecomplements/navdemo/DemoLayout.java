package dev.nativecomplements.navdemo;

import android.os.Bundle;

/**
 * Single-frame bottom-surface classifier. EXTEND = the bottom is a fixed bar/blank surface whose last
 * row can be stretched; NATIVE (shown as 悬浮) = content continues to the boundary or the app already
 * draws under the navigation area. Offline prototype: navtools/proto.py (kept numerically in step).
 */
public final class DemoLayout {
    private DemoLayout() {}
    static final String ALGORITHM = "layout-v6;navbarItems3-6w<.2cv<.25h>=14pad2dp,noRepeat,faint>.08=>NATIVE;grid=1dp;rowBg=mode15bit-mean;tol=12;ink=28;clean>=.985;"
            + "navUniform<.98=>NATIVE;blank>=32dp=>EXTEND;touching:lastRowCov<.9=>NATIVE;"
            + "bar=surface-edges+gap8dp;repeat=IoU.6(h.6-1.6)|single IoU.85;span>=.4;line8-90dp;"
            + "even3-6cv<.25sym<.12=>EXTEND;bounded&line>=20dp=>EXTEND;else NATIVE";
    private static final int TOL = 12, INK = 28;

    /**
     * @param pixels app pixels for up to 320dp directly above the navigation boundary (ARGB, row-major)
     * @param nav    app-layer pixels inside the navigation area below the boundary, may be empty
     */
    public static Bundle analyze(int[] pixels, int width, int height, float density, int[] nav, int navWidth, int navHeight) {
        if (pixels == null || width < 32 || height < 8 || !Float.isFinite(density) || density <= 0
                || (long) width * height != pixels.length || height > Math.ceil(320.0 * density) + 2
                || nav == null || (long) navWidth * navHeight != nav.length)
            return result("INCONCLUSIVE", "invalid_geometry", new Bundle());
        Bundle info = new Bundle();
        double navUniform = uniform(nav, 0, nav.length, 1);
        info.putDouble("navUniform", navUniform);
        if (navUniform < 0.98) return result("NATIVE", "app_draws_under_navigation", info);

        int step = Math.max(1, Math.round(density));
        int rows = (height - step / 2 + step - 1) / step, cols = (width - step / 2 + step - 1) / step;
        int[] grid = new int[rows * cols];
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++)
            grid[r * cols + c] = pixels[(step / 2 + r * step) * width + step / 2 + c * step];
        int[] bg = new int[rows];
        double[] cov = new double[rows];
        boolean[] clean = new boolean[rows];
        boolean[] ink = new boolean[rows * cols];
        int[] distance = new int[rows * cols];
        int[] counts = new int[32768];
        for (int r = 0; r < rows; r++) {
            bg[r] = mode(grid, r * cols, cols, counts);
            int near = 0;
            for (int c = 0; c < cols; c++) {
                int d = dist(grid[r * cols + c], bg[r]);
                distance[r * cols + c] = d;
                if (d <= TOL) near++;
                ink[r * cols + c] = d > INK;
            }
            cov[r] = near / (double) cols;
            clean[r] = cov[r] >= 0.985;
        }
        // 1. clean margin directly above the boundary
        int b = 0;
        while (b < rows && clean[rows - 1 - b] && dist(bg[rows - 1 - b], bg[rows - 1]) <= TOL) b++;
        info.putDouble("bottomCleanDp", shown(dp(b, step, density)));
        if (dp(b, step, density) >= 32) return result("EXTEND", "blank_bottom", info);
        int cb = bg[rows - 1];
        boolean touching = dp(b, step, density) < 0.9;
        if (touching) {
            // The row that would be stretched must itself be calm, otherwise extension paints stripes.
            int last = (height - 1) * width;
            int sourceColor = modeStride(pixels, last, width, 2, counts);
            int near = 0, total = 0;
            for (int x = 0; x < width; x += 2) { total++; if (dist(pixels[last + x], sourceColor) <= TOL) near++; }
            double source = near / (double) total;
            info.putDouble("sourceRowCov", source);
            if (source < 0.9) return result("NATIVE", "content_cut_by_boundary", info);
            cb = sourceColor;
        }
        // 1b. classic navigation bar: 3-6 compact, evenly spaced, symmetric items under a calm padding row,
        //     whatever colour the content above has. A translucent (blurred) bar is left floating.
        double[] navbar = navbarBand(ink, distance, clean, rows, cols, b, density, step);
        if (navbar != null) {
            info.putInt("navbarItems", (int) navbar[0]);
            info.putDouble("navbarBandDp", shown(navbar[1]));
            info.putDouble("navbarFaint", shown(navbar[2] * 100) / 100);
            if (navbar[2] > 0.08) return result("NATIVE", "translucent_bar", info);
            return result("EXTEND", "navbar_items", info);
        }
        // 2. walk up through the surface forming the bottom margin; inset buttons/inputs stay inside it,
        //    full-width colour changes or an 8dp clean gap end it.
        int ew = Math.max(1, cols / 32);
        boolean[] busy = new boolean[rows];
        for (int r = 0; r < rows; r++) busy[r] = !clean[r];
        int r = rows - 1 - b, first = -1, lastRow = -1, gap = 0;
        String bound = "none";
        while (r >= 0) {
            if (dist(edge(grid, r * cols, ew), cb) > 14 || dist(edge(grid, r * cols + cols - ew, ew), cb) > 14) { bound = "surface"; break; }
            if (busy[r]) { if (first < 0) first = r; lastRow = r; gap = 0; }
            else { gap++; if (lastRow >= 0 && dp(gap, step, density) >= 8) { bound = "gap"; break; } }
            r--;
        }
        info.putString("bound", bound);
        if (first < 0) return result("NATIVE", "empty_gap_then_" + bound, info);
        int s = lastRow, e = first + 1;
        double lineDp = dp(e - s, step, density);
        info.putDouble("lineDp", shown(lineDp));
        info.putDouble("barDp", shown(dp(rows - 1 - r, step, density)));
        // 3. horizontal layout of the lowest line
        boolean[] profile = columns(ink, cols, s, e);
        int[][] groups = runs(profile, rowsFor(10, step, density));
        double span = groups.length == 0 ? 0 : (groups[groups.length - 1][1] - groups[0][0]) / (double) cols;
        boolean even = false;
        if (groups.length >= 3 && groups.length <= 6) {
            double[] centers = new double[groups.length];
            for (int i = 0; i < groups.length; i++) centers[i] = (groups[i][0] + groups[i][1]) / 2.0 / cols;
            double mean = 0, var = 0;
            for (int i = 1; i < centers.length; i++) mean += centers[i] - centers[i - 1];
            mean /= centers.length - 1;
            for (int i = 1; i < centers.length; i++) var += Math.pow(centers[i] - centers[i - 1] - mean, 2);
            double sd = Math.sqrt(var / (centers.length - 1));
            even = sd / mean < 0.25 && Math.abs(centers[0] + centers[centers.length - 1] - 1) < 0.12;
        }
        info.putInt("groups", groups.length);
        info.putDouble("span", shown(span * 10) / 10);
        info.putBoolean("even", even);
        // 4. repetition above -> list
        int[][] above = runs(slice(busy, s), rowsFor(8, step, density));
        int repeats = 0;
        for (int[] run : above) {
            double ratio = (run[1] - run[0]) / (double) (e - s);
            boolean[] other = columns(ink, cols, run[0], run[1]);
            int inter = 0, union = 0;
            for (int c = 0; c < cols; c++) { if (profile[c] && other[c]) inter++; if (profile[c] || other[c]) union++; }
            double iou = union == 0 ? 0 : inter / (double) union;
            if (groups.length >= 2 && ratio >= 0.6 && ratio <= 1.6 && iou >= 0.6) repeats++;
            else if (groups.length < 2 && ratio >= 0.8 && ratio <= 1.25 && iou >= 0.85) repeats++;
        }
        info.putInt("repeats", repeats);
        if (repeats >= 1) return result("NATIVE", "line_repeats_above", info);
        if (span < 0.4) return result("NATIVE", "narrow_bottom_line", info);
        if (lineDp < 8 || lineDp > 90) return result("NATIVE", "line_height", info);
        if (even) return result("EXTEND", "bottom_bar_even", info);
        if (lineDp >= 20 && !"none".equals(bound)) return result("EXTEND", "bottom_bar_" + bound, info);
        return result("NATIVE", "unbounded_line", info);
    }

    /** @return {items, bandDp, faintFraction} or null. Mirrors navbar_band() in proto.py. */
    private static double[] navbarBand(boolean[] ink, int[] distance, boolean[] clean, int rows, int cols, int b,
                                       float density, int step) {
        int bottom = rows - b, bestTop = -1;
        int[][] bestGroups = null; double[] bestCenters = null;
        for (int top = bottom - rowsFor(16, step, density); top > Math.max(-1, bottom - rowsFor(96, step, density)); top--) {
            if (top < 0) break;
            boolean[] mask = columns(ink, cols, top, bottom);
            int[][] gs = runs(mask, rowsFor(6, step, density));
            if (gs.length < 3 || gs.length > 6) continue;
            boolean narrow = true;
            for (int[] g : gs) if ((g[1] - g[0]) / (double) cols > 0.2) narrow = false;
            if (!narrow) continue;
            double[] centers = centers(gs, cols);
            if (!even(centers)) continue;
            if ((gs[gs.length - 1][1] - gs[0][0]) / (double) cols < 0.6) continue;
            boolean tall = true;
            for (int[] g : gs) {
                int lo = -1, hi = -1;
                for (int y = top; y < bottom; y++) for (int c = g[0]; c < g[1]; c++) if (ink[y * cols + c]) { if (lo < 0) lo = y; hi = y; break; }
                if (lo < 0 || (hi - lo + 1) * step / (double) density < 14) { tall = false; break; }
            }
            if (!tall) continue;
            int padTop = Math.max(0, top - rowsFor(2, step, density)), padInk = 0, padCells = (top - padTop) * cols;
            if (padCells == 0) continue;
            for (int y = padTop; y < top; y++) for (int c = 0; c < cols; c++) {
                if (!ink[y * cols + c]) continue;
                boolean inGroup = false;
                for (int[] g : gs) if (c >= g[0] && c < g[1]) { inGroup = true; break; }
                if (!inGroup) padInk++;   // badges / a raised centre button may poke up
            }
            if (padInk / (double) padCells > 0.03) continue;
            bestTop = top; bestGroups = gs; bestCenters = centers;   // keep the tallest band
        }
        if (bestTop < 0) return null;
        int limit = Math.max(0, bestTop - rowsFor(2, step, density));
        boolean[] busy = new boolean[limit];
        for (int y = 0; y < limit; y++) busy[y] = !clean[y];
        for (int[] line : runs(busy, rowsFor(6, step, density))) {
            int[][] other = runs(columns(ink, cols, line[0], line[1]), rowsFor(6, step, density));
            if (other.length < 3 || Math.abs(other.length - bestGroups.length) > 1) continue;
            double[] oc = centers(other, cols);
            boolean all = true;
            for (double c : bestCenters) {
                double nearest = 1;
                for (double x : oc) nearest = Math.min(nearest, Math.abs(c - x));
                if (nearest >= 0.04) { all = false; break; }
            }
            if (all) return null;   // the same columns repeat above: a grid of products/thumbnails
        }
        int calm = 0, faint = 0;
        for (int i = bestTop * cols; i < bottom * cols; i++) if (distance[i] <= TOL) { calm++; if (distance[i] > 4) faint++; }
        return new double[]{bestGroups.length, (bottom - bestTop) * step / (double) density, calm == 0 ? 0 : faint / (double) calm};
    }

    private static double[] centers(int[][] gs, int cols) {
        double[] out = new double[gs.length];
        for (int i = 0; i < gs.length; i++) out[i] = (gs[i][0] + gs[i][1]) / 2.0 / cols;
        return out;
    }

    private static boolean even(double[] centers) {
        double mean = 0, var = 0;
        for (int i = 1; i < centers.length; i++) mean += centers[i] - centers[i - 1];
        mean /= centers.length - 1;
        for (int i = 1; i < centers.length; i++) var += Math.pow(centers[i] - centers[i - 1] - mean, 2);
        double sd = Math.sqrt(var / (centers.length - 1));
        return sd / mean < 0.25 && Math.abs(centers[0] + centers[centers.length - 1] - 1) < 0.12;
    }

    private static double dp(int n, int step, float density) { return n * step / (double) density; }
    private static double shown(double v) { return Math.round(v * 10) / 10.0; }
    private static int rowsFor(double dp, int step, float density) { return Math.max(1, (int) Math.round(dp * density / step)); }

    static int dist(int a, int b) {
        int dr = Math.abs(((a >>> 16) & 255) - ((b >>> 16) & 255));
        int dg = Math.abs(((a >>> 8) & 255) - ((b >>> 8) & 255));
        int db = Math.abs((a & 255) - (b & 255));
        return Math.max(dr, Math.max(dg, db));
    }

    private static int key(int c) { return ((c >>> 9) & 0x7c00) | ((c >>> 6) & 0x3e0) | ((c >>> 3) & 0x1f); }

    /** Mean colour of the most frequent 15-bit bin (ties: first occurrence). */
    private static int mode(int[] values, int offset, int length, int[] counts) { return modeStride(values, offset, length, 1, counts); }

    private static int modeStride(int[] values, int offset, int length, int stride, int[] counts) {
        int best = -1, bestCount = 0;
        for (int i = 0; i < length; i += stride) {
            int k = key(values[offset + i]);
            if (++counts[k] > bestCount) { bestCount = counts[k]; best = k; }
        }
        long r = 0, g = 0, b = 0; int n = 0;
        for (int i = 0; i < length; i += stride) {
            int v = values[offset + i], k = key(v);
            counts[k] = 0;
            if (k == best) { r += (v >>> 16) & 255; g += (v >>> 8) & 255; b += v & 255; n++; }
        }
        return n == 0 ? 0 : (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }

    private static double uniform(int[] values, int offset, int length, int stride) {
        if (length == 0) return 1;
        int[] counts = new int[32768];
        int color = modeStride(values, offset, length, stride, counts), near = 0, total = 0;
        for (int i = 0; i < length; i += stride) { total++; if (dist(values[offset + i], color) <= TOL) near++; }
        return near / (double) total;
    }

    /** Per-channel median of a short run of samples at a row edge. */
    private static int edge(int[] grid, int offset, int n) {
        int[] r = new int[n], g = new int[n], b = new int[n];
        for (int i = 0; i < n; i++) { int v = grid[offset + i]; r[i] = (v >>> 16) & 255; g[i] = (v >>> 8) & 255; b[i] = v & 255; }
        return median(r) << 16 | median(g) << 8 | median(b);
    }

    private static int median(int[] v) {
        java.util.Arrays.sort(v);
        int n = v.length;
        return n % 2 == 1 ? v[n / 2] : (v[n / 2 - 1] + v[n / 2]) / 2;
    }

    private static boolean[] columns(boolean[] ink, int cols, int start, int end) {
        boolean[] out = new boolean[cols];
        for (int r = start; r < end; r++) for (int c = 0; c < cols; c++) out[c] |= ink[r * cols + c];
        return out;
    }

    private static boolean[] slice(boolean[] values, int end) {
        boolean[] out = new boolean[end];
        System.arraycopy(values, 0, out, 0, end);
        return out;
    }

    /** Runs of true values, merging gaps of at most {@code merge}. */
    static int[][] runs(boolean[] mask, int merge) {
        java.util.ArrayList<int[]> out = new java.util.ArrayList<>();
        int i = 0;
        while (i < mask.length) {
            if (!mask[i]) { i++; continue; }
            int start = i;
            while (i < mask.length && mask[i]) i++;
            if (!out.isEmpty() && start - out.get(out.size() - 1)[1] <= merge) out.get(out.size() - 1)[1] = i;
            else out.add(new int[]{start, i});
        }
        return out.toArray(new int[0][]);
    }

    private static Bundle result(String decision, String reason, Bundle info) {
        Bundle b = new Bundle();
        b.putString("reviewDecision", decision);
        b.putString("reviewReason", reason);
        b.putString("structureAlgorithm", ALGORITHM);
        StringBuilder text = new StringBuilder();
        for (String k : new java.util.TreeSet<>(info.keySet())) text.append(k).append('=').append(info.get(k)).append(';');
        b.putString("layoutFeatures", text.toString());
        return b;
    }
}
