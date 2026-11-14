package dev.local.sunwindowdemo.features.iconlayers;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.VectorDrawable;

/** A live source in a square reference viewport, fitted by visible height, not longest edge. */
public final class StatusIconDrawable extends DrawableWrapper implements Animatable {
    private final int referenceSize;
    private final int viewportWidth;
    private final int sourceWidth;
    private final int sourceHeight;
    private final RectF content;
    private boolean changingSourceBounds;

    /** Native StatusBarIconView may subsequently scale this reference viewport via mIconScale. */
    public static Drawable wrap(Drawable source, int referenceSize) {
        if (source == null || referenceSize <= 0) return source;
        if (source instanceof StatusIconDrawable
                && ((StatusIconDrawable) source).referenceSize == referenceSize) return source;
        // Unwrap only our own viewport; do not flatten native containers or animation frames.
        if (source instanceof StatusIconDrawable) source = ((StatusIconDrawable) source).getDrawable();
        return new StatusIconDrawable(source, referenceSize, referenceSize);
    }

    /** System slots retain native intrinsic dimensions, and therefore native wrap-content spacing. */
    public static Drawable wrapSystem(Drawable source) {
        if(source==null || source instanceof StatusIconDrawable)return source;
        if(source.getIntrinsicWidth()<=0 || source.getIntrinsicHeight()<=0)return source;
        return new StatusIconDrawable(source,source.getIntrinsicHeight(),source.getIntrinsicWidth());
    }

    private StatusIconDrawable(Drawable source, int referenceSize, int viewportWidth) {
        super(null);
        // DrawableWrapper.setDrawable applies wrapper state to its child. Seed that state first.
        setState(source.getState());
        setLevel(source.getLevel());
        setVisible(source.isVisible(), false);
        setLayoutDirection(source.getLayoutDirection());
        setBounds(source.getBounds());
        setDrawable(source);
        this.referenceSize = referenceSize;
        this.viewportWidth = viewportWidth;
        sourceWidth = source.getIntrinsicWidth() > 0 ? source.getIntrinsicWidth() : referenceSize;
        sourceHeight = source.getIntrinsicHeight() > 0 ? source.getIntrinsicHeight() : referenceSize;
        content = measure(source, sourceWidth, sourceHeight);
    }

    /** Visible fraction of intrinsic height; conservative 1 for unknown/dynamic/empty sources. */
    public static float visibleHeightFraction(Drawable source) {
        if (source == null) return 1f;
        int width = source.getIntrinsicWidth() > 0 ? source.getIntrinsicWidth() : 128;
        int height = source.getIntrinsicHeight() > 0 ? source.getIntrinsicHeight() : 128;
        return measure(source, width, height).height() / height;
    }

    private static boolean isStatic(Drawable source) {
        if (source instanceof Animatable || source instanceof android.graphics.drawable.ScaleDrawable
                || source instanceof android.graphics.drawable.ClipDrawable
                || source instanceof android.graphics.drawable.RotateDrawable) return false;
        if (source instanceof DrawableWrapper) return isStatic(((DrawableWrapper) source).getDrawable());
        return source instanceof BitmapDrawable || source instanceof VectorDrawable || source instanceof ColorDrawable;
    }

    private static void clearAppearance(Drawable probe) {
        probe.setTintList(null);
        probe.clearColorFilter();
        probe.setAlpha(255);
        if (probe instanceof DrawableWrapper) clearAppearance(((DrawableWrapper) probe).getDrawable());
    }

    private static RectF measure(Drawable source, int width, int height) {
        RectF full = new RectF(0, 0, width, height);
        // ponytail: unknown/state/level containers keep their full viewport; measuring a frame
        // would crop later animation states. Only known static primitives are sampled once.
        if (!isStatic(source)) return full;
        Bitmap sample = null;
        try {
            Drawable.ConstantState state = source.getConstantState();
            if (state == null) return full;
            Drawable probe = state.newDrawable().mutate();
            if (probe == source) return full;
            clearAppearance(probe);
            float ratio = Math.min(1f, 128f / Math.max(width, height));
            int w = Math.max(1, Math.round(width * ratio));
            int h = Math.max(1, Math.round(height * ratio));
            sample = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            probe.setBounds(0, 0, w, h);
            probe.draw(new Canvas(sample));
            int left = w, top = h, right = 0, bottom = 0;
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                if (Color.alpha(sample.getPixel(x, y)) == 0) continue;
                left = Math.min(left, x); top = Math.min(top, y);
                right = Math.max(right, x + 1); bottom = Math.max(bottom, y + 1);
            }
            if (right <= left || bottom <= top) return full;
            return new RectF(left * (float) width / w, top * (float) height / h,
                    right * (float) width / w, bottom * (float) height / h);
        } catch (RuntimeException ignored) {
            // Vendor drawables may require a hardware canvas; leave their native viewport intact.
            return full;
        } finally {
            if (sample != null) sample.recycle();
        }
    }

    // The base wrapper's ConstantState cannot reconstruct this viewport/measurement.
    @Override public ConstantState getConstantState() { return null; }

    @Override public int getIntrinsicWidth() { return viewportWidth; }
    @Override public int getIntrinsicHeight() { return referenceSize; }

    // Wrapper bounds describe the destination, not the source's native coordinate system.
    @Override protected void onBoundsChange(Rect bounds) { }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;
        Drawable source = getDrawable();
        Rect old = new Rect(source.getBounds());
        float scale = Math.min(bounds.height() / content.height(), bounds.width() / content.width());
        int saved = canvas.save();
        try {
            canvas.clipRect(bounds);
            canvas.translate(bounds.exactCenterX(), bounds.exactCenterY());
            canvas.scale(scale, scale);
            canvas.translate(-content.centerX(), -content.centerY());
            changingSourceBounds = true;
            source.setBounds(0, 0, sourceWidth, sourceHeight);
            changingSourceBounds = false;
            source.draw(canvas);
        } finally {
            // Bounds invalidations alone must not turn every draw into an endless redraw loop.
            changingSourceBounds = true;
            try { source.setBounds(old); }
            finally { changingSourceBounds = false; canvas.restoreToCount(saved); }
        }
    }

    @Override public void invalidateDrawable(Drawable who) {
        if (!changingSourceBounds) super.invalidateDrawable(who);
    }

    private Animatable animation() {
        Drawable source = getDrawable();
        while (!(source instanceof Animatable) && source instanceof DrawableWrapper)
            source = ((DrawableWrapper) source).getDrawable();
        return source instanceof Animatable ? (Animatable) source : null;
    }
    @Override public void start() {
        Animatable animation = animation();
        if (animation != null) animation.start();
    }
    @Override public void stop() {
        Animatable animation = animation();
        if (animation != null) animation.stop();
    }
    @Override public boolean isRunning() {
        Animatable animation = animation();
        return animation != null && animation.isRunning();
    }
}
