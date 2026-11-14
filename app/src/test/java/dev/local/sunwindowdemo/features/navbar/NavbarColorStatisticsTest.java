package dev.local.sunwindowdemo.features.navbar;

import java.util.Arrays;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure JVM checks; no Android capture, device or vendor alpha assumptions. */
public class NavbarColorStatisticsTest {
    @Test public void varianceThresholdIsInclusiveAndUsesSquaredChannelUnits() {
        int[] gray = {0xff000000, 0xff0a0a0a};
        assertEquals(0, NavbarColors.background(gray, 24));
        assertEquals(0xff050505, NavbarColors.background(gray, 25));
        int[] redOnly = {0xff000000, 0xff0c0000};
        assertEquals(0, NavbarColors.background(redOnly, 11));
        assertEquals(0xff060000, NavbarColors.background(redOnly, 12));
        // Per-channel variances 1,1,0 have integer average 0.
        assertEquals(0xff010100, NavbarColors.background(new int[]{0xff000000, 0xff020200}, 0));
    }

    @Test public void integerMeanPrecedesIntegerPopulationVariance() {
        // mean=0, variance=(0+0+1+4)/4=1, not floor(float variance)=0.
        int[] pixels = {0xff000000, 0xff000000, 0xff010101, 0xff020202};
        assertEquals(0, NavbarColors.background(pixels, 0));
        assertEquals(0xff000000, NavbarColors.background(pixels, 1));
    }

    @Test public void trimStartsAtTenAndRemovesBothExtremesPerChannel() {
        int[] nine = new int[9];
        Arrays.fill(nine, 0xff646464);
        nine[0] = 0xff000000;
        nine[8] = 0xffffffff;
        assertEquals(0, NavbarColors.background(nine, 0));
        int[] ten = Arrays.copyOf(nine, 10);
        ten[9] = 0xff646464;
        assertEquals(0xff646464, NavbarColors.background(ten, 0));
        // Each channel trims a different pixel; no single luminance-ranked subset works.
        Arrays.fill(ten, 0xff646464);
        ten[0] = 0xffff6464;
        ten[1] = 0xff64ff64;
        ten[2] = 0xff6464ff;
        assertEquals(0xff646464, NavbarColors.background(ten, 0));
    }

    @Test public void invalidAndNonOpaqueSamplesAreEmptyWithoutInventedColor() {
        assertEquals(0, NavbarColors.background(null));
        assertEquals(0, NavbarColors.background(new int[0]));
        assertEquals(0, NavbarColors.background(new int[]{0xff123456}, -1));
        assertEquals(0, NavbarColors.background(new int[]{0x00123456}));
        assertEquals(0, NavbarColors.background(new int[]{0xfe123456, 0xff123456}));
        int[] mostlyOpaque = new int[20];
        Arrays.fill(mostlyOpaque, 0xff123456);
        mostlyOpaque[0] = 0x00123456; // Reject even if trimming could hide this alpha.
        assertEquals(0, NavbarColors.background(mostlyOpaque));
        assertEquals(0xff000000, NavbarColors.background(new int[]{0xff000000}, 0));
        assertEquals(0xffffffff, NavbarColors.background(new int[]{0xffffffff}, 0));
    }

    @Test public void squaredSumsDoNotOverflowAndInputIsNotReordered() {
        int[] pixels = new int[200_000];
        Arrays.fill(pixels, 0, pixels.length / 2, 0xff000000);
        Arrays.fill(pixels, pixels.length / 2, pixels.length, 0xffffffff);
        int[] original = pixels.clone();
        assertEquals(0, NavbarColors.background(pixels, 16255));
        assertEquals(0xff7f7f7f, NavbarColors.background(pixels, 16256));
        assertArrayEquals(original, pixels);
    }

    @Test public void sortedImplementationMatchesIndependentHistogramModel() {
        Random random = new Random(0x434f4c4f52L);
        for (int size : new int[]{1, 2, 9, 10, 19, 20, 48, 200, 501}) {
            for (int attempt = 0; attempt < 30; attempt++) {
                int[] pixels = new int[size];
                for (int i = 0; i < size; i++) pixels[i] = 0xff000000 | random.nextInt(1 << 24);
                int threshold = random.nextInt(65026);
                assertEquals("size=" + size + ", threshold=" + threshold,
                        histogramModel(pixels, threshold), NavbarColors.background(pixels, threshold));
            }
        }
    }

    private static int histogramModel(int[] pixels, int threshold) {
        long totalVariance = 0;
        int result = 0xff000000, count = pixels.length - 2 * (pixels.length / 10);
        for (int shift = 0; shift <= 16; shift += 8) {
            int[] counts = new int[256];
            for (int pixel : pixels) counts[(pixel >>> shift) & 255]++;
            int discard = pixels.length / 10;
            for (int value = 0; value < 256 && discard > 0; value++) {
                int remove = Math.min(discard, counts[value]);
                counts[value] -= remove;
                discard -= remove;
            }
            discard = pixels.length / 10;
            for (int value = 255; value >= 0 && discard > 0; value--) {
                int remove = Math.min(discard, counts[value]);
                counts[value] -= remove;
                discard -= remove;
            }
            long sum = 0, squared = 0;
            for (int value = 0; value < 256; value++) sum += (long) value * counts[value];
            int mean = (int) (sum / count);
            for (int value = 0; value < 256; value++) squared += (long) (value - mean) * (value - mean) * counts[value];
            totalVariance += squared / count;
            result |= mean << shift;
        }
        return totalVariance / 3 <= threshold ? result : 0;
    }
}
