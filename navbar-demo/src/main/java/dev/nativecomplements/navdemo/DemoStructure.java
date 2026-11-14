package dev.nativecomplements.navdemo;

import android.os.Bundle;

/** Bounded edge-layout heuristic; no match is not evidence for extension. */
public final class DemoStructure {
    private DemoStructure() {}
    private static final int COLS = 32, BLOCK = 12;
    private static final String ALGORITHM = "edge-layout-v1;maxHeight=320dp;grid=32xceil(height/4dp);"
            + "sample=4x4-subcell-RGBmean;edge=maxRGB>32;block=12rows;2D=H>=6,V>=6,rows>=4,cols>=4;"
            + "texture=[0.05,0.75];repeat=IoU>=0.85,1-nonoverlapping-reference;"
            + "backgroundCandidate=6..18flatRows-and24..72dp,boundaryV>=0.75,complexAbove;candidateOrNoMatch=INCONCLUSIVE";

    public static Bundle analyze(int[] pixels, int width, int height, float density) {
        if (pixels == null || width < COLS || height <= 0 || !Float.isFinite(density) || density <= 0
                || (long) width * height != pixels.length || height > Math.ceil(320.0 * density) + 1)
            return result("INCONCLUSIVE", "invalid_geometry", 0, 0, 0);
        int rows = Math.min(80, (int) Math.ceil(height / (4.0 * density)));
        if (rows < BLOCK * 3) return result("INCONCLUSIVE", "insufficient_height", 0, 0, 0);
        int[][] grid = new int[rows][COLS];
        for (int y = 0; y < rows; y++) for (int x = 0; x < COLS; x++) {
            int red = 0, green = 0, blue = 0;
            for (int sy = 0; sy < 4; sy++) for (int sx = 0; sx < 4; sx++) {
                int color = pixels[Math.min(height - 1, (int) ((y + (sy + 0.5) / 4) * height / rows)) * width
                        + Math.min(width - 1, (int) ((x + (sx + 0.5) / 4) * width / COLS))];
                if ((color >>> 24) != 255) return result("INCONCLUSIVE", "non_opaque_pixels", 0, 0, 0);
                red += (color >>> 16) & 255;
                green += (color >>> 8) & 255;
                blue += color & 255;
            }
            grid[y][x] = 0xff000000 | ((red / 16) << 16) | ((green / 16) << 8) | (blue / 16);
        }
        int[][] edges = new int[rows][COLS];
        for (int y = 0; y < rows; y++) for (int x = 0; x < COLS; x++) {
            if (x > 0 && different(grid[y][x], grid[y][x - 1])) edges[y][x] |= 1;
            if (y > 0 && different(grid[y][x], grid[y - 1][x])) edges[y][x] |= 2;
        }
        int bottom = rows - BLOCK;
        double texture = texture(edges, bottom);
        double best = 0;
        int offset = 0;
        if (structured(edges, bottom)) {
            for (int start = 0; start + BLOCK <= bottom; start++) {
                if (!structured(edges, start)) continue;
                double score = similarity(edges, bottom, start);
                if (score > best) { best = score; offset = Math.round((bottom - start) * height / (float) rows / density); }
            }
            if (best >= 0.85) return result("NATIVE", "repeated_two_dimensional_layout", best, texture, offset);
        }
        // A separated flat band may also be a card footer: report a candidate, never infer extension.
        if (!structured(edges, bottom)) for (int flatRows = 6; flatRows <= 18; flatRows++) {
            int boundary = rows - flatRows;
            double bandDp = flatRows * height / (double) rows / density;
            if (boundary < BLOCK || bandDp < 24 || bandDp > 72) continue;
            int base = grid[rows - 1][0], separators = 0;
            boolean flat = true;
            for (int y = boundary; y < rows; y++) for (int x = 0; x < COLS; x++)
                if (different(base, grid[y][x])) flat = false;
            for (int x = 0; x < COLS; x++) if ((edges[boundary][x] & 2) != 0) separators++;
            if (flat && separators >= COLS * 0.75 && structured(edges, boundary - BLOCK))
                return result("INCONCLUSIVE", "isolated_background_candidate", separators / (double) COLS,
                        texture, Math.round(flatRows * height / (float) rows / density));
        }
        return result("INCONCLUSIVE", "insufficient_or_conflicting_structure", best, texture, offset);
    }

    private static boolean different(int a, int b) {
        for (int shift = 0; shift <= 16; shift += 8)
            if (Math.abs(((a >>> shift) & 255) - ((b >>> shift) & 255)) > 32) return true;
        return false;
    }

    private static double texture(int[][] edges, int start) {
        int count = 0;
        for (int y = 1; y < BLOCK; y++) for (int x = 1; x < COLS; x++)
            count += Integer.bitCount(edges[start + y][x]);
        return count / (double) ((BLOCK - 1) * (COLS - 1) * 2);
    }

    private static boolean structured(int[][] edges, int start) {
        int horizontal = 0, vertical = 0, occupiedRows = 0, occupiedCols = 0;
        boolean[] columns = new boolean[COLS];
        for (int y = 1; y < BLOCK; y++) {
            boolean occupied = false;
            for (int x = 1; x < COLS; x++) {
                int edge = edges[start + y][x];
                if ((edge & 1) != 0) horizontal++;
                if ((edge & 2) != 0) vertical++;
                if (edge != 0) { occupied = true; columns[x] = true; }
            }
            if (occupied) occupiedRows++;
        }
        for (boolean column : columns) if (column) occupiedCols++;
        double texture = texture(edges, start);
        return horizontal >= 6 && vertical >= 6 && occupiedRows >= 4 && occupiedCols >= 4
                && texture >= 0.05 && texture <= 0.75;
    }

    private static double similarity(int[][] edges, int a, int b) {
        int intersection = 0, union = 0;
        for (int y = 1; y < BLOCK; y++) for (int x = 1; x < COLS; x++) {
            intersection += Integer.bitCount(edges[a + y][x] & edges[b + y][x]);
            union += Integer.bitCount(edges[a + y][x] | edges[b + y][x]);
        }
        return union == 0 ? 0 : intersection / (double) union;
    }

    private static Bundle result(String decision, String reason, double score, double texture, int offset) {
        Bundle result = new Bundle();
        result.putString("reviewDecision", decision);
        result.putString("reviewReason", reason);
        result.putDouble("structureScore", score);
        result.putDouble("structureTexture", texture);
        result.putInt("structureOffsetDp", offset);
        result.putString("structureAlgorithm", ALGORITHM);
        return result;
    }
}
