package dev.local.sunwindowdemo.features.navbar;

import android.graphics.Rect;
import android.view.SurfaceControl;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** One window-owned visual child; caller serializes access under the WM lock. No input surface. */
final class NavbarWindowLayer implements AutoCloseable {
    // Window BLAST buffer starts at 0; WindowStateAnimator.PRESERVED_SURFACE_LAYER is 1.
    // Remain within the window subtree rather than using a display-wide maximum layer.
    private static final int COLOR_LAYER = 2;
    private final Constructor<?> builder;
    private final Method name, parent, hidden, colorLayer, build, sameSurface;
    private final Method color, alpha, position, crop, layer, show, hide, reparent;
    /** Optional: buffer layer that stretches the app row above the boundary down over the navigation area. */
    private final Method setBuffer, setScale;
    private SurfaceControl owner, child;
    private boolean bufferChild;
    private int[] shownRow;
    private static volatile boolean stretchBroken;

    /** Resolve every hidden signature before allocating any surface. Fail closed if unavailable. */
    NavbarWindowLayer() throws ReflectiveOperationException {
        Class<?> b = Class.forName("android.view.SurfaceControl$Builder");
        builder = b.getDeclaredConstructor();
        builder.setAccessible(true);
        name = method(b, "setName", String.class);
        parent = method(b, "setParent", SurfaceControl.class);
        hidden = method(b, "setHidden", boolean.class);
        colorLayer = method(b, "setColorLayer");
        build = method(b, "build");
        sameSurface = method(SurfaceControl.class, "isSameSurface", SurfaceControl.class);
        Class<?> t = SurfaceControl.Transaction.class;
        t.getDeclaredConstructor();
        method(t, "apply");
        method(t, "close");
        color = method(t, "setColor", SurfaceControl.class, float[].class);
        alpha = method(t, "setAlpha", SurfaceControl.class, float.class);
        position = method(t, "setPosition", SurfaceControl.class, float.class, float.class);
        crop = method(t, "setWindowCrop", SurfaceControl.class, int.class, int.class);
        layer = method(t, "setLayer", SurfaceControl.class, int.class);
        show = method(t, "show", SurfaceControl.class);
        hide = method(t, "hide", SurfaceControl.class);
        reparent = method(t, "reparent", SurfaceControl.class, SurfaceControl.class);
        Method buffer = null, scaleMethod = null;
        try {
            buffer = method(t, "setBuffer", SurfaceControl.class, android.hardware.HardwareBuffer.class);
            scaleMethod = method(t, "setScale", SurfaceControl.class, float.class, float.class);
        } catch (ReflectiveOperationException unavailable) { buffer = null; scaleMethod = null; }
        setBuffer = buffer;
        setScale = scaleMethod;
    }

    private static Method method(Class<?> type, String name, Class<?>... args)
            throws ReflectiveOperationException {
        Method result = type.getDeclaredMethod(name, args);
        result.setAccessible(true);
        return result;
    }

    /** crop is the actual navigation rectangle in parent-surface coordinates, not display space. */
    void update(SurfaceControl nextParent, Rect rectangle, int argb)
            throws ReflectiveOperationException { update(nextParent, rectangle, argb, null); }

    /** row: opaque app pixels just above the boundary (any width), stretched to the rectangle; null = solid. */
    void update(SurfaceControl nextParent, Rect rectangle, int argb, int[] row)
            throws ReflectiveOperationException {
        boolean stretch = row != null && row.length > 0 && setBuffer != null && !stretchBroken;
        if (nextParent == null || !nextParent.isValid() || rectangle == null
                || rectangle.isEmpty() || rectangle.left < 0 || rectangle.top < 0
                || (argb >>> 24) == 0) {
            clear();
            return;
        }
        if (child != null && (!valid() || !((Boolean) sameSurface.invoke(owner, nextParent)) || bufferChild != stretch)) {
            clear();
        }
        if (child == null) {
            Object b = builder.newInstance();
            name.invoke(b, "NativeComplements-navbar");
            parent.invoke(b, nextParent);
            hidden.invoke(b, true);
            if (!stretch) colorLayer.invoke(b);
            child = (SurfaceControl) build.invoke(b);
            owner = nextParent;
            bufferChild = stretch;
            shownRow = null;
            if (child == null || !child.isValid()) {
                clear();
                throw new IllegalStateException("Invalid navbar color surface");
            }
        }
        // Refresh the borrowed wrapper without rebuilding a child of the same native surface.
        owner = nextParent;
        android.hardware.HardwareBuffer pending = null;
        try (SurfaceControl.Transaction tx = new SurfaceControl.Transaction()) {
            if (stretch) {
                if (shownRow == null || !java.util.Arrays.equals(shownRow, row)) {
                    pending = hardware(row);
                    setBuffer.invoke(tx, child, pending);
                    shownRow = row.clone();
                }
                alpha.invoke(tx, child, 1f);
                // Buffer is row.length x 1: scale it to the navigation rectangle (bilinear horizontally).
                setScale.invoke(tx, child, rectangle.width() / (float) row.length, (float) rectangle.height());
            } else {
                color.invoke(tx, child, new float[]{((argb >> 16) & 255) / 255f,
                        ((argb >> 8) & 255) / 255f, (argb & 255) / 255f});
                alpha.invoke(tx, child, (argb >>> 24) / 255f);
                // Crop starts at (0,0); using rectangle here would apply left/top twice.
                crop.invoke(tx, child, rectangle.width(), rectangle.height());
            }
            position.invoke(tx, child, (float) rectangle.left, (float) rectangle.top);
            layer.invoke(tx, child, COLOR_LAYER);
            show.invoke(tx, child);
            tx.apply();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (stretch) {
                // A device without a usable hardware row buffer keeps the solid fill from now on.
                stretchBroken = true;
                NavbarTrace.w("Stretched navigation fill unavailable; using solid colour", failure);
            }
            try { clear(); }
            catch (ReflectiveOperationException | RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        } finally {
            if (pending != null) pending.close();
        }
    }

    /** row.length x 1 GPU buffer; the transaction keeps its own reference, so the caller closes this one. */
    private static android.hardware.HardwareBuffer hardware(int[] row) {
        android.graphics.Bitmap cpu = android.graphics.Bitmap.createBitmap(row, row.length, 1, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Bitmap gpu = null;
        try {
            gpu = cpu.copy(android.graphics.Bitmap.Config.HARDWARE, false);
            if (gpu == null) throw new IllegalStateException("hardware bitmap unavailable");
            return gpu.getHardwareBuffer();
        } finally {
            cpu.recycle();
            if (gpu != null) gpu.recycle();
        }
    }

    boolean valid() {
        return owner != null && owner.isValid() && child != null && child.isValid();
    }

    boolean belongsTo(SurfaceControl surface) throws ReflectiveOperationException {
        return valid() && surface != null && surface.isValid()
                && (Boolean) sameSurface.invoke(owner, surface);
    }

    /** Detach before releasing. On transaction failure retain the handle for a later clear retry. */
    void clear() throws ReflectiveOperationException {
        if (child == null) { owner = null; return; }
        if (child.isValid()) {
            try (SurfaceControl.Transaction tx = new SurfaceControl.Transaction()) {
                hide.invoke(tx, child);
                // Transaction.remove releases immediately, even before apply can fail.
                reparent.invoke(tx, child, (Object) null);
                tx.apply();
            }
        }
        child.release();
        child = null;
        owner = null;
        shownRow = null;
    }

    @Override public void close() throws ReflectiveOperationException { clear(); }

    /**
     * Bottom display-navigation intersection in the window's surface-local coordinates.
     * Frame/display must be in the same unscaled space; inset values are surface padding.
     * Does not extend a short/IME-resized window into navigation or paint only a partial band.
     */
    static Rect geometry(Rect frame, Rect display, int navHeight, Rect surfaceInsets) {
        if (frame == null || display == null || frame.isEmpty() || display.isEmpty()
                || navHeight <= 0 || navHeight > display.height()
                || frame.bottom < display.bottom || frame.top > display.bottom - navHeight) {
            return null;
        }
        int leftInset = surfaceInsets == null ? 0 : surfaceInsets.left;
        int topInset = surfaceInsets == null ? 0 : surfaceInsets.top;
        if (surfaceInsets != null && (leftInset < 0 || topInset < 0
                || surfaceInsets.right < 0 || surfaceInsets.bottom < 0)) return null;
        int left = Math.max(frame.left, display.left);
        int right = Math.min(frame.right, display.right);
        if (left >= right) return null;
        Rect result = new Rect(left, display.bottom - navHeight, right, display.bottom);
        // WindowState.transformFrameToSurfacePosition subtracts the surface inset origin.
        result.offset(leftInset - frame.left, topInset - frame.top);
        return result;
    }
}
