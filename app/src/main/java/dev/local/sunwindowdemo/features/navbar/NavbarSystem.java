package dev.local.sunwindowdemo.features.navbar;

import android.app.*;
import android.content.*;
import android.database.ContentObserver;
import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.*;
import android.view.*;
import dev.local.sunwindowdemo.core.HostSnapshot;
import io.github.libxposed.api.*;
import java.lang.reflect.*;
import java.util.Map;

/** All Binder calls and cropped GPU readbacks run outside the WM lock. */
final class NavbarSystem {
    static final String FRAME = "dev.nativecomplements.module.NAVBAR_FRAME";
    static final long FRAME_TIMEOUT_MS = 3000;
    /** Coalescing window for layout/draw bursts. */
    static final long COALESCE_MS = 64;
    /** One frame for the finishDrawing transaction to reach SurfaceFlinger before reading it back. */
    static final long FIRST_DRAW_MS = 16;
    /** A new page that has not drawn yet keeps the previous frame this long instead of restoring native. */
    static final long SWITCH_HOLD_MS = 400;
    private final Handler worker;
    private final NavbarColorCache cache = new NavbarColorCache();
    private long switchedAt;
    private boolean sampledSinceSwitch, cacheTried, waitingFirstFrame;
    private int stableColor, stableSamples;
    private Object pendingWindow;
    private long pendingSince;
    private final java.util.concurrent.atomic.AtomicBoolean urgentPending = new java.util.concurrent.atomic.AtomicBoolean();
    private final Map<String, Long> versions = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile NavbarRules rules = new NavbarRules(Bundle.EMPTY);
    private volatile boolean enabled;
    private volatile boolean suspended = true;
    private volatile boolean gestures;
    private volatile boolean landscapeClear = true;
    private Object wm, lock;
    private volatile Object previousWindow;
    private final Map<Object, NavbarWindowLayer> windowLayers = new java.util.IdentityHashMap<>();
    private boolean publishedWindowOwned;
    private NavbarWindowLayer layerPreflight;
    private record LayerGeometry(Rect frame, Rect display, Rect surfaceInsets, int height) {}
    private final Map<Object, LayerGeometry> layerBounds = new java.util.IdentityHashMap<>();
    private boolean windowLayersAvailable;
    private Context context;
    private final java.util.concurrent.ExecutorService statusWriter = java.util.concurrent.Executors.newSingleThreadExecutor();
    private long lastFailure;
    private volatile String lastRuntime = "";
    private String runtimePage = "";
    private volatile long statusPausedUntil;
    private volatile String pendingRuntime = "";
    private final java.util.concurrent.atomic.AtomicBoolean statusPending = new java.util.concurrent.atomic.AtomicBoolean();
    private HostSnapshot settings;
    private boolean settingsApplied;
    private final Runnable retryReload = this::reload;
    private int frameNavHeight;
    private Frame lastGood;
    private long lastPublished;
    private boolean publishedActive;
    private int publishedColor, publishedMode, publishedHeight;
    private final java.util.concurrent.atomic.AtomicBoolean refreshPending = new java.util.concurrent.atomic.AtomicBoolean();
    private final NavbarColors.Stability stability = new NavbarColors.Stability();
    private final Runnable tick = this::tick;
    private final Runnable urgentTick = this::runUrgentTick;
    private void runUrgentTick() {
        urgentPending.set(false);
        worker.removeCallbacks(tick);
        tick();
    }
    private final Class<?> insetsClass;
    private final NavbarInsets stateTools;
    private final Method sourceSize, sourceAt, sourceType, sourceVisible;
    private final Method capture, build, crop, scale;
    private final Method broadcast;
    private final Constructor<?> captureBuilder;

    private NavbarSystem(ClassLoader loader) throws ReflectiveOperationException {
        insetsClass = Class.forName("android.view.InsetsState", false, loader);
        Class<?> source = Class.forName("android.view.InsetsSource", false, loader);
        stateTools = new NavbarInsets(insetsClass);
        sourceSize = insetsClass.getMethod("sourceSize");
        sourceAt = insetsClass.getMethod("sourceAt", int.class);
        sourceType = source.getMethod("getType");
        sourceVisible = source.getMethod("isVisible");
        Class<?> captureClass = Class.forName("android.window.ScreenCaptureInternal", false, loader);
        Class<?> args = Class.forName(captureClass.getName() + "$LayerCaptureArgs", false, loader);
        Class<?> builder = Class.forName(args.getName() + "$Builder", false, loader);
        captureBuilder = builder.getConstructor(SurfaceControl.class);
        crop = builder.getMethod("setSourceCrop", Rect.class);
        scale = builder.getMethod("setFrameScale", float.class, float.class);
        build = builder.getMethod("build");
        capture = captureClass.getMethod("captureLayers", args);
        broadcast = Context.class.getMethod("sendBroadcastAsUser", Intent.class, UserHandle.class, String.class, Bundle.class);
        // BACKGROUND moves the thread into the background cpuset, which is what delays ticks during
        // the CPU-heavy app transition. The worker mostly sleeps between cropped readbacks.
        HandlerThread thread = new HandlerThread("NativeNavbar", android.os.Process.THREAD_PRIORITY_DEFAULT);
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    static void install(XposedModule module, ClassLoader loader) {
        try {
            NavbarSystem backend = new NavbarSystem(loader);
            Class<?> window = Class.forName("com.android.server.wm.WindowState", false, loader);
            Method state = window.getDeclaredMethod("getInsetsState", boolean.class);
            Class<?> manager = Class.forName("com.android.server.wm.WindowManagerService", false, loader);
            Method ready = manager.getDeclaredMethod("systemReady");
            Method focus = manager.getDeclaredMethod("updateFocusedWindowLocked", int.class, boolean.class);
            Method draw = window.getDeclaredMethod("finishDrawing", SurfaceControl.Transaction.class, int.class);
            Class<?> policy = Class.forName("com.android.server.wm.DisplayPolicy", false, loader);
            Method layout = policy.getDeclaredMethod("finishPostLayoutPolicyLw");
            // Native bottom ownership survives input focus moving into a freeform task.
            policy.getDeclaredField("mNavBarBackgroundWindowCandidate");
            window.getMethod("getSurfaceControl");
            window.getDeclaredField("mGlobalScale");
            try {
                WindowManager.LayoutParams.class.getDeclaredField("surfaceInsets");
                backend.layerPreflight = new NavbarWindowLayer();
                backend.windowLayersAvailable = true;
            } catch (ReflectiveOperationException | RuntimeException failure) {
                android.util.Log.w("NativeNavbar", "Window layer preflight failed; retaining host fallback", failure);
            }
            Method removed = window.getDeclaredMethod("removeImmediately");
            module.hook(removed).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                try { if (backend.lock != null) synchronized (backend.lock) { backend.clearLayer(chain.getThisObject()); } }
                catch (ReflectiveOperationException | RuntimeException failure) {
                    android.util.Log.w("NativeNavbar", "Window layer cleanup pending", failure);
                }
                return chain.proceed();
            });
            module.hook(state).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object original = chain.proceed();
                try { return backend.insets(chain.getThisObject(), original); }
                catch (ReflectiveOperationException | RuntimeException error) { return original; }
            });
            module.hook(ready)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        Object result = chain.proceed();
                        backend.wm = chain.getThisObject();
                        backend.lock = NavbarReflect.field(backend.wm, "mGlobalLock");
                        backend.worker.postDelayed(backend::connect, 5000);
                        return result;
                    });
            for (Method event : new Method[]{focus, draw, layout}) {
                module.hook(event).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object result = chain.proceed();
                    long delay;
                    if (event == focus) delay = Boolean.TRUE.equals(result) ? 0 : COALESCE_MS;
                    else if (event == draw) delay = backend.isTarget(chain.getThisObject()) ? FIRST_DRAW_MS : -1;
                    else delay = COALESCE_MS;
                    if (delay >= 0) backend.requestTick(delay);
                    return result;
                });
            }
            android.util.Log.i("NativeNavbar", "SYSTEM_HOOK_READY");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { android.util.Log.e("NativeNavbar", "System preflight failed", error); }
    }

    private void connect() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            context.getContentResolver().registerContentObserver(Uri.parse("content://" + NavbarStore.AUTHORITY),
                    true, new ContentObserver(worker) {
                        @Override public void onChange(boolean selfChange) { reload(); }
                    });
            IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
            screen.addAction(Intent.ACTION_SCREEN_OFF);
            screen.addAction(Intent.ACTION_USER_PRESENT);
            screen.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    worker.removeCallbacks(tick);
                    worker.post(tick);
                }
            }, screen, null, worker, Context.RECEIVER_NOT_EXPORTED);
            IntentFilter packages = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
            packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
            packages.addDataScheme("package");
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) { reload(); }
            }, packages, null, worker, Context.RECEIVER_NOT_EXPORTED);
            reload();
        } catch (ReflectiveOperationException | RuntimeException error) {
            android.util.Log.w("NativeNavbar", "System connection pending", error);
            worker.postDelayed(this::connect, 5000);
        }
    }

    private void reload() {
        worker.removeCallbacks(retryReload);
        long identity = Binder.clearCallingIdentity();
        try {
            // A stopped module process keeps the last complete settings (including enabled) instead of restoring native handling.
            if (settings == null) settings = HostSnapshot.open(context, "navbar");
            HostSnapshot.Result read = settings.read(() ->
                    context.getContentResolver().call(Uri.parse("content://" + NavbarStore.AUTHORITY), "system", null, null));
            if (read.report()) android.util.Log.i("NativeNavbar", read.summary());
            Bundle config = read.config();
            if (config == null) throw new IllegalStateException("Missing navbar settings");
            if (!read.live()) worker.postDelayed(retryReload, settings.retryDelay(5000));
            if (!read.live() && settingsApplied) return;
            rules = new NavbarRules(config);
            enabled = config.getBoolean("enabled", false);
            landscapeClear = config.getBoolean("landscape_clear", true);
            versions.clear();
            synchronized (lock) {
                Object display = NavbarReflect.call(NavbarReflect.field(wm, "mRoot"), "getDefaultDisplay");
                Object focused = NavbarReflect.field(display, "mCurrentFocus");
                if (focused != null) NavbarReflect.call(focused, "notifyInsetsChanged");
                NavbarReflect.call(wm, "requestTraversal");
            }
            if (read.live()) {
                lastRuntime = "";
                Bundle status = new Bundle();
                status.putString("host", "系统框架已连接 · " + dev.local.sunwindowdemo.BuildConfig.VERSION_NAME);
                try { context.getContentResolver().call(Uri.parse("content://" + NavbarStore.AUTHORITY), "status", null, status); }
                catch (RuntimeException ignored) { statusPausedUntil = SystemClock.uptimeMillis() + 60_000; }
            }
            settingsApplied = true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            settingsApplied = false;
            enabled = false;
            // Provider failures are already reported once per state change by HostSnapshot.
            if (settings == null || settings.reachable())
                android.util.Log.w("NativeNavbar", "Settings unavailable; restoring native handling", failure);
            worker.postDelayed(retryReload, settings == null ? 5000 : settings.retryDelay(5000));
        } finally { Binder.restoreCallingIdentity(identity); }
        if (previousWindow != null) {
            try { synchronized (lock) { NavbarReflect.call(previousWindow, "notifyInsetsChanged"); } }
            catch (ReflectiveOperationException ignored) {}
        }
        previousWindow = null;
        lastGood = null;
        cache.clear();
        worker.removeCallbacks(tick);
        worker.post(tick);
    }

    private boolean visible(Object state, int type) throws ReflectiveOperationException {
        int count = (Integer) sourceSize.invoke(state);
        for (int index = 0; index < count; index++) {
            Object source = sourceAt.invoke(state, index);
            if (((Integer) sourceType.invoke(source) & type) != 0 && (Boolean) sourceVisible.invoke(source)) return true;
        }
        return false;
    }

    private NavbarRules.Choice choice(Object window, boolean loadVersion) throws ReflectiveOperationException {
        Object activity = NavbarReflect.field(window, "mActivityRecord");
        if (activity == null) return new NavbarRules.Choice(0, null, "系统窗口");
        ComponentName component = (ComponentName) NavbarReflect.field(activity, "mActivityComponent");
        String pkg = component.getPackageName();
        if (pkg.equals("dev.nativecomplements.module") || pkg.equals("com.android.systemui")
                || pkg.equals("com.google.android.apps.nexuslauncher")) return new NavbarRules.Choice(0, null, "系统宿主");
        Long version = versions.get(pkg);
        if (version == null && loadVersion) {
            // Called on the worker before acquiring the WM lock.
            try { version = context.getPackageManager().getPackageInfo(pkg, 0).getLongVersionCode(); }
            catch (android.content.pm.PackageManager.NameNotFoundException missing) { version = -1L; }
            versions.put(pkg, version);
        }
        WindowManager.LayoutParams attrs = (WindowManager.LayoutParams) NavbarReflect.field(window, "mAttrs");
        return rules.resolve(pkg, component.getClassName(), version == null ? -1 : version,
                attrs.type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION);
    }

    private boolean eligible(Object window, Object display, Object raw) throws ReflectiveOperationException {
        WindowManager.LayoutParams attrs = (WindowManager.LayoutParams) NavbarReflect.field(window, "mAttrs");
        Rect frame = (Rect) NavbarReflect.call(window, "getFrame");
        return attrs.type >= 1 && attrs.type <= 99 && (attrs.flags & WindowManager.LayoutParams.FLAG_SECURE) == 0
                && (Integer) NavbarReflect.call(window, "getWindowingMode") == 1 && frame.height() > 0 && frame.width() > 0
                && targetWindow(display, previousWindow) == window
                && supportsNavigation(frame, landscapeClear,
                    (Boolean) window.getClass().getMethod("isRequestedVisible", int.class).invoke(window, WindowInsets.Type.navigationBars()),
                    visible(raw, WindowInsets.Type.navigationBars()), visible(raw, WindowInsets.Type.ime()));
    }

    static boolean supportsNavigation(Rect frame, boolean clearLandscape, boolean requested, boolean visible, boolean keyboard) {
        if (keyboard) return false;
        return frame.width() > frame.height() ? clearLandscape : requested && visible;
    }

    /** Input focus may belong to a freeform task; the native policy still knows the bottom owner. */
    static Object targetWindow(Object display, Object previous) throws ReflectiveOperationException {
        Object focused = NavbarReflect.field(display, "mCurrentFocus");
        if (focused != null) {
            WindowManager.LayoutParams attrs = (WindowManager.LayoutParams) NavbarReflect.field(focused, "mAttrs");
            boolean screenshot = "com.android.systemui".equals(attrs.packageName)
                    && "ScreenshotUI".contentEquals(attrs.getTitle());
            if (!screenshot) {
                boolean application = attrs.type >= 1 && attrs.type <= 99;
                if (application && (Integer) NavbarReflect.call(focused, "getWindowingMode") == 5) {
                    Object policy = NavbarReflect.call(display, "getDisplayPolicy");
                    Object bottom = NavbarReflect.field(policy, "mNavBarBackgroundWindowCandidate");
                    if (bottom != null && (Integer) NavbarReflect.call(bottom, "getWindowingMode") == 1
                            && (Boolean) NavbarReflect.call(bottom, "isVisible")) {
                        WindowManager.LayoutParams bottomAttrs = (WindowManager.LayoutParams) NavbarReflect.field(bottom, "mAttrs");
                        if (bottomAttrs.type >= 1 && bottomAttrs.type <= 99
                                && (bottomAttrs.flags & WindowManager.LayoutParams.FLAG_SECURE) == 0
                                && NavbarReflect.call(bottom, "getDisplayContent") == display) return bottom;
                    }
                    return null;
                }
                return focused;
            }
        }
        if (previous == null || NavbarReflect.field(previous, "mActivityRecord") == null
                || NavbarReflect.field(previous, "mActivityRecord") != NavbarReflect.field(display, "mFocusedApp")) return null;
        return (Boolean) NavbarReflect.call(previous, "isVisible") ? previous : null;
    }

    private static boolean screenshotFocused(Object display) throws ReflectiveOperationException {
        Object focused = NavbarReflect.field(display, "mCurrentFocus");
        if (focused == null) return false;
        WindowManager.LayoutParams attrs = (WindowManager.LayoutParams) NavbarReflect.field(focused, "mAttrs");
        return "com.android.systemui".equals(attrs.packageName) && "ScreenshotUI".contentEquals(attrs.getTitle());
    }

    /** Called from finishDrawing under the WM lock: the tracked window or the newly focused one. */
    private boolean isTarget(Object window) {
        if (window == previousWindow) return true;
        if (!enabled) return false;
        try {
            Object display = NavbarReflect.call(window, "getDisplayContent");
            return display != null && NavbarReflect.field(display, "mCurrentFocus") == window;
        } catch (ReflectiveOperationException | RuntimeException failure) { return false; }
    }

    /**
     * A focus change runs the next tick immediately, and the first draw of the target one frame later.
     * Further events in the same burst fold into the pending tick; layout events keep the 64ms window.
     */
    private void requestTick(long delay) {
        if (!enabled || context == null) return;
        if (delay < COALESCE_MS) {
            if (delay <= 0) {
                worker.removeCallbacks(urgentTick);
                urgentPending.set(true);
                worker.post(urgentTick);
            } else if (urgentPending.compareAndSet(false, true)) worker.postDelayed(urgentTick, delay);
            return;
        }
        if (!refreshPending.compareAndSet(false, true)) return;
        worker.postDelayed(() -> {
            refreshPending.set(false);
            worker.removeCallbacks(tick);
            tick();
        }, COALESCE_MS);
    }

    record Frame(Object window, int color, int mode, int height, long sampledAt) {
        boolean matches(Object current, int nextMode, int nextHeight) {
            return window == current && mode == nextMode && height == nextHeight;
        }
        boolean canRetain(Object current, int nextMode, int nextHeight, long now) {
            return matches(current, nextMode, nextHeight) && now >= sampledAt && now - sampledAt <= 1500;
        }
    }

    private Object insets(Object window, Object original) throws ReflectiveOperationException {
        if (!enabled || suspended || !gestures || context == null) return original;
        Rect frame = (Rect) NavbarReflect.call(window, "getFrame");
        if (frame.width() > frame.height()) return original;
        if (choice(window, false).mode() != NavbarStore.FLOATING) return original;
        Object display = NavbarReflect.call(window, "getDisplayContent");
        Object raw = NavbarReflect.call(NavbarReflect.call(display, "getInsetsStateController"), "getRawInsetsState");
        if (!eligible(window, display, raw)) return original;
        return stateTools.strip(original);
    }

    /** Convert the content/navigation boundary to window-local coordinates exactly once. */
    static int navigationTop(Rect frame, Rect bounds, int navHeight) {
        return Math.min(frame.bottom, bounds.bottom - navHeight) - frame.top;
    }

    /** Launcher remains the landscape background owner throughout home/recents handoff. */
    static boolean keepsOriginal(int selectedMode, boolean landscape, String source, String component) {
        if (selectedMode != NavbarStore.ORIGINAL) return false;
        boolean launcher = component != null && component.startsWith("com.google.android.apps.nexuslauncher/");
        return !landscape || "系统窗口".equals(source) || "系统宿主".equals(source) && !launcher;
    }

    static boolean clearsLauncherHost(boolean landscape, boolean ownedTasks, String component) {
        return !landscape && ownedTasks && component != null
                && component.startsWith("com.google.android.apps.nexuslauncher/");
    }

    /** A switch with no sample yet keeps an active host frame briefly while the new page draws. */
    static boolean holdsSwitch(boolean sampled, boolean active, long switchedAt, long now) {
        return !sampled && active && now >= switchedAt && now - switchedAt <= SWITCH_HOLD_MS;
    }

    private void tick() {
        boolean adapting = false;
        waitingFirstFrame = false;
        runtimePage = "";
        try {
            int navigationMode = navigationMode(context);
            gestures = navigationMode == 2;
            boolean interactive = context.getSystemService(PowerManager.class).isInteractive();
            boolean locked = context.getSystemService(KeyguardManager.class).isKeyguardLocked();
            boolean unavailable = !enabled || !gestures || !interactive || locked;
            if (unavailable != suspended) {
                suspended = unavailable;
                if (previousWindow != null) synchronized (lock) { NavbarReflect.call(previousWindow, "notifyInsetsChanged"); }
            }
            if (unavailable) {
                String reason = !enabled ? "总开关关闭" : !gestures ? "导航模式 " + navigationMode : !interactive ? "屏幕已关闭" : "锁屏中";
                runtime("已暂停 · " + reason);
                publish(false, 0, 0);
                return;
            }
            Snapshot snapshot = snapshot();
            if (snapshot == null) {
                // The focused page was added but has no frame yet: hold briefly instead of restoring native.
                if (pendingWindow != null && holdsSwitch(false, publishedActive, pendingSince, SystemClock.uptimeMillis())) {
                    adapting = true;
                    waitingFirstFrame = true;
                    runtime("等待页面布局");
                    return;
                }
                runtime("当前窗口暂不适配");
                publish(false, 0, 0);
                return;
            }
            Object window = snapshot.window();
            Object raw = snapshot.raw();
            Rect frame = snapshot.frame(), bounds = snapshot.bounds();
            SurfaceControl surface = snapshot.surface();
            NavbarRules.Choice selected = choice(window, true);
            runtimePage = snapshot.component() + " · 模式=" + selected.mode() + " · " + selected.source();
            boolean landscape = frame.width() > frame.height();
            if (window != previousWindow) {
                previousWindow = window;
                stability.reset();
                switchedAt = SystemClock.uptimeMillis();
                sampledSinceSwitch = false;
                cacheTried = false;
                stableColor = 0;
                stableSamples = 0;
                synchronized (lock) { NavbarReflect.call(window, "notifyInsetsChanged"); }
            }
            // Home/recents owns the screen background, not the application's sampled color.
            // Keep only its navigation host transparent while task children supply card backgrounds.
            boolean launcherHandoff;
            synchronized (lock) { launcherHandoff = !windowLayers.isEmpty(); }
            if (clearsLauncherHost(landscape, launcherHandoff, snapshot.component())) {
                runtime("桌面／最近任务导航背景透明");
                frameNavHeight = 0;
                publish(true, 0, NavbarStore.FLOATING);
                return;
            }
            if (keepsOriginal(selected.mode(), landscape, selected.source(), snapshot.component())) {
                if (!"系统宿主".equals(selected.source())) runtime("保留系统原有 · " + selected.source());
                publish(false, 0, 0);
                return;
            }
            adapting = true;
            Insets nav = (Insets) insetsClass.getMethod("calculateInsets", Rect.class, Rect.class, int.class, boolean.class)
                    .invoke(raw, bounds, bounds, WindowInsets.Type.navigationBars(), landscape);
            frameNavHeight = nav.bottom;
            if (nav.bottom <= 0) { runtime("当前窗口没有底部导航区域"); publish(false, 0, 0); return; }
            if (landscape) {
                adapting = false;
                runtime("横屏导航条透明");
                publish(true, 0, NavbarStore.FLOATING);
                return;
            }
            if (selected.mode() == NavbarStore.FLOATING) { runtime("悬浮中 · " + selected.source()); publish(true, 0, NavbarStore.FLOATING); return; }
            if (snapshot.screenshot() && lastGood != null && lastGood.matches(window, selected.mode(), nav.bottom)) {
                lastGood = new Frame(window, lastGood.color(), lastGood.mode(), nav.bottom, SystemClock.uptimeMillis());
                runtime("截图期间保持颜色 · " + selected.source());
                publish(true, lastGood.color(), lastGood.mode());
                return;
            }
            String key = selected.color() == null
                    ? NavbarColorCache.key(snapshot.component(), selected.mode(), nav.bottom, snapshot.night(), landscape) : null;
            // Offered once per page switch, so an unsamplable page cannot alternate cached and native frames.
            Integer cached = cacheTried ? null : cache.get(key);
            cacheTried = true;
            if (cached != null) {
                // Shown before the synchronous readback; a different sample must then be confirmed.
                stability.seed(cached);
                runtime("沿用页面最近颜色 · " + selected.source());
                publish(true, cached, NavbarStore.COLOR);
            }
            int color = selected.color() == null
                    ? sample(surface, frame.width(), navigationTop(frame, bounds, nav.bottom)) : selected.color();
            boolean current;
            synchronized (lock) {
                Object display = NavbarReflect.call(window, "getDisplayContent");
                Object currentRaw = NavbarReflect.call(NavbarReflect.call(display, "getInsetsStateController"), "getRawInsetsState");
                current = enabled && eligible(window, display, currentRaw)
                        && frame.equals(NavbarReflect.call(window, "getFrame"))
                        && bounds.equals(NavbarReflect.call(display, "getBounds"));
            }
            if (!current) { requestTick(COALESCE_MS); return; }
            if (color == 0) {
                long now = SystemClock.uptimeMillis();
                if (lastGood != null && lastGood.canRetain(window, selected.mode(), nav.bottom, now)) {
                    runtime("暂用最近颜色 · " + selected.source());
                    publish(true, lastGood.color(), lastGood.mode());
                } else if (holdsSwitch(sampledSinceSwitch, publishedActive, switchedAt, now)) {
                    // The new page has no buffer yet: keep the current host frame (or the cached color
                    // just sent) instead of flashing the native background, and retry on its first draw.
                    waitingFirstFrame = true;
                    runtime("等待页面首帧 · " + selected.source());
                } else {
                    runtime("颜色采样暂不可用");
                    publish(false, 0, 0);
                }
                return;
            }
            sampledSinceSwitch = true;
            color = stability.choose(color);
            if (stability.needsConfirmation() || color != stableColor) {
                stableColor = color;
                stableSamples = stability.needsConfirmation() ? 0 : 1;
            } else stableSamples++;
            // Only a value seen on two consecutive samples is remembered for the next visit.
            if (stableSamples >= 2) cache.put(key, color);
            lastGood = new Frame(window, color, NavbarStore.COLOR, nav.bottom, SystemClock.uptimeMillis());
            runtime("取色中 · " + selected.source());
            publish(true, color, NavbarStore.COLOR);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (SystemClock.uptimeMillis() - lastFailure > 10_000) {
                lastFailure = SystemClock.uptimeMillis();
                android.util.Log.w("NativeNavbar", "System frame unavailable", failure);
            }
            runtime("系统接口异常 · " + failure.getClass().getSimpleName());
            publish(false, 0, 0);
        } finally {
            if (enabled && context.getSystemService(PowerManager.class).isInteractive()) {
                worker.removeCallbacks(tick);
                worker.postDelayed(tick, adapting ? (stability.needsConfirmation() || waitingFirstFrame ? COALESCE_MS : 160) : 1000);
            }
        }
    }

    static int navigationMode(Context context) {
        // SystemUI persists the actual mode here after navigation overlays change.
        // The early-created system context can retain the base framework resource value.
        int mode = android.provider.Settings.Secure.getInt(context.getContentResolver(), "navigation_mode", -1);
        if (mode >= 0 && mode <= 2) return mode;
        int id = context.getResources().getIdentifier("config_navBarInteractionMode", "integer", "android");
        return id == 0 ? -1 : context.getResources().getInteger(id);
    }

    /**
     * Diagnostic status only, sent from the worker outside the WM lock and only when the text changes.
     * A failed delivery (module process stopped) pauses reporting for 60s and never affects adaptation;
     * the latest text is sent after the pause or the next successful settings read.
     */
    private void runtime(String message) {
        if (!runtimePage.isEmpty()) message += " · " + runtimePage;
        if (message.equals(lastRuntime)) return;
        if (SystemClock.uptimeMillis() < statusPausedUntil || settings != null && !settings.reachable()) return;
        pendingRuntime = message;
        if (statusPending.compareAndSet(false, true)) statusWriter.execute(this::writeRuntime);
    }

    private void writeRuntime() {
        String message = pendingRuntime;
        try {
            Bundle status = new Bundle();
            status.putString("runtime", message);
            context.getContentResolver().call(Uri.parse("content://" + NavbarStore.AUTHORITY), "status", null, status);
            lastRuntime = message;
        } catch (RuntimeException unavailable) { statusPausedUntil = SystemClock.uptimeMillis() + 60_000; }
        finally {
            statusPending.set(false);
            if (!message.equals(pendingRuntime) && SystemClock.uptimeMillis() >= statusPausedUntil
                    && statusPending.compareAndSet(false, true)) statusWriter.execute(this::writeRuntime);
        }
    }

    private record Snapshot(Object window, Object raw, Rect frame, Rect bounds, SurfaceControl surface, boolean screenshot,
                            String component, boolean night) {}
    private Snapshot snapshot() throws ReflectiveOperationException {
        synchronized (lock) {
            Object display = NavbarReflect.call(NavbarReflect.field(wm, "mRoot"), "getDefaultDisplay");
            Object window = targetWindow(display, previousWindow);
            Object raw = NavbarReflect.call(NavbarReflect.call(display, "getInsetsStateController"), "getRawInsetsState");
            if (window == null || !eligible(window, display, raw)) {
                boolean laidOut = window == null || window == previousWindow || NavbarReflect.field(window, "mActivityRecord") == null
                        || !((Rect) NavbarReflect.call(window, "getFrame")).isEmpty();
                if (laidOut) pendingWindow = null;
                else if (window != pendingWindow) {
                    pendingWindow = window;
                    pendingSince = SystemClock.uptimeMillis();
                }
                return null;
            }
            pendingWindow = null;
            Object activity = NavbarReflect.field(window, "mActivityRecord");
            ComponentName component = activity == null ? null : (ComponentName) NavbarReflect.field(activity, "mActivityComponent");
            android.content.res.Configuration config = (android.content.res.Configuration) NavbarReflect.call(window, "getConfiguration");
            return new Snapshot(window, stateTools.copy(raw),
                    new Rect((Rect) NavbarReflect.call(window, "getFrame")),
                    new Rect((Rect) NavbarReflect.call(display, "getBounds")),
                    (SurfaceControl) NavbarReflect.call(window, "getSurfaceControl"),
                    screenshotFocused(display),
                    component == null ? null : component.flattenToShortString(),
                    config != null && (config.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                            == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        }
    }

    /** Application-layer strip only: never sample the host background or dump a stale view tree. */
    private int sample(SurfaceControl surface, int width, int navigationTop) {
        HardwareBuffer hardware = null;
        Bitmap bitmap = null, software = null;
        try {
            if (surface == null || !surface.isValid() || width < 24 || navigationTop < 12) return 0;
            Object builder = captureBuilder.newInstance(surface);
            crop.invoke(builder, new Rect(0, navigationTop - 12, width, navigationTop - 2));
            // Preserve actual edge pixels: downscaling the entire strip mixes unrelated content.
            scale.invoke(builder, 1f, 1f);
            Object buffer = capture.invoke(null, build.invoke(builder));
            if (buffer == null) return 0;
            hardware = (HardwareBuffer) NavbarReflect.call(buffer, "getHardwareBuffer");
            if ((Boolean) NavbarReflect.call(buffer, "containsSecureLayers")) return 0;
            bitmap = (Bitmap) NavbarReflect.call(buffer, "asBitmap");
            if (bitmap == null) return 0;
            software = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (software == null) return 0;
            int w = software.getWidth(), h = software.getHeight();
            if (w != width || h != 10) return 0;
            int[] sides = new int[200];
            software.getPixels(sides, 0, 10, 2, 0, 10, 10);
            software.getPixels(sides, 100, 10, w - 12, 0, 10, 10);
            int picked = NavbarColors.background(sides, 25);
            if (picked != 0) return picked;
            // Compatibility policy: a second low-variance bottom strip, not a guessed black/white.
            int step = Math.max(1, w * 3 / 500);
            int[] line = new int[((w + step - 1) / step) * 3];
            int index = 0;
            for (int y = h - 3; y < h; y++)
                for (int x = 0; x < w; x += step) line[index++] = software.getPixel(x, y);
            return NavbarColors.background(line, 25);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (SystemClock.uptimeMillis() - lastFailure > 10_000) {
                lastFailure = SystemClock.uptimeMillis();
                android.util.Log.w("NativeNavbar", "Color sample unavailable", failure);
            }
            return 0;
        } finally {
            if (software != null) software.recycle();
            if (bitmap != null) bitmap.recycle();
            if (hardware != null) hardware.close();
        }
    }

    /** A newer frame replaces an older one still queued for the same host, so a slow queue never replays stale colors. */
    static Bundle frameOptions() {
        return BroadcastOptions.makeBasic().setShareIdentityEnabled(true)
                .setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT).toBundle();
    }

    private void clearLayer(Object window) throws ReflectiveOperationException {
        NavbarWindowLayer layer = windowLayers.get(window);
        if (layer != null) { layer.clear(); windowLayers.remove(window); }
        layerBounds.remove(window);
    }

    /** Keep inactive windows' children for native task capture; their parent controls visibility. */
    private boolean updateWindowLayer(boolean active, int color, int mode) throws ReflectiveOperationException {
        synchronized (lock) {
            if (!windowLayersAvailable) return false;
            for (Object window : windowLayers.keySet().toArray()) {
                SurfaceControl parent = (SurfaceControl) NavbarReflect.call(window, "getSurfaceControl");
                Object display = NavbarReflect.call(window, "getDisplayContent");
                LayerGeometry geometry = layerBounds.get(window);
                WindowManager.LayoutParams attrs = (WindowManager.LayoutParams) NavbarReflect.field(window, "mAttrs");
                Object raw = display == null ? null : NavbarReflect.call(NavbarReflect.call(display, "getInsetsStateController"), "getRawInsetsState");
                Rect bounds = display == null ? null : (Rect) NavbarReflect.call(display, "getBounds");
                int height = bounds == null ? 0 : ((Insets) insetsClass.getMethod("calculateInsets", Rect.class, Rect.class, int.class, boolean.class)
                        .invoke(raw, bounds, bounds, WindowInsets.Type.navigationBars(), true)).bottom;
                if (!enabled || suspended || !gestures || !windowLayers.get(window).belongsTo(parent)
                        || (Integer) NavbarReflect.call(window, "getWindowingMode") != 1
                        || choice(window, false).mode() != NavbarStore.COLOR || geometry == null
                        || !geometry.frame().equals(NavbarReflect.call(window, "getFrame"))
                        || !geometry.display().equals(bounds) || geometry.height() != height
                        || !geometry.surfaceInsets().equals(NavbarReflect.field(attrs, "surfaceInsets"))
                        || ((Number) NavbarReflect.field(window, "mGlobalScale")).floatValue() != 1f
                        || !(Boolean) window.getClass().getMethod("isRequestedVisible", int.class).invoke(window, WindowInsets.Type.navigationBars())
                        || (attrs.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0) clearLayer(window);
            }
            Object window = previousWindow;
            if (!active || mode != NavbarStore.COLOR || window == null) {
                if (window != null && (active || targetWindow(NavbarReflect.call(window, "getDisplayContent"), window) == window))
                    clearLayer(window);
                return false;
            }
            Object display = NavbarReflect.call(window, "getDisplayContent");
            Object raw = NavbarReflect.call(NavbarReflect.call(display, "getInsetsStateController"), "getRawInsetsState");
            if (!eligible(window, display, raw) || ((Number) NavbarReflect.field(window, "mGlobalScale")).floatValue() != 1f) {
                clearLayer(window);
                return false;
            }
            Rect frame = (Rect) NavbarReflect.call(window, "getFrame");
            Rect bounds = (Rect) NavbarReflect.call(display, "getBounds");
            WindowManager.LayoutParams attrs = (WindowManager.LayoutParams) NavbarReflect.field(window, "mAttrs");
            Rect rectangle = NavbarWindowLayer.geometry(frame, bounds, frameNavHeight, (Rect) NavbarReflect.field(attrs, "surfaceInsets"));
            if (rectangle == null) { clearLayer(window); return false; }
            SurfaceControl parent = (SurfaceControl) NavbarReflect.call(window, "getSurfaceControl");
            NavbarWindowLayer layer = windowLayers.get(window);
            if (layer == null) {
                layer = layerPreflight != null ? layerPreflight : new NavbarWindowLayer();
                layerPreflight = null;
                windowLayers.put(window, layer);
            }
            layer.update(parent, rectangle, color);
            layerBounds.put(window, new LayerGeometry(new Rect(frame), new Rect(bounds),
                    new Rect((Rect) NavbarReflect.field(attrs, "surfaceInsets")), frameNavHeight));
            return layer.valid();
        }
    }

    private void publish(boolean active, int color, int mode) {
        if (context == null) return;
        boolean windowOwned = false;
        try { windowOwned = updateWindowLayer(active, color, mode); }
        catch (ReflectiveOperationException | RuntimeException failure) {
            android.util.Log.w("NativeNavbar", "Window color layer unavailable; using host fallback", failure);
        }
        if (!active) { stability.reset(); lastGood = null; }
        int height = active ? frameNavHeight : 0;
        long now = SystemClock.uptimeMillis();
        if (publishedActive == active && publishedColor == color && publishedMode == mode
                && publishedHeight == height && publishedWindowOwned == windowOwned && now - lastPublished < 750) return;
        publishedActive = active;
        publishedColor = color;
        publishedMode = mode;
        publishedHeight = height;
        publishedWindowOwned = windowOwned;
        lastPublished = now;
        Intent intent = new Intent(FRAME)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra("active", active).putExtra("color", color).putExtra("mode", mode)
                .putExtra("navHeight", height).putExtra("windowOwned", windowOwned);
        for (String host : new String[]{"com.android.systemui", "com.google.android.apps.nexuslauncher"}) {
            try {
                broadcast.invoke(context, new Intent(intent).setPackage(host), android.os.Process.myUserHandle(), "android.permission.STATUS_BAR_SERVICE",
                        frameOptions());
            } catch (ReflectiveOperationException failure) {
                android.util.Log.w("NativeNavbar", "Navigation host frame delivery failed", failure);
            }
        }
    }
}
