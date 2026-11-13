package dev.local.sunwindowdemo.features.navbar;

/** Small quantized histogram avoids treating adjacent gradient colors as unrelated. */
public final class NavbarColors {
    private NavbarColors() {}
    public static int background(int[] pixels) {
        if (pixels.length == 0) return 0;
        int left = pixels[0], right = pixels[pixels.length - 1];
        if ((left >>> 24) == 255 && (right >>> 24) == 255 && similar(left, right, 10)) {
            int red = (((left >>> 16) & 255) + ((right >>> 16) & 255)) / 2;
            int green = (((left >>> 8) & 255) + ((right >>> 8) & 255)) / 2;
            int blue = ((left & 255) + (right & 255)) / 2;
            return 0xff000000 | (red << 16) | (green << 8) | blue;
        }
        int[] counts = new int[4096];
        int winner = 0, valid = 0;
        long red = 0, green = 0, blue = 0;
        for (int color : pixels) {
            if ((color >>> 24) != 255) continue;
            int r = (color >>> 16) & 255, g = (color >>> 8) & 255, b = color & 255;
            int key = ((r >>> 4) << 8) | ((g >>> 4) << 4) | (b >>> 4);
            counts[key]++;
            if (counts[key] > counts[winner]) winner = key;
            red += r; green += g; blue += b; valid++;
        }
        if (valid < pixels.length * 3 / 4 || valid == 0) return 0;
        if (counts[winner] * 100 < valid * 35) {
            return (red * 299 + green * 587 + blue * 114) / valid < 128000 ? 0xff000000 : 0xffffffff;
        }
        red = 0; green = 0; blue = 0;
        for (int color : pixels) {
            int r = (color >>> 16) & 255, g = (color >>> 8) & 255, b = color & 255;
            int key = ((r >>> 4) << 8) | ((g >>> 4) << 4) | (b >>> 4);
            if ((color >>> 24) == 255 && key == winner) { red += r; green += g; blue += b; }
        }
        return 0xff000000 | ((int) (red / counts[winner]) << 16)
                | ((int) (green / counts[winner]) << 8) | (int) (blue / counts[winner]);
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
        boolean needsConfirmation() { return pending != 0; }
        void reset() { applied = 0; pending = 0; }
    }
}
