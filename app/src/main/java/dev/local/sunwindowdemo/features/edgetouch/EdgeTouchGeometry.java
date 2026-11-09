package dev.local.sunwindowdemo.features.edgetouch;

import java.util.ArrayList;

/** Display-coordinate edge strips; subtract controls rather than putting a large window over them. */
public final class EdgeTouchGeometry {
    private EdgeTouchGeometry() {}
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    public static int[] build(int width, int height, int topInset, int bottomInset, float density,
            boolean left, boolean right, int leftWidthDp, int rightWidthDp,
            int heightPercent, int positionPercent, int[] protectedRects) {
        if (width < 5 || height < 2 || !Float.isFinite(density) || density <= 0
                || protectedRects == null || protectedRects.length % 4 != 0 || protectedRects.length > 64)
            return new int[0];
        int safeTop = clamp(topInset, 0, height - 1);
        int safeBottom = clamp(height - Math.max(0, bottomInset), safeTop + 1, height);
        int available = safeBottom - safeTop;
        int stripHeight = Math.max(1, Math.round(available * clamp(heightPercent, 20, 100) / 100f));
        int top = safeTop + Math.round((available - stripHeight) * clamp(positionPercent, 0, 100) / 100f);
        ArrayList<Integer> result = new ArrayList<>();
        if (left) append(result, 0, clamp(Math.round(clamp(leftWidthDp, 1, 48) * density), 1, width / 5), top, top + stripHeight, protectedRects);
        if (right) append(result, width - clamp(Math.round(clamp(rightWidthDp, 1, 48) * density), 1, width / 5), width, top, top + stripHeight, protectedRects);
        int[] rectangles = new int[result.size()];
        for (int i = 0; i < rectangles.length; i++) rectangles[i] = result.get(i);
        return rectangles;
    }

    private static void append(ArrayList<Integer> output, int left, int right, int top, int bottom, int[] protectedRects) {
        ArrayList<int[]> segments = new ArrayList<>();
        segments.add(new int[]{top, bottom});
        for (int i = 0; i < protectedRects.length; i += 4) {
            if (protectedRects[i] >= right || protectedRects[i + 2] <= left
                    || protectedRects[i] >= protectedRects[i + 2] || protectedRects[i + 1] >= protectedRects[i + 3]) continue;
            ArrayList<int[]> next = new ArrayList<>();
            for (int[] segment : segments) {
                int cutTop = Math.max(segment[0], protectedRects[i + 1]);
                int cutBottom = Math.min(segment[1], protectedRects[i + 3]);
                if (cutTop >= cutBottom) next.add(segment);
                else {
                    if (segment[0] < cutTop) next.add(new int[]{segment[0], cutTop});
                    if (cutBottom < segment[1]) next.add(new int[]{cutBottom, segment[1]});
                }
            }
            segments = next;
        }
        for (int[] segment : segments) {
            output.add(left); output.add(segment[0]); output.add(right); output.add(segment[1]);
        }
    }
}
