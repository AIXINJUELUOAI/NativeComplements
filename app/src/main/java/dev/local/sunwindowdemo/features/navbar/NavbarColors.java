package dev.local.sunwindowdemo.features.navbar;

import java.util.Arrays;

/** ColorOS-style per-channel trimmed integer mean and population variance. */
public final class NavbarColors {
    private NavbarColors() {}

    /** 25 is the researched side-limit constructor value, not a verified runtime configuration. */
    public static int background(int[] pixels) { return background(pixels, 25); }

    /**
     * Returns 0 for an unavailable/rejected sample; never substitutes a guessed black/white color.
     * The target capture lacks vendor sample/layer alpha separation: only opaque input is safe.
     * Threshold units are squared 8-bit channel values, compared with the integer RGB variance mean.
     * The caller owns ROI selection, fallback capture and any retention of an earlier valid color.
     */
    public static int background(int[] pixels, int threshold) {
        if (pixels == null || pixels.length == 0 || threshold < 0) return 0;
        for (int color : pixels) if ((color >>> 24) != 255) return 0;
        int trim = pixels.length / 10, end = pixels.length - trim, count = end - trim;
        int[] channel = new int[pixels.length];
        int result = 0xff000000;
        long variance = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            for (int index = 0; index < pixels.length; index++) channel[index] = (pixels[index] >>> shift) & 255;
            Arrays.sort(channel);
            long sum = 0, squared = 0;
            for (int index = trim; index < end; index++) sum += channel[index];
            int mean = (int) (sum / count);
            for (int index = trim; index < end; index++) {
                int difference = channel[index] - mean;
                squared += difference * difference;
            }
            variance += squared / count;
            result |= mean << shift;
        }
        return variance / 3 <= threshold ? result : 0;
    }

    private static boolean similar(int first, int second, int tolerance) {
        return Math.abs(((first >>> 16) & 255) - ((second >>> 16) & 255)) <= tolerance
                && Math.abs(((first >>> 8) & 255) - ((second >>> 8) & 255)) <= tolerance
                && Math.abs((first & 255) - (second & 255)) <= tolerance;
    }

    /** Require a repeated candidate, while retaining the current color during scrolling. */
    static final class Stability {
        private int applied, pending;
        int choose(int candidate) {
            if (candidate == 0) return applied;
            if (applied == 0) { applied = candidate; return applied; }
            if (similar(applied, candidate, 16)) { pending = 0; return applied; }
            if (pending != 0 && similar(pending, candidate, 10)) {
                applied = candidate;
                pending = 0;
            } else pending = candidate;
            return applied;
        }
        /** Starts a page from its last confirmed color; a different sample then needs confirmation. */
        void seed(int color) { applied = color; pending = 0; }
        int applied() { return applied; }
        boolean needsConfirmation() { return pending != 0; }
        void reset() { applied = 0; pending = 0; }
    }
}
