package dev.nativecomplements.navdemo;

import android.os.Bundle;
import java.util.Arrays;

/** Pixel evidence only: NATIVE means "do not recommend extension", not a window mode verdict. */
public final class DemoClassifier {
    private DemoClassifier() {}
    // Experimental demo thresholds, not vendor/perceptual calibration. Max-channel RGB distance / 255.
    private static final int DISTANCE = 16;
    private static final String ALGORITHM = "rgb-column-v1;strip=ceil24dp+-1px;reference=upper12dp-median;"
            + "distance=maxRGB/255<=16/255;columnStable>=0.90;stableRatio>=0.95;"
            + "edge=last3px-all-match;segments<=8;segmentWidth>=12dp";

    public static Bundle analyze(int[] pixels, int width, int height, float density) {
        if (pixels == null || width <= 0 || height < 6 || !Float.isFinite(density) || density <= 0
                || (long) width * height != pixels.length || Math.abs(height - Math.ceil(24.0 * density)) > 1)
            return result("UNKNOWN", "invalid_geometry", 0, 0, 0);
        for (int pixel : pixels) {
            if ((pixel >>> 24) != 255) return result("UNKNOWN", "non_opaque_pixels", 0, 0, 0);
        }
        int referenceRows = Math.min(height - 3, Math.max(1, (int) Math.ceil(12.0 * density)));
        int[] reference = new int[width];
        int[] channel = new int[referenceRows];
        int stableColumns = 0, matchingEdge = 0;
        for (int x = 0; x < width; x++) {
            int color = 0xff000000;
            for (int shift = 0; shift <= 16; shift += 8) {
                for (int y = 0; y < referenceRows; y++) channel[y] = (pixels[y * width + x] >>> shift) & 255;
                Arrays.sort(channel);
                color |= channel[referenceRows / 2] << shift;
            }
            reference[x] = color;
            int matching = 0;
            for (int y = 0; y < height - 3; y++) {
                if (near(pixels[y * width + x], color)) matching++;
            }
            if (matching / (double) (height - 3) >= 0.90) stableColumns++;
            for (int y = height - 3; y < height; y++) {
                if (near(pixels[y * width + x], color)) matchingEdge++;
            }
        }
        double stableRatio = stableColumns / (double) width;
        double edgeRatio = matchingEdge / (3.0 * width);
        int segments = 1, start = 0, anchor = reference[0];
        int minimumWidth = Math.max(1, (int) Math.ceil(12.0 * density));
        boolean narrow = false;
        for (int x = 1; x < width; x++) {
            if (!near(reference[x], anchor)) {
                narrow |= x - start < minimumWidth;
                segments++;
                start = x;
                anchor = reference[x];
            }
        }
        narrow |= width - start < minimumWidth;
        if (stableRatio < 0.95) return result("NATIVE", "vertical_texture", stableRatio, edgeRatio, segments);
        if (edgeRatio < 1) return result("NATIVE", "boundary_content", stableRatio, edgeRatio, segments);
        if (narrow || segments > 8) return result("NATIVE", "narrow_or_fragmented_regions", stableRatio, edgeRatio, segments);
        return result("EXTEND", "stable_regions_reach_boundary", stableRatio, edgeRatio, segments);
    }

    private static boolean near(int a, int b) {
        for (int shift = 0; shift <= 16; shift += 8) {
            if (Math.abs(((a >>> shift) & 255) - ((b >>> shift) & 255)) > DISTANCE) return false;
        }
        return true;
    }

    private static Bundle result(String decision, String reason, double stable, double edge, int segments) {
        Bundle result = new Bundle();
        result.putString("decision", decision);
        result.putString("reason", reason);
        result.putDouble("stableRatio", stable);
        result.putDouble("edgeRatio", edge);
        result.putInt("segments", segments);
        result.putString("algorithm", ALGORITHM);
        return result;
    }
}
