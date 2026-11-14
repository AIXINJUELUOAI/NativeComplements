package dev.nativecomplements.navdemo;

import android.os.Bundle;
import java.util.Arrays;

/** Single-frame foreground envelopes, not semantic list recognition. No evidence keeps the initial decision. */
public final class DemoStructure {
    private DemoStructure() {}
    private static final int C = 32;
    private static final String ALGORITHM = "layout-envelope-v4;singleFrame<=320dp;grid=32xceil(height/4dp),max80;"
            + "cell=4x4RGBmean;background=mostFrequent16RGBbin-firstColor;foreground=maxRGB>40;"
            + "close=1cell-horizontal-then-vertical;components=4connected,bboxFill,area>=2;"
            + "rejectBoxes=width>=30or(height>=rows*0.8andwidth<=2);"
            + "blocks=48/96/160dp;reference=nonoverlap;bottomGap<=32dp-and-identical-reference-trailingGap;"
            + "2D=components>=2,occupiedRows(width>=4)>=3,xSpan>=5,occupancy=[.08,.80];"
            + "score=.6IoU+.2rowProfile+.2columnProfile;NATIVE>=.78andIoU>=.60;rowPitch=3-leading6colRuns,min6rows,tolerance20%or2rows,tailGap<=precedingGap+2;rowMatch=profileIoU>=.70or(leading0..6andtrailing20..30spanCenterDelta<=2,widthRatio>=.4);otherwise=INCONCLUSIVE";

    public static Bundle analyze(int[] pixels, int width, int height, float density) {
        if (pixels == null || width < C || height <= 0 || !Float.isFinite(density) || density <= 0
                || (long) width * height != pixels.length || height > Math.ceil(320.0 * density) + 1)
            return result("INCONCLUSIVE", "invalid_geometry", 0, 0, 0);
        int rows = Math.min(80, (int) Math.ceil(height / (4.0 * density)));
        if (rows < 24) return result("INCONCLUSIVE", "insufficient_height", 0, 0, 0);
        int[] grid = new int[rows * C], histogram = new int[4096], representative = new int[4096];
        for (int y = 0; y < rows; y++) for (int x = 0; x < C; x++) {
            int r = 0, g = 0, b = 0;
            for (int sy = 0; sy < 4; sy++) for (int sx = 0; sx < 4; sx++) {
                int p = pixels[Math.min(height - 1, (int) ((y + (sy + .5) / 4) * height / rows)) * width
                        + Math.min(width - 1, (int) ((x + (sx + .5) / 4) * width / C))];
                if ((p >>> 24) != 255) return result("INCONCLUSIVE", "non_opaque_pixels", 0, 0, 0);
                r += (p >>> 16) & 255; g += (p >>> 8) & 255; b += p & 255;
            }
            int color = ((r / 16) << 16) | ((g / 16) << 8) | (b / 16);
            grid[y * C + x] = color;
            int bin = ((color >>> 12) & 0xf00) | ((color >>> 8) & 0xf0) | ((color >>> 4) & 15);
            if (histogram[bin]++ == 0) representative[bin] = color;
        }
        int dominant = 0;
        for (int i = 1; i < histogram.length; i++) if (histogram[i] > histogram[dominant]) dominant = i;
        boolean[] foreground = new boolean[grid.length];
        for (int i = 0; i < grid.length; i++) foreground[i] = different(grid[i], representative[dominant]);
        boolean[] closed = foreground.clone();
        for (int y = 0; y < rows; y++) for (int x = 1; x < C - 1; x++)
            if (foreground[y * C + x - 1] && foreground[y * C + x + 1]) closed[y * C + x] = true;
        foreground = closed.clone();
        for (int y = 1; y < rows - 1; y++) for (int x = 0; x < C; x++)
            if (foreground[(y - 1) * C + x] && foreground[(y + 1) * C + x]) closed[y * C + x] = true;
        int[] labels = new int[grid.length], queue = new int[grid.length];
        boolean[] envelope = new boolean[grid.length];
        int id = 0;
        for (int seed = 0; seed < closed.length; seed++) {
            if (!closed[seed] || labels[seed] != 0) continue;
            int head = 0, tail = 1, minX = C, maxX = 0, minY = rows, maxY = 0;
            labels[seed] = ++id; queue[0] = seed;
            while (head < tail) {
                int p = queue[head++], x = p % C, y = p / C;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                for (int next : new int[]{x > 0 ? p - 1 : -1, x < C - 1 ? p + 1 : -1, p - C, p + C})
                    if (next >= 0 && next < closed.length && closed[next] && labels[next] == 0) {
                        labels[next] = id; queue[tail++] = next;
                    }
            }
            int bw = maxX - minX + 1, bh = maxY - minY + 1;
            if (tail < 2 || bw >= 30 || (bh >= rows * .8 && bw <= 2)) continue;
            for (int y = minY; y <= maxY; y++) Arrays.fill(envelope, y * C + minX, y * C + maxX + 1, true);
        }
        double best = 0, texture = 0; int offset = 0;
        int gap = 0;
        while (gap < rows && !occupied(envelope, rows - gap - 1)) gap++;
        if (gap > Math.ceil(32 * density * rows / height))
            return result("INCONCLUSIVE", "bottom_gap_exceeds_32dp", 0, 0, 0);
        Bundle repeatedRows = repeatedRows(envelope, rows, gap, height, density);
        if (repeatedRows != null) return repeatedRows;
        for (int dp : new int[]{48, 96, 160}) {
            int block = Math.max(4, Math.round(dp * density * rows / height));
            int bottom = rows - block;
            if (bottom < block || !structured(envelope, labels, bottom, block)) continue;

            texture = occupancy(envelope, bottom, block);
            for (int start = 0; start + block <= bottom; start++) {
                if (!structured(envelope, labels, start, block)) continue;
                double[] score = similarity(envelope, bottom, start, block);
                if (score[0] > best) { best = score[0]; offset = Math.round((bottom - start) * height / (float) rows / density); }
                boolean sameTrailingGap = true;
                for (int y = start + block - gap; y < start + block; y++)
                    if (occupied(envelope, y)) sameTrailingGap = false;
                if (gap > 0 && !occupied(envelope, start + block - gap - 1)) sameTrailingGap = false;
                if (score[0] >= .78 && score[1] >= .60 && sameTrailingGap)
                    return result("NATIVE", "repeated_foreground_envelopes_reach_bottom", score[0], texture,
                            Math.round((bottom - start) * height / (float) rows / density));
            }
        }
        return result("INCONCLUSIVE", "no_repeated_layout_reaching_bottom", best, texture, offset);
    }

    private static Bundle repeatedRows(boolean[] mask, int rows, int gap, int height, float density) {
        int[] starts = new int[rows], ends = new int[rows];
        int count = 0;
        for (int y = 0; y < rows;) {
            if (!anchorOccupied(mask, y)) { y++; continue; }
            int start = y;
            while (y < rows && anchorOccupied(mask, y)) y++;
            if (y - start >= 2) { starts[count] = start; ends[count++] = y; }
        }
        if (count < 3) return null;
        int a = count - 3, b = count - 2, c = count - 1;
        int period1 = starts[b] - starts[a], period2 = starts[c] - starts[b];
        if (period1 < 6 || Math.abs(period1 - period2) > Math.max(2, Math.min(period1, period2) / 5)) return null;
        int precedingGap = starts[c] - ends[b];
        int anchorGap = rows - ends[c];
        if (anchorGap > Math.ceil(32 * density * rows / height) || anchorGap > precedingGap + 2) return null;
        boolean[][] profile = new boolean[3][C];
        int[] index = {a, b, c};
        for (int k = 0; k < 3; k++) {
            int row = index[k];
            for (int y = starts[row]; y < ends[row]; y++) for (int x = 0; x < C; x++)
                profile[k][x] |= mask[y * C + x];
        }
        int spans = 0;
        for (int x = 0; x < C; x++) if (profile[2][x] && (x == 0 || !profile[2][x - 1])) spans++;
        if (spans < 2 || ends[c] - starts[c] < 3) return null;
        double score = 1;
        for (int k = 0; k < 2; k++) {
            int intersection = 0, union = 0;
            for (int x = 0; x < C; x++) { if (profile[k][x] && profile[2][x]) intersection++; if (profile[k][x] || profile[2][x]) union++; }
            score = Math.min(score, union == 0 ? 0 : intersection / (double) union);
        }
        if (score < .70) {
            // Text width may vary; require recurring leading icon and trailing control envelopes instead.
            for (int k = 0; k < 2; k++) for (int[] range : new int[][]{{0, 6}, {20, 30}}) {
                int first = C, last = -1, targetFirst = C, targetLast = -1;
                for (int x = range[0]; x < range[1]; x++) {
                    if (profile[k][x]) { first = Math.min(first, x); last = x; }
                    if (profile[2][x]) { targetFirst = Math.min(targetFirst, x); targetLast = x; }
                }
                int w = last - first + 1, tw = targetLast - targetFirst + 1;
                if (w < 2 || tw < 2 || Math.abs((first + last) - (targetFirst + targetLast)) > 4
                        || Math.min(w, tw) / (double) Math.max(w, tw) < .4) return null;
            }
            score = .70;
        }
        return result("NATIVE", "repeated_row_pitch_with_matching_trailing_gap", score,
                occupancy(mask, starts[c], ends[c] - starts[c]),
                Math.round(period2 * height / (float) rows / density));
    }
    private static boolean anchorOccupied(boolean[] mask, int row) {
        int count = 0;
        for (int x = 0; x < 6; x++) if (mask[row * C + x]) count++;
        return count >= 2;
    }
    private static boolean occupied(boolean[] mask, int row) {
        int count = 0;
        for (int x = 0; x < C; x++) if (mask[row * C + x]) count++;
        return count >= 2;
    }
    private static boolean different(int a, int b) {
        for (int s = 0; s <= 16; s += 8) if (Math.abs(((a >>> s) & 255) - ((b >>> s) & 255)) > 40) return true;
        return false;
    }
    private static double occupancy(boolean[] mask, int start, int n) {
        int count = 0;
        for (int i = start * C; i < (start + n) * C; i++) if (mask[i]) count++;
        return count / (double) (n * C);
    }
    private static boolean structured(boolean[] mask, int[] labels, int start, int n) {
        double ratio = occupancy(mask, start, n);
        if (ratio < .08 || ratio > .80) return false;
        java.util.HashSet<Integer> ids = new java.util.HashSet<>();
        int minX = C, maxX = -1, occupiedRows = 0;
        for (int y = start; y < start + n; y++) {
            int width = 0;
            for (int x = 0; x < C; x++) if (mask[y * C + x]) {
                width++; minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                if (labels[y * C + x] > 0) ids.add(labels[y * C + x]);
            }
            if (width >= 4) occupiedRows++;
        }
        return ids.size() >= 2 && occupiedRows >= 3 && maxX - minX >= 4;
    }
    private static double[] similarity(boolean[] mask, int a, int b, int n) {
        int intersection = 0, union = 0; double rowError = 0, colError = 0;
        for (int y = 0; y < n; y++) {
            int ra = 0, rb = 0;
            for (int x = 0; x < C; x++) {
                boolean p = mask[(a + y) * C + x], q = mask[(b + y) * C + x];
                if (p && q) intersection++; if (p || q) union++; if (p) ra++; if (q) rb++;
            }
            rowError += Math.abs(ra - rb) / (double) C;
        }
        for (int x = 0; x < C; x++) {
            int ca = 0, cb = 0;
            for (int y = 0; y < n; y++) { if (mask[(a + y) * C + x]) ca++; if (mask[(b + y) * C + x]) cb++; }
            colError += Math.abs(ca - cb) / (double) n;
        }
        double iou = union == 0 ? 0 : intersection / (double) union;
        return new double[]{.6 * iou + .2 * (1 - rowError / n) + .2 * (1 - colError / C), iou};
    }
    private static Bundle result(String decision, String reason, double score, double texture, int offset) {
        Bundle b = new Bundle();
        b.putString("reviewDecision", decision); b.putString("reviewReason", reason);
        b.putDouble("structureScore", score); b.putDouble("structureTexture", texture);
        b.putInt("structureOffsetDp", offset); b.putString("structureAlgorithm", ALGORITHM);
        return b;
    }
}
