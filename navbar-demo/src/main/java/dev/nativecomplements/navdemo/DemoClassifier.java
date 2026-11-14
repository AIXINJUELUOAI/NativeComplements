package dev.nativecomplements.navdemo;

import android.os.Bundle;

/** Observation only: NATIVE means "do not recommend extension", not a window mode verdict. */
public final class DemoClassifier {
    private DemoClassifier() {}
    // Experimental RGB approximation, not perceptual color difference or exact clustering.
    private static final String ALGORITHM = "rgb-dominant-v2;input=ceil24dp+-1px;band=upper-ceil12dp;"
            + "strideXY=2px;binRGB=16;topBins=8;candidate=first-real-pixel;"
            + "neighbor=maxChannel<=24;EXTEND>=0.70;NATIVE<=0.50;otherwise=UNKNOWN;edgeVeto=none";

    public static Bundle analyze(int[] pixels, int width, int height, float density) {
        if (pixels == null || width <= 0 || height < 2 || !Float.isFinite(density) || density <= 0
                || (long) width * height != pixels.length || Math.abs(height - Math.ceil(24.0 * density)) > 1)
            return result("UNKNOWN", "invalid_geometry", 0, 0);
        int rows = Math.min(height, Math.max(1, (int) Math.ceil(12.0 * density)));
        for (int y = 0; y < rows; y++) for (int x = 0; x < width; x++) {
            if ((pixels[y * width + x] >>> 24) != 255)
                return result("UNKNOWN", "non_opaque_pixels", 0, 0);
        }
        int[] counts = new int[4096], representatives = new int[4096];
        int analyzed = 0;
        for (int y = 0; y < rows; y += 2) for (int x = 0; x < width; x += 2) {
            int color = pixels[y * width + x];
            int bin = ((color >>> 12) & 0xf00) | ((color >>> 8) & 0xf0) | ((color >>> 4) & 0xf);
            if (counts[bin]++ == 0) representatives[bin] = color;
            analyzed++;
        }
        int best = 0;
        for (int candidate = 0; candidate < 8; candidate++) {
            int bin = -1;
            for (int i = 0; i < counts.length; i++)
                if (counts[i] > 0 && (bin < 0 || counts[i] > counts[bin])) bin = i;
            if (bin < 0) break;
            counts[bin] = 0;
            int matching = 0, color = representatives[bin];
            for (int y = 0; y < rows; y += 2) for (int x = 0; x < width; x += 2)
                if (near(pixels[y * width + x], color)) matching++;
            best = Math.max(best, matching);
        }
        double ratio = best / (double) analyzed;
        if (ratio >= 0.70) return result("EXTEND", "dominant_color", ratio, analyzed);
        if (ratio <= 0.50) return result("NATIVE", "no_dominant_color", ratio, analyzed);
        return result("UNKNOWN", "ambiguous_dominance", ratio, analyzed);
    }

    private static boolean near(int a, int b) {
        for (int shift = 0; shift <= 16; shift += 8)
            if (Math.abs(((a >>> shift) & 255) - ((b >>> shift) & 255)) > 24) return false;
        return true;
    }

    private static Bundle result(String decision, String reason, double ratio, int analyzed) {
        Bundle result = new Bundle();
        result.putString("decision", decision);
        result.putString("reason", reason);
        result.putDouble("dominantRatio", ratio);
        result.putInt("analyzedPixels", analyzed);
        result.putString("algorithm", ALGORITHM);
        return result;
    }
}
