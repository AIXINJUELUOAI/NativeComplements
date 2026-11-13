package dev.local.sunwindowdemo.features.navbar;

import android.graphics.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE)
public class NavbarTaskbarTest {
    private static final class RecordingCanvas extends Canvas {
        RectF rectangle;
        @Override public int getWidth() { return 100; }
        @Override public int getHeight() { return 200; }
        @Override public void drawRect(float left, float top, float right, float bottom, Paint paint) {
            rectangle = new RectF(left, top, right, bottom);
        }
    }
    @Test public void bandCoversOnlyActualBottomNavigationHeight() {
        RecordingCanvas canvas = new RecordingCanvas();
        NavbarTaskbarHost.drawBand(canvas, new Paint(), 20);
        assertEquals(new RectF(0, 180, 100, 200), canvas.rectangle);
    }
    @Test public void absentBandDoesNotPaintAndLargeBandIsClamped() {
        RecordingCanvas canvas = new RecordingCanvas();
        NavbarTaskbarHost.drawBand(canvas, new Paint(), 0);
        assertNull(canvas.rectangle);
        NavbarTaskbarHost.drawBand(canvas, new Paint(), 300);
        assertEquals(new RectF(0, 0, 100, 200), canvas.rectangle);
    }
    @Test public void colorTintIsOpaqueAndNativePaintCanBeRestored() {
        Paint paint = new Paint();
        paint.setColor(0xff123456);
        paint.setAlpha(64);
        int originalAlpha = paint.getAlpha();
        int originalColor = paint.getColor();
        Paint original = new Paint(paint);
        NavbarTaskbarHost.tint(paint, NavbarStore.COLOR, Color.WHITE);
        assertEquals(Color.WHITE, paint.getColor());
        assertEquals(255, paint.getAlpha());
        NavbarTaskbarHost.restore(paint, original, originalColor, originalAlpha);
        assertEquals(64, paint.getAlpha());
        assertEquals(0x123456, paint.getColor() & 0xffffff);
    }
    @Test public void floatingTintRemovesNativeBackgroundAlpha() {
        Paint paint = new Paint();
        paint.setColor(0xff123456);
        NavbarTaskbarHost.tint(paint, NavbarStore.FLOATING, Color.WHITE);
        assertEquals(0, paint.getAlpha());
        assertEquals(0x123456, paint.getColor() & 0xffffff);
    }
}
