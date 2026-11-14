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
        int alpha;
        @Override public int getWidth() { return 100; }
        @Override public int getHeight() { return 200; }
        @Override public void drawRect(float left, float top, float right, float bottom, Paint paint) {
            rectangle = new RectF(left, top, right, bottom);
            alpha = paint.getAlpha();
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
    @Test public void colorBandRemainsVisibleWhenPhoneTaskbarBackgroundIsStashed() {
        for (int nativeAlpha : new int[]{0, 64, 255}) {
            Paint nativeBackground = new Paint();
            nativeBackground.setAlpha(nativeAlpha);
            NavbarTaskbarHost.tint(nativeBackground, NavbarStore.COLOR, Color.WHITE);
            assertEquals(nativeAlpha, nativeBackground.getAlpha());
            Paint band = new Paint();
            band.setColor(Color.WHITE);
            band.setAlpha(nativeAlpha); // A reused paint must not carry stash/background alpha into the band.
            RecordingCanvas canvas = new RecordingCanvas();
            NavbarTaskbarHost.drawBand(canvas, band, 20);
            assertEquals(new RectF(0, 180, 100, 200), canvas.rectangle);
            assertEquals(255, canvas.alpha);
        }
    }
    @Test public void colorTintPreservesNativeAlphaAndNativePaintCanBeRestored() {
        for (int alpha : new int[]{0, 64, 255}) {
            Paint paint = new Paint();
            paint.setColor(0xff123456);
            paint.setAlpha(alpha);
            int originalColor = paint.getColor();
            Paint original = new Paint(paint);
            NavbarTaskbarHost.tint(paint, NavbarStore.COLOR, Color.WHITE);
            assertEquals(0xffffff, paint.getColor() & 0xffffff);
            assertEquals(alpha, paint.getAlpha());
            NavbarTaskbarHost.restore(paint, original, originalColor, alpha);
            assertEquals(alpha, paint.getAlpha());
            assertEquals(0x123456, paint.getColor() & 0xffffff);
        }
    }
    @Test public void floatingThenColorUsesRestoredNativeAnimationAlpha() {
        Paint paint = new Paint();
        paint.setColor(0xff123456);
        paint.setAlpha(64);
        int originalColor = paint.getColor();
        Paint original = new Paint(paint);
        NavbarTaskbarHost.tint(paint, NavbarStore.FLOATING, Color.WHITE);
        assertEquals(0, paint.getAlpha());
        NavbarTaskbarHost.restore(paint, original, originalColor, 64);
        NavbarTaskbarHost.tint(paint, NavbarStore.COLOR, Color.WHITE);
        assertEquals(64, paint.getAlpha());
    }
    @Test public void floatingTintRemovesNativeBackgroundAlpha() {
        Paint paint = new Paint();
        paint.setColor(0xff123456);
        NavbarTaskbarHost.tint(paint, NavbarStore.FLOATING, Color.WHITE);
        assertEquals(0, paint.getAlpha());
        assertEquals(0x123456, paint.getColor() & 0xffffff);
    }
}
