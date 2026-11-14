package dev.nativecomplements.navdemo;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorSpace;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DemoCaptureColorTest {
    @Test public void p3UsesColorConversionRatherThanByteRelabeling() {
        ColorSpace p3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3);
        Bitmap source = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888, true, p3);
        long color = Color.pack(0.65f, 0.45f, 0.25f, 1f, p3);
        source.eraseColor(color);
        Bitmap converted = DemoCaptureColor.toSrgb(source);
        try {
            float[] expected = ColorSpace.connect(p3, ColorSpace.get(ColorSpace.Named.SRGB)).transform(0.65f, 0.45f, 0.25f);
            int actual = converted.getPixel(0, 0);
            assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), converted.getColorSpace());
            assertEquals(Math.round(expected[0] * 255), Color.red(actual), 3);
            assertEquals(Math.round(expected[1] * 255), Color.green(actual), 3);
            assertEquals(Math.round(expected[2] * 255), Color.blue(actual), 3);
            assertTrue(Math.abs(Color.red(actual) - Math.round(0.65f * 255)) > 3);
            assertFalse(source.isRecycled());
        } finally { converted.recycle(); source.recycle(); }
    }

    @Test public void srgbBlackIsLegalAndSourceRemainsOwnedByCaller() {
        Bitmap source = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.BLACK);
        Bitmap converted = DemoCaptureColor.toSrgb(source);
        try {
            assertEquals(2, converted.getWidth());
            assertEquals(3, converted.getHeight());
            assertEquals(Color.BLACK, converted.getPixel(0, 0));
            assertFalse(source.isRecycled());
        } finally { converted.recycle(); source.recycle(); }
    }

    @Test public void nullAndExtendedRangeAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> DemoCaptureColor.toSrgb(null));
        Bitmap source = Bitmap.createBitmap(1, 1, Bitmap.Config.RGBA_F16, true,
                ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB));
        try { assertThrows(IllegalArgumentException.class, () -> DemoCaptureColor.toSrgb(source)); }
        finally { source.recycle(); }
    }
}
