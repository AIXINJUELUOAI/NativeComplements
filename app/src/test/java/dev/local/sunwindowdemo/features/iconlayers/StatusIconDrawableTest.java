package dev.local.sunwindowdemo.features.iconlayers;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StatusIconDrawableTest {
    private static BitmapDrawable image(int width, int height, Rect content) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(); paint.setColor(Color.WHITE);
        new Canvas(bitmap).drawRect(content, paint);
        BitmapDrawable result = new BitmapDrawable(RuntimeEnvironment.getApplication().getResources(), bitmap);
        result.setTargetDensity(bitmap.getDensity());
        return result;
    }

    private static Bitmap draw(Drawable icon, Rect bounds) {
        Bitmap output = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        icon.setBounds(bounds);
        icon.draw(new Canvas(output));
        return output;
    }

    @Test public void systemWrapperPreservesNativeIntrinsicSpacing() {
        Drawable source=image(32,24,new Rect(8,4,24,20));
        Drawable icon=StatusIconDrawable.wrapSystem(source);
        assertEquals(source.getIntrinsicWidth(),icon.getIntrinsicWidth());
        assertEquals(source.getIntrinsicHeight(),icon.getIntrinsicHeight());
        assertSame(icon,StatusIconDrawable.wrapSystem(icon));
        Rect visible=IconImages.contentBounds(draw(icon,new Rect(0,0,32,24)));
        assertEquals(16,visible.centerX());assertEquals(12,visible.centerY());
        assertEquals(4,visible.left,1);assertEquals(28,visible.right,1);
        assertEquals(0,visible.top);assertEquals(24,visible.bottom);
    }

    @Test public void paddedTallContentFillsHeightWithoutBecomingSquare() {
        Drawable source = image(64, 64, new Rect(24, 16, 40, 48));
        Drawable icon = StatusIconDrawable.wrap(source, 24);
        assertEquals(24, icon.getIntrinsicWidth());
        assertEquals(24, icon.getIntrinsicHeight());
        assertEquals(.5f, StatusIconDrawable.visibleHeightFraction(source), .001f);
        assertEquals(new Rect(26, 30, 38, 54), IconImages.contentBounds(draw(icon, new Rect(20, 30, 44, 54))));
    }

    @Test public void wideContentIsLimitedBySlotWidthNotForcedToTargetHeight() {
        Drawable icon = StatusIconDrawable.wrap(image(64, 64, new Rect(16, 24, 48, 40)), 24);
        assertEquals(new Rect(20, 36, 44, 48), IconImages.contentBounds(draw(icon, new Rect(20, 30, 44, 54))));
        Rect enlarged = IconImages.contentBounds(draw(icon, new Rect(10, 13, 58, 61)));
        assertEquals(10, enlarged.left); assertEquals(58, enlarged.right); // Width remains slot-limited.
        assertEquals(34f, enlarged.exactCenterX(), .01f);
        assertEquals(37f, enlarged.exactCenterY(), .01f);
        // Bilinear sampling may contribute one alpha pixel beyond either geometric edge.
        assertTrue(Math.abs(enlarged.top - 25) <= 1);
        assertTrue(Math.abs(enlarged.bottom - 49) <= 1);
    }

    @Test public void nestedNativeWrapperAndTransparentTintAreMeasuredWithoutChangingSource() {
        BitmapDrawable bitmap = image(64, 64, new Rect(24, 16, 40, 48));
        Drawable source = new InsetDrawable(bitmap, 8);
        source.setTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        source.setAlpha(0);
        Rect old = new Rect(2, 3, 17, 23); source.setBounds(old);
        Drawable icon = StatusIconDrawable.wrap(source, 24);
        assertEquals(old, source.getBounds());
        assertEquals(0, source.getAlpha());
        icon.setAlpha(255); icon.setTint(Color.RED);
        Bitmap result = draw(icon, new Rect(20, 30, 44, 54));
        Rect visible = IconImages.contentBounds(result);
        assertEquals(24, visible.height());
        assertTrue(visible.width() >= 11 && visible.width() <= 13);
        assertEquals(Color.RED, result.getPixel(32, 42));
        assertEquals(old, source.getBounds());
    }

    @Test public void emptyAndUnknownDimensionsFallBackSafely() {
        Drawable icon = StatusIconDrawable.wrap(image(32, 32, new Rect()), 24);
        assertTrue(IconImages.contentBounds(draw(icon, new Rect(0, 0, 24, 24))).isEmpty());
        Drawable color = StatusIconDrawable.wrap(new android.graphics.drawable.ColorDrawable(Color.WHITE), 24);
        assertEquals(new Rect(0, 0, 24, 24), IconImages.contentBounds(draw(color, new Rect(0, 0, 24, 24))));
        assertNull(StatusIconDrawable.wrap(null, 24));
        assertSame(icon, StatusIconDrawable.wrap(icon, 24));
    }

    @Test public void liveSourceRetainsStateLevelVisibilityAndAnimation() {
        LiveDrawable source = new LiveDrawable();
        source.setState(new int[]{android.R.attr.state_pressed}); source.setLevel(4321);
        source.setVisible(false, false); source.setBounds(4, 5, 18, 19);
        Drawable icon = StatusIconDrawable.wrap(source, 24);
        assertEquals(0, source.draws); // No sampling or flattening of animated drawables.
        assertArrayEquals(new int[]{android.R.attr.state_pressed}, source.getState());
        assertEquals(4321, icon.getLevel()); assertFalse(icon.isVisible());
        icon.setState(new int[]{android.R.attr.state_selected}); icon.setLevel(1234);
        icon.setVisible(true, true);
        assertArrayEquals(icon.getState(), source.getState());
        assertEquals(1234, source.getLevel()); assertTrue(source.isVisible());
        ((Animatable) icon).start(); assertTrue(((Animatable) icon).isRunning());
        ((Animatable) icon).stop(); assertFalse(source.running);
        draw(icon, new Rect(0, 0, 24, 24));
        assertEquals(new Rect(4, 5, 18, 19), source.getBounds());
        assertEquals(1, source.draws);
    }

    @Test public void realAnimationDrawableIsNotFlattened() {
        AnimationDrawable source = new AnimationDrawable();
        source.addFrame(image(32, 32, new Rect(8, 8, 24, 24)), 100);
        source.addFrame(image(32, 32, new Rect(0, 0, 32, 32)), 100);
        Drawable icon = StatusIconDrawable.wrap(source, 24);
        ((Animatable) icon).start(); assertTrue(source.isRunning());
        source.selectDrawable(1);
        assertEquals(new Rect(0, 0, 24, 24), IconImages.contentBounds(draw(icon, new Rect(0, 0, 24, 24))));
        ((Animatable) icon).stop(); assertFalse(source.isRunning());
    }

    @Test public void callbacksForwardAsWrapperAndRestoreBoundsEvenWhenDrawThrows() {
        LiveDrawable source = new LiveDrawable();
        Drawable icon = StatusIconDrawable.wrap(source, 24);
        int[] calls = new int[3]; Runnable task = () -> {};
        icon.setCallback(new Drawable.Callback() {
            @Override public void invalidateDrawable(Drawable who) { assertSame(icon, who); calls[0]++; }
            @Override public void scheduleDrawable(Drawable who, Runnable what, long when) {
                assertSame(icon, who); assertSame(task, what); assertEquals(123L, when); calls[1]++;
            }
            @Override public void unscheduleDrawable(Drawable who, Runnable what) {
                assertSame(icon, who); assertSame(task, what); calls[2]++;
            }
        });
        source.invalidateSelf(); source.scheduleSelf(task, 123); source.unscheduleSelf(task);
        assertArrayEquals(new int[]{1, 1, 1}, calls);
        source.setBounds(1, 2, 9, 10); source.fail = true;
        Canvas canvas = new Canvas(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888));
        icon.setBounds(0, 0, 24, 24); int saves = canvas.getSaveCount();
        assertThrows(IllegalStateException.class, () -> icon.draw(canvas));
        assertEquals(new Rect(1, 2, 9, 10), source.getBounds());
        assertEquals(saves, canvas.getSaveCount());
    }

    private static class LiveDrawable extends Drawable implements Animatable {
        int draws; boolean running; boolean fail;
        @Override public void draw(Canvas canvas) {
            draws++;
            if (fail) throw new IllegalStateException("draw failed");
            Paint paint = new Paint(); paint.setColor(Color.WHITE); canvas.drawRect(getBounds(), paint);
        }
        @Override public int getIntrinsicWidth() { return 32; }
        @Override public int getIntrinsicHeight() { return 32; }
        @Override public boolean isStateful() { return true; }
        @Override protected boolean onStateChange(int[] state) { return true; }
        @Override protected boolean onLevelChange(int level) { return true; }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter colorFilter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public void start() { running = true; }
        @Override public void stop() { running = false; }
        @Override public boolean isRunning() { return running; }
    }
}
