package dev.nativecomplements.navdemo;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.SurfaceControl;
import android.view.WindowInsets;
import android.view.WindowManager;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only window-layer capture. All GPU work and provider IPC stay outside the WM lock. */
final class DemoCapture {
    private static final Uri PROVIDER = Uri.parse("content://dev.nativecomplements.navdemo.data");
    private static final long INTERVAL = 1000;
    static final int MAX_IMAGE = 800 * 1024;
    private final Constructor<?> builder;
    private final Method crop, build, capture, hardwareBuffer, bitmap, secure, hdr, securePolicy, protectedPolicy;
    private final HandlerThread thread;
    private final Handler worker;
    private final AtomicBoolean queued = new AtomicBoolean();
    private volatile Context context;
    private volatile Object wm, lock;
    private volatile boolean enabled, stopped;
    private volatile long nextSample;
    private long generation;
    private long sequence = SystemClock.elapsedRealtimeNanos();
    private String lastPending = "";
    private record Remembered(Snapshot snapshot, long generation, byte[] stripHash) {}
    private final LinkedHashMap<Long, Remembered> recent = new LinkedHashMap<>();
    // Captures and pending requests are serialized on the observation worker.
    private String captureColorSpace;

    DemoCapture(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> screen = Class.forName("android.window.ScreenCaptureInternal", false, loader);
        Class<?> args = Class.forName(screen.getName() + "$LayerCaptureArgs", false, loader);
        Class<?> type = Class.forName(args.getName() + "$Builder", false, loader);
        builder = type.getConstructor(SurfaceControl.class);
        crop = type.getMethod("setSourceCrop", Rect.class);
        securePolicy = type.getMethod("setSecureContentPolicy", int.class);
        protectedPolicy = type.getMethod("setProtectedContentPolicy", int.class);
        build = type.getMethod("build");
        capture = screen.getMethod("captureLayers", args);
        Class<?> buffer = capture.getReturnType();
        hardwareBuffer = buffer.getMethod("getHardwareBuffer");
        bitmap = buffer.getMethod("asBitmap");
        secure = buffer.getMethod("containsSecureLayers");
        hdr = buffer.getMethod("containsHdrLayers");
        // Required coordinate/eligibility APIs must exist before installing any hooks.
        Class<?> window = Class.forName("com.android.server.wm.WindowState", false, loader);
        window.getMethod("getFrame");
        window.getMethod("getSurfaceControl");
        window.getMethod("isRequestedVisible", int.class);
        window.getMethod("isVisible");
        window.getMethod("getOwningUid");
        window.getMethod("getWindowingMode");
        window.getMethod("getConfiguration");
        Class.forName("com.android.server.wm.ActivityRecord", false, loader).getMethod("inTransition");
        Class<?> display = Class.forName("com.android.server.wm.DisplayContent", false, loader);
        display.getMethod("getDisplayId");
        display.getMethod("getBounds");
        display.getMethod("getInsetsStateController");
        Class<?> insets = Class.forName("android.view.InsetsState", false, loader);
        insets.getMethod("calculateInsets", Rect.class, Rect.class, int.class, boolean.class);
        insets.getMethod("sourceSize");
        insets.getMethod("sourceAt", int.class);
        window.getDeclaredField("mGlobalScale");
        WindowManager.LayoutParams.class.getDeclaredField("surfaceInsets");
        thread = new HandlerThread("NavbarDemoObserver");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    void connect(Context c, Object manager, Object globalLock) {
        if (stopped || context != null) return;
        wm = manager;
        lock = globalLock;
        context = c;
        worker.post(this::readConfig);
    }

    void stop() {
        stopped = true;
        enabled = false;
        worker.removeCallbacksAndMessages(null);
        thread.quitSafely();
    }

    private void readConfig() {
        if (stopped) return;
        long identity = Binder.clearCallingIdentity();
        try {
            Bundle config = context.getContentResolver().call(PROVIDER, "config", null, null);
            boolean previous = enabled;
            enabled = config != null && config.getBoolean("enabled", false);
            long nextGeneration = config == null ? 0 : config.getLong("generation", 0);
            if (generation != nextGeneration || !enabled) recent.clear();
            generation = nextGeneration;
            Bundle pending = config == null ? null : config.getBundle("pending");
            if (enabled && pending != null) {
                String token = pending.getString("token", "");
                if (!token.isEmpty() && !token.equals(lastPending)) {
                    lastPending = token;
                    capturePending(pending);
                }
            }
            // ponytail: WM draw notifications are not every app frame; 1 Hz observation fallback
            // catches in-Activity sheets/video changes without hooking every application's render path.
            if (enabled) request();
            if (!enabled && previous) publish(unavailable("disabled", null, 0, generation, 0, 0, 0));
        } catch (RuntimeException error) {
            // Uninstall/provider failure stops capture immediately; only the cheap config poll remains.
            enabled = false;
            queued.set(false);
            worker.removeCallbacks(sample);
        } finally {
            Binder.restoreCallingIdentity(identity);
            if (!stopped) worker.postDelayed(this::readConfig, 1000);
        }
    }

    void request() {
        if (stopped || !enabled || context == null || !queued.compareAndSet(false, true)) return;
        worker.postDelayed(sample, Math.max(0, nextSample - SystemClock.uptimeMillis()));
    }

    private final Runnable sample = this::sample;
    private void sample() {
        nextSample = SystemClock.uptimeMillis() + INTERVAL;
        queued.set(false);
        if (!enabled || stopped) return;
        long started = SystemClock.elapsedRealtime(), captureGeneration = generation;
        long capturedAt = 0, captureMs = 0, classifyMs = 0;
        Snapshot before = null;
        captureColorSpace = null;
        try {
            before = snapshot();
            long captureStarted = SystemClock.elapsedRealtime();
            Rect fullRegion = structureRegion(before);
            int navRows = navRows(before);
            Rect captureRegion = new Rect(fullRegion.left, fullRegion.top, fullRegion.right, fullRegion.bottom + navRows);
            int[] captured;
            Bitmap frame = captureBitmap(before, captureRegion);
            try {
                captured = new int[frame.getWidth() * frame.getHeight()];
                frame.getPixels(captured, 0, frame.getWidth(), 0, 0, frame.getWidth(), frame.getHeight());
                for (int pixel : captured) if ((pixel >>> 24) != 255) reject("nonopaque single-frame capture");
            } finally { frame.recycle(); }
            int roiLength = fullRegion.width() * fullRegion.height();
            int[] fullPixels = java.util.Arrays.copyOf(captured, roiLength);
            int[] navPixels = java.util.Arrays.copyOfRange(captured, roiLength, captured.length);
            int[] pixels = bottomStrip(fullPixels, before.local.width(), fullRegion.height(), before.local.height());
            capturedAt = SystemClock.elapsedRealtime();
            captureMs = capturedAt - captureStarted;
            long classifyStarted = capturedAt;
            Bundle result = DemoClassifier.analyze(pixels, before.local.width(), before.local.height(), before.density);
            classifyMs = SystemClock.elapsedRealtime() - classifyStarted;
            Snapshot after = snapshot();
            if (!before.same(after)) throw new IllegalStateException("window or geometry changed during capture");
            String initial = result.getString("decision", "UNKNOWN");
            boolean recognized = "EXTEND".equals(initial) || "NATIVE".equals(initial);
            if (!recognized) result.putString("reviewTrigger", "none");
            review(result, before, fullPixels, fullRegion, navPixels, navRows, recognized);
            if (!before.same(snapshot())) reject("window changed before result delivery");
            result.putBoolean("valid", recognized);
            metadata(result, before, started, captureGeneration, capturedAt, captureMs, classifyMs);
            recent.put(result.getLong("id"), new Remembered(before, captureGeneration, hash(pixels)));
            if (recent.size() > 256) recent.remove(recent.keySet().iterator().next());
            publish(result);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            publish(unavailable(error.getClass().getSimpleName() + ": " + error.getMessage(), before, started,
                    captureGeneration, capturedAt, captureMs, classifyMs));
        }
    }

    private record Snapshot(Object window, Object focus, SurfaceControl surface, ComponentName component, Rect frame,
                            Rect bounds, Rect padding, Rect screen, Rect local, float density, int rotation, String source) {
        boolean same(Snapshot other) {
            return window == other.window && focus == other.focus && surface == other.surface && component.equals(other.component)
                    && frame.equals(other.frame) && bounds.equals(other.bounds) && padding.equals(other.padding)
                    && screen.equals(other.screen) && local.equals(other.local)
                    && density == other.density && rotation == other.rotation;
        }
    }

    private Snapshot snapshot() throws ReflectiveOperationException {
        if (!enabled || !context.getSystemService(PowerManager.class).isInteractive()
                || context.getSystemService(KeyguardManager.class).isKeyguardLocked()) reject("disabled, screen off or locked");
        int mode = android.provider.Settings.Secure.getInt(context.getContentResolver(), "navigation_mode", -1);
        if (mode != 2) reject("gesture mode not confirmed");
        synchronized (lock) {
            Object display = call(field(wm, "mRoot"), "getDefaultDisplay");
            if (((Number) call(display, "getDisplayId")).intValue() != 0) reject("not display zero");
            Object focus = field(display, "mCurrentFocus");
            if (focus == null || field(focus, "mActivityRecord") == null) reject("no focused activity");
            Object activity = field(focus, "mActivityRecord");
            if (activity != field(display, "mFocusedApp")) reject("focus mismatch");
            if (Boolean.TRUE.equals(call(activity, "inTransition"))) reject("activity transition in progress");
            // A dialog/bottom sheet/popup of the same activity takes focus but is drawn in its own window.
            // Then the activity layer (main window + its attached windows) is captured, so the bottom shows
            // exactly what the user sees there; geometry still comes from the main window.
            WindowManager.LayoutParams focusAttrs = (WindowManager.LayoutParams) field(focus, "mAttrs");
            boolean attached = focusAttrs.type != WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
            if (attached) {
                int t = focusAttrs.type;
                boolean app = t >= WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW && t <= WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
                boolean sub = t >= WindowManager.LayoutParams.FIRST_SUB_WINDOW && t <= WindowManager.LayoutParams.LAST_SUB_WINDOW;
                if ((!app && !sub) || (focusAttrs.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0) reject("ineligible focused window type=" + t);
            }
            Object window = attached ? call(activity, "findMainWindow") : focus;
            if (window == null) reject("no main window behind focused dialog");
            WindowManager.LayoutParams attrs = (WindowManager.LayoutParams) field(window, "mAttrs");
            if (attrs.type != WindowManager.LayoutParams.TYPE_BASE_APPLICATION
                    || (attrs.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0
                    || attrs.alpha != 1f || ((Number) call(window, "getWindowingMode")).intValue() != 1
                    || ((Number) field(window, "mGlobalScale")).floatValue() != 1f
                    || !Boolean.TRUE.equals(call(window, "isVisible"))) reject("ineligible main window");
            ComponentName component = (ComponentName) field(activity, "mActivityComponent");
            int uid = ((Number) call(window, "getOwningUid")).intValue();
            if (uid % 100000 < 10000 || component == null
                    || component.getPackageName().equals("dev.nativecomplements.navdemo")
                    || component.getPackageName().equals("com.android.systemui")
                    || component.getPackageName().equals("com.google.android.apps.nexuslauncher")) reject("not ordinary application");
            Rect frame = new Rect((Rect) call(window, "getFrame"));
            Rect bounds = new Rect((Rect) call(display, "getBounds"));
            if (bounds.isEmpty() || bounds.width() >= bounds.height() || frame.isEmpty()
                    || frame.left != bounds.left || frame.right != bounds.right) reject("not full portrait width");
            android.content.res.Configuration config = (android.content.res.Configuration) call(window, "getConfiguration");
            float density = config.densityDpi / 160f;
            int rotation = ((Number) call(field(config, "windowConfiguration"), "getRotation")).intValue();
            if (rotation != 0 || !Float.isFinite(density) || density <= 0) reject("unsupported rotation or density");
            Object state = call(call(display, "getInsetsStateController"), "getRawInsetsState");
            int count = ((Number) call(state, "sourceSize")).intValue();
            for (int i = 0; i < count; i++) {
                Object source = invoke(state, "sourceAt", new Class<?>[]{int.class}, i);
                if ((((Number) call(source, "getType")).intValue() & WindowInsets.Type.ime()) != 0
                        && Boolean.TRUE.equals(call(source, "isVisible"))) reject("IME visible");
            }
            Insets nav = (Insets) invoke(state, "calculateInsets",
                    new Class<?>[]{Rect.class, Rect.class, int.class, boolean.class},
                    bounds, bounds, WindowInsets.Type.navigationBars(), true);
            if (nav.bottom <= 0 || nav.left != 0 || nav.right != 0 || nav.top != 0
                    || !Boolean.TRUE.equals(invoke(window, "isRequestedVisible", new Class<?>[]{int.class}, WindowInsets.Type.navigationBars())))
                reject("no confirmed bottom navigation boundary");
            Insets status = (Insets) invoke(state, "calculateInsets",
                    new Class<?>[]{Rect.class, Rect.class, int.class, boolean.class},
                    bounds, bounds, WindowInsets.Type.statusBars(), true);
            int bottom = bounds.bottom - nav.bottom;
            if (frame.top < bounds.top || frame.top > bounds.top + status.top
                    || frame.bottom < bottom || frame.bottom > bounds.bottom) reject("not full-height application content");
            int height = (int) Math.ceil(24d * density);
            Rect screen = new Rect(bounds.left, bottom - height, bounds.right, bottom);
            if (height < 3 || !frame.contains(screen) || !bounds.contains(screen)
                    || screen.bottom > bounds.bottom - nav.bottom) reject("crop intersects navigation or leaves window");
            Rect padding = new Rect((Rect) field(attrs, "surfaceInsets"));
            if (attached) {
                // Activity layer origin is the activity bounds; require a fullscreen activity so the main
                // window frame sits at its screen position inside that layer.
                Rect activityBounds = new Rect((Rect) call(activity, "getBounds"));
                if (!activityBounds.equals(bounds)) reject("activity layer not fullscreen");
                padding = new Rect(frame.left - bounds.left, frame.top - bounds.top, 0, 0);
            }
            if (padding.left < 0 || padding.top < 0 || padding.right < 0 || padding.bottom < 0) reject("invalid surface padding");
            Rect local = new Rect(screen);
            local.offset(padding.left - frame.left, padding.top - frame.top);
            if (local.left < padding.left || local.top < padding.top
                    || local.right > padding.left + frame.width() || local.bottom > padding.top + frame.height()) reject("invalid surface-local crop");
            SurfaceControl surface = (SurfaceControl) call(attached ? activity : window, "getSurfaceControl");
            if (surface == null || !surface.isValid()) reject("surface unavailable");
            return new Snapshot(window, focus, surface, component, frame, bounds, padding, screen, local, density, rotation,
                    attached ? "attached:" + focusAttrs.type : "main");
        }
    }

    private int[] pixels(Snapshot snapshot) throws ReflectiveOperationException {
        Bitmap software = captureBitmap(snapshot, snapshot.local);
        try {
            int[] pixels = new int[software.getWidth() * software.getHeight()];
            software.getPixels(pixels, 0, software.getWidth(), 0, 0, software.getWidth(), software.getHeight());
            for (int pixel : pixels) if ((pixel >>> 24) != 255) reject("nonopaque capture");
            return pixels;
        } finally { software.recycle(); }
    }

    /** Ownership of returned software bitmap transfers to the caller. Never includes other windows. */
    private Bitmap captureBitmap(Snapshot snapshot, Rect rectangle) throws ReflectiveOperationException {
        HardwareBuffer buffer = null;
        Bitmap image = null;
        try {
            Object args = builder.newInstance(snapshot.surface);
            crop.invoke(args, rectangle);
            // Target API37 THROW_EXCEPTION=2: reject protected layers rather than accepting a redacted black image.
            securePolicy.invoke(args, 2);
            protectedPolicy.invoke(args, 2);
            Object result = capture.invoke(null, build.invoke(args));
            if (result == null) reject("capture_result_null; requested=" + rectangle.width() + "x" + rectangle.height());
            buffer = (HardwareBuffer) hardwareBuffer.invoke(result);
            if (Boolean.TRUE.equals(secure.invoke(result)) || Boolean.TRUE.equals(hdr.invoke(result)))
                throw new SecurityException("capture_secure_or_hdr");
            if (buffer == null) reject("capture_buffer_null; requested=" + rectangle.width() + "x" + rectangle.height());
            if ((buffer.getUsage() & HardwareBuffer.USAGE_PROTECTED_CONTENT) != 0)
                throw new SecurityException("capture_protected");
            if (buffer.getFormat() != HardwareBuffer.RGBA_8888) reject("capture_format=" + buffer.getFormat());
            image = (Bitmap) bitmap.invoke(result);
            String diagnostic = "requested=" + rectangle.width() + "x" + rectangle.height()
                    + "; buffer=" + buffer.getWidth() + "x" + buffer.getHeight()
                    + "; bitmap=" + (image == null ? "null" : image.getWidth() + "x" + image.getHeight())
                    + "; colorSpace=" + (image == null || image.getColorSpace() == null ? "unknown"
                    : image.getColorSpace().getName() + "#" + image.getColorSpace().getId());
            if (image == null) reject("capture_bitmap_null; " + diagnostic);
            if (buffer.getWidth() != rectangle.width() || buffer.getHeight() != rectangle.height())
                reject("capture_buffer_geometry; " + diagnostic);
            if (image.getWidth() != rectangle.width() || image.getHeight() != rectangle.height())
                reject("capture_bitmap_geometry; " + diagnostic);
            captureColorSpace = image.getColorSpace() == null ? "unknown"
                    : image.getColorSpace().getName() + "#" + image.getColorSpace().getId() + " -> sRGB";
            try { return DemoCaptureColor.toSrgb(image); }
            catch (RuntimeException error) { throw new IllegalStateException(error.getMessage() + "; " + diagnostic, error); }
        } finally {
            if (image != null) image.recycle();
            if (buffer != null) buffer.close();
        }
    }

    private static Rect structureRegion(Snapshot expected) {
        Rect region = new Rect(expected.screen.left,
                Math.max(expected.frame.top, expected.screen.bottom - (int) Math.ceil(320d * expected.density)),
                expected.screen.right, expected.screen.bottom);
        region.offset(expected.padding.left - expected.frame.left, expected.padding.top - expected.frame.top);
        return region;
    }

    /** App-layer rows inside the navigation area (the window may stop at the boundary: zero rows). */
    private static int navRows(Snapshot s) {
        return Math.max(0, Math.min(s.frame.bottom, s.bounds.bottom) - s.screen.bottom);
    }

    static int[] bottomStrip(int[] pixels, int width, int height, int stripHeight) {
        if (pixels == null || width <= 0 || height <= 0 || stripHeight <= 0 || stripHeight > height
                || (long) width * height != pixels.length) throw new IllegalArgumentException("invalid single-frame geometry");
        int[] strip = new int[width * stripHeight];
        System.arraycopy(pixels, pixels.length - strip.length, strip, 0, strip.length);
        return strip;
    }

    private void review(Bundle result, Snapshot expected, int[] pixels, Rect region, int[] nav, int navRows, boolean recognized) {
        result.putString("initialDecision", result.getString("initialDecision", result.getString("decision", "UNKNOWN")));
        result.putLong("reviewCacheAgeMs", 0);
        result.putLong("reviewMs", 0);
        result.putLong("reviewAt", 0);
        result.putString("reviewStatus", "skipped");
        result.putString("reviewReason", "invalid initial classification");
        if (!recognized) return;
        long started = SystemClock.elapsedRealtime();
        result.putString("reviewTrigger", "single_frame_structure");
        result.putString("reviewCrop", "surface=" + region);
        result.putInt("reviewHeightDp", Math.round(region.height() / expected.density));
        result.putString("reviewMatchScope", "same_capture_bitmap; 1Hz observation may delay window events up to 1s");
        result.putInt("navRows", navRows);
        try {
            result.putAll(DemoLayout.analyze(pixels, region.width(), region.height(), expected.density, nav, region.width(), navRows));
            result.putString("reviewStatus", "INCONCLUSIVE".equals(result.getString("reviewDecision", "INCONCLUSIVE"))
                    ? "inconclusive" : "fresh");
            applyReview(result);
        } catch (RuntimeException error) {
            result.putString("reviewDecision", "INCONCLUSIVE");
            result.putString("reviewStatus", "failed");
            result.putString("reviewReason", error.getClass().getSimpleName() + ": " + error.getMessage());
        }
        result.putLong("reviewAt", SystemClock.elapsedRealtime());
        result.putLong("reviewMs", SystemClock.elapsedRealtime() - started);
    }

    static void applyReview(Bundle result) {
        String decision = result.getString("reviewDecision", "INCONCLUSIVE");
        if ("EXTEND".equals(decision) || "NATIVE".equals(decision)) {
            result.putString("decision", decision);
            result.putString("reason", result.getString("reason", "") + "; " + result.getString("reviewReason", ""));
        }
    }

    private void capturePending(Bundle pending) {
        Bundle output = new Bundle();
        output.putString("token", pending.getString("token", ""));
        long requestedGeneration = pending.getLong("generation", -1);
        output.putLong("generation", requestedGeneration);
        output.putString("status", "failed");
        output.putBoolean("contentMatched", false);
        output.putByteArray("bytes", null);
        Bitmap image = null, scaled = null;
        try {
            long now = SystemClock.elapsedRealtime(), requestAt = pending.getLong("requestAt", -1);
            if (!enabled || requestedGeneration != generation || requestAt < 0 || now < requestAt
                    || now - requestAt > 10_000) reject("request expired or generation changed");
            Bundle sample = pending.getBundle("sample");
            if (sample == null || sample.getLong("generation", -1) != requestedGeneration) reject("sample epoch missing");
            Remembered remembered = recent.get(sample.getLong("id", -1));
            if (remembered == null || remembered.generation != requestedGeneration) reject("sample no longer retained");
            Snapshot expected = remembered.snapshot;
            if (!expected.same(snapshot())) reject("sample window or geometry changed");
            byte[] currentHash = hash(pixels(expected));
            if (!expected.same(snapshot())) reject("window changed while checking strip");
            // App content only, ending above navigation even when the app draws edge-to-edge.
            Rect roi = structureRegion(expected);
            int navRows = navRows(expected);
            Rect content = new Rect(roi.left, roi.top, roi.right, roi.bottom + navRows);
            if (SystemClock.elapsedRealtime() - requestAt > 10_000) reject("request deadline before screenshot");
            image = captureBitmap(expected, content);
            long screenshotAt = SystemClock.elapsedRealtime(), wallTime = System.currentTimeMillis();
            if (!expected.same(snapshot())) reject("window changed during screenshot");
            byte[] afterHash = hash(pixels(expected));
            if (!expected.same(snapshot())) reject("window changed after screenshot");
            boolean matched = MessageDigest.isEqual(remembered.stripHash, currentHash)
                    && MessageDigest.isEqual(currentHash, afterHash);
            output.putLong("screenshotAt", screenshotAt);
            output.putLong("screenshotWallTime", wallTime);
            output.putInt("sourceWidth", image.getWidth());
            output.putInt("sourceHeight", image.getHeight());
            output.putInt("roiHeight", roi.height());
            output.putInt("navRows", navRows);
            scaled = image;
            // Lossless first so offline replay sees the exact pixels the classifier saw.
            byte[] bytes = null; String format = "png";
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            if (scaled.compress(Bitmap.CompressFormat.PNG, 100, png) && png.size() <= MAX_IMAGE) bytes = png.toByteArray();
            for (int quality : new int[]{95, 90, 80, 70}) {
                if (bytes != null) break;
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)) reject("JPEG compression failed");
                if (stream.size() <= MAX_IMAGE) { bytes = stream.toByteArray(); format = "jpg"; }
            }
            if (bytes == null) reject("image exceeds Binder limit");
            output.putString("format", format);
            if (!expected.same(snapshot())) reject("window changed before screenshot delivery");
            output.putInt("width", scaled.getWidth());
            output.putInt("height", scaled.getHeight());
            output.putString("crop", "surface=" + content);
            output.putString("matchScope", "strip_match_only");
            output.putBoolean("contentMatched", matched);
            output.putString("status", matched ? "saved" : "content_changed");
            output.putString("reason", "strip_match_only; full-page content identity is not guaranteed; app layer excludes overlay");
            output.putByteArray("bytes", bytes);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            output.putString("reason", error.getClass().getSimpleName() + ": " + error.getMessage());
        } finally {
            if (scaled != null && scaled != image) scaled.recycle();
            if (image != null) image.recycle();
        }
        if (!output.containsKey("screenshotAt")) output.putLong("screenshotAt", SystemClock.elapsedRealtime());
        if (!output.containsKey("screenshotWallTime")) output.putLong("screenshotWallTime", System.currentTimeMillis());
        long identity = Binder.clearCallingIdentity();
        try { context.getContentResolver().call(PROVIDER, "screenshot", null, output); }
        catch (RuntimeException error) {
            enabled = false;
            worker.removeCallbacks(sample);
            queued.set(false);
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    private static byte[] hash(int[] pixels) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer row = ByteBuffer.allocate(4096);
            for (int pixel : pixels) {
                if (row.remaining() < 4) { digest.update(row.array(), 0, row.position()); row.clear(); }
                row.putInt(pixel);
            }
            digest.update(row.array(), 0, row.position());
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private Bundle unavailable(String reason, Snapshot snapshot, long started, long captureGeneration,
                               long capturedAt, long captureMs, long classifyMs) {
        Bundle result = new Bundle();
        result.putBoolean("valid", false);
        result.putString("decision", "UNAVAILABLE");
        result.putString("reason", reason);
        result.putDouble("stableRatio", 0);
        result.putDouble("edgeRatio", 0);
        result.putInt("segments", 0);
        metadata(result, snapshot, started, captureGeneration, capturedAt, captureMs, classifyMs);
        return result;
    }

    private void metadata(Bundle result, Snapshot snapshot, long started, long captureGeneration,
                          long capturedAt, long captureMs, long classifyMs) {
        long now = SystemClock.elapsedRealtime();
        result.putLong("id", ++sequence);
        result.putLong("generation", captureGeneration);
        result.putLong("capturedAt", capturedAt);
        result.putLong("captureMs", captureMs);
        result.putLong("classifyMs", classifyMs);
        result.putLong("elapsedMs", started == 0 ? 0 : now - started);
        if (captureColorSpace != null) result.putString("colorSpace", captureColorSpace);
        result.putString("component", snapshot == null ? "" : snapshot.component.flattenToShortString());
        result.putInt("width", snapshot == null ? 0 : snapshot.local.width());
        result.putInt("height", snapshot == null ? 0 : snapshot.local.height());
        result.putFloat("density", snapshot == null ? 0 : snapshot.density);
        result.putString("crop", snapshot == null ? "" : "screen=" + snapshot.screen + "; surface=" + snapshot.local);
        if (snapshot != null) result.putString("source", snapshot.source);
    }

    private void publish(Bundle result) {
        long identity = Binder.clearCallingIdentity();
        try {
            context.getContentResolver().call(PROVIDER, "sample", null, result);
        } catch (RuntimeException error) {
            enabled = false;
            worker.removeCallbacks(sample);
            queued.set(false);
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    private static void reject(String reason) { throw new IllegalStateException(reason); }

    static Object field(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
            catch (NoSuchFieldException missing) { /* Search inherited fields. */ }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        return invoke(target, name, new Class<?>[0]);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name, types);
        return method.invoke(target, args);
    }
}
