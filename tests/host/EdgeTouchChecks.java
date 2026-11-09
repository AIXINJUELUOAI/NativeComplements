package dev.local.sunwindowdemo;

import dev.local.sunwindowdemo.features.edgetouch.EdgeTouchGeometry;
import java.util.Arrays;

public class EdgeTouchChecks {
    private static void assertArrayEquals(int[] expected,int[] actual){
        if(!Arrays.equals(expected,actual))throw new AssertionError(Arrays.toString(actual));
    }
    public static void main(String[] args) {
        int[] rects = EdgeTouchGeometry.build(400, 800, 24, 24, 1, true, true, 16, 8, 100, 50,
                new int[]{0, 300, 60, 404, 380, 500, 400, 550});
        assertArrayEquals(new int[]{0, 24, 16, 300, 0, 404, 16, 776,
                392, 24, 400, 500, 392, 550, 400, 776}, rects);
        assertArrayEquals(new int[0], EdgeTouchGeometry.build(400, 800, 24, 24, 1,
                false, false, 8, 8, 80, 50, new int[0]));
        rects = EdgeTouchGeometry.build(800, 400, 24, 24, 2, true, false, 48, 8, 50, 100, new int[0]);
        assertArrayEquals(new int[]{0, 200, 96, 376}, rects);
        assertArrayEquals(new int[0], EdgeTouchGeometry.build(400, 800, 24, 24, 1,
                true, false, 8, 8, 100, 0, new int[]{0, 0, 20, 800}));
        System.out.println("PASS: edge strips, disabled regions, rotation geometry and floating-control protection");
    }
}
