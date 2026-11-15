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
    /** Labelling mode: every settled AUTO verdict is offered to the SystemUI chip for ✓/✗. */
    private volatile boolean labelMode;
    private long labelAt;
    private String labelLast = "";
    private Object wm, lock;
    private volatile Object previousWindow;
    private final Map<Object, NavbarWindowLayer> windowLayers = new java.util.IdentityHashMap<>();
    private boolean publishedWindowOwned;
    private NavbarWindowLayer layerPreflight;
    private record LayerGeometry(Rect frame, Rect display, Rect surfaceInsets, int height) {}
    private final Map<Object, LayerGeometry> layerBounds = new java.util.IdentityHashMap<>();
    private boolean windowLayersAvailable;
    private final ThreadLocal<NavbarRules.Choice> layoutChoice = new ThreadLocal<>();
    private Context context;
    private final java.util.concurrent.ExecutorService statusWriter = java.util.concurrent.Executors.newSingleThreadExecutor();
    private long lastFailure;
    private volatile String lastRuntime = "";
    private String loggedRuntime = "";
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
    static final String AUTO_SOURCE = "自动";
    /** Per-window classifier decision (COLOR or FLOATING); absent until the first classification. */
    private final Map<Object, Integer> autoModes = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    /** Last decision per component, so a revisited page starts in its previous mode without a jump. */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> autoCache = new java.util.concurrent.ConcurrentHashMap<>();
    private Object autoWindow;
    /** Last content thumbnail of the tracked window; a large change followed by a quiet frame is a refresh. */
    private int[] autoThumb, autoReference;
    /**
     * Some windows (Douyin: SurfaceView/video children) return an all-black image for any partial source
     * crop, while a crop of the whole window is correct. Every capture therefore takes the whole window at a
     * reduced scale and cuts regions in software. One 1/8 capture per tick serves the refresh thumbnail and
     * the colour row; the classifier takes one 1/2 capture per refresh.
     */
    private record Lowres(int[] px, int w, int h, float f) {}
    private static final float TICK_SCALE = 1 / 8f, CLASSIFY_SCALE = 1 / 2f;
    private Lowres tickLow;
    /** Our own fill child of the tracked window, excluded from every capture so it never judges itself. */
    private SurfaceControl[] captureExclude = new SurfaceControl[0];
    private Method excludeLayers;
    /**
     * The last floating application window keeps floating while home/recents has focus, so its recents card
     * and the live tile never show the black navigation background it draws once insets come back.
     */
    private volatile Object stickyFloating;
    /** uid/Activity -> the page currently draws its own navigation area (reported by the scoped app). */
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> pageAdapted = new java.util.concurrent.ConcurrentHashMap<>();
    /** Startup: the first STARTUP_MS of a window (splash, open-screen ad, loading) is neither learned nor allowed to override a known page. */
    private static final long STARTUP_MS = 1500;
    private long autoSince;
    private boolean autoSeeded, autoStartupRecheck;
    private NavbarSampling sampling;
    private boolean samplingTried;
    /** Fill history for automatic flicker marks. */
    private long fillChangedAt, fillPrevChangedAt;
    private String fillState = "", fillPrevState = "";
    private boolean tickLowTried;
    /** Colour row just above the navigation area from this tick's capture, for the stretched fill. */
    private volatile int[] stretchRow;
    private volatile int stretchColor;
    private boolean autoDirty;
    private long autoQuietUntil, autoThumbNanos;
    private int autoPending = -1, autoThumbs;
    private final Method sourceSize, sourceAt, sourceType, sourceVisible;
    private final Method capture, build, crop, scale;
    private final Method securePolicy, protectedPolicy;
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
        // Same as navbar-demo: protected/secure layers throw instead of returning a silently redacted black image.
        securePolicy = optional(builder, "setSecureContentPolicy");
        try { excludeLayers = builder.getMethod("setExcludeLayers", SurfaceControl[].class); }
        catch (NoSuchMethodException missing) { excludeLayers = null; }
        protectedPolicy = optional(builder, "setProtectedContentPolicy");
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
                NavbarTrace.w("Window layer preflight failed; retaining host fallback", failure);
            }
            Method removed = window.getDeclaredMethod("removeImmediately");
            module.hook(removed).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                try { if (backend.lock != null) synchronized (backend.lock) { backend.clearLayer(chain.getThisObject()); } }
                catch (ReflectiveOperationException | RuntimeException failure) {
                    NavbarTrace.w("Window layer cleanup pending", failure);
                }
                return chain.proceed();
            });
            // Preserve raw navigation sources for app Decor/IME visibility. Consumption is app-side only.
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
            backend.installTransparentLayout(module, loader, policy, window);
            NavbarTrace.enableRing();
            NavbarTrace.i("SYSTEM_HOOK_READY");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { NavbarTrace.w("System preflight failed", error); }
    }

    /** Server-side layout only: never mutates the application's persistent LayoutParams/Decor. */
    private void installTransparentLayout(XposedModule module, ClassLoader loader, Class<?> policy, Class<?> window) {
        java.util.List<XposedInterface.HookHandle> installed = new java.util.ArrayList<>();
        try {
            Class<?> frames = Class.forName("com.android.server.wm.DisplayFrames", false, loader);
            Class<?> layout = Class.forName("android.view.WindowLayout", false, loader);
            Class<?> clientFrames = Class.forName("android.window.ClientWindowFrames", false, loader);
            Method compute = layout.getDeclaredMethod("computeFrames", WindowManager.LayoutParams.class, insetsClass,
                    Rect.class, Rect.class, int.class, int.class, int.class, int.class, float.class, clientFrames);
            Method layoutWindow = policy.getDeclaredMethod("layoutWindowLw", window, window, frames);
            installed.add(module.hook(compute).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                NavbarRules.Choice selected = layoutChoice.get();
                if (selected == null) return chain.proceed();
                WindowManager.LayoutParams original = (WindowManager.LayoutParams) chain.getArg(0);
                WindowManager.LayoutParams adjusted = transparentLayout(original, selected);
                Object[] args = new Object[10];
                for (int i = 0; i < args.length; i++) args[i] = chain.getArg(i);
                args[0] = adjusted;
                return chain.proceed(args);
            }));
            installed.add(module.hook(layoutWindow).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                NavbarRules.Choice previous = layoutChoice.get();
                NavbarRules.Choice selected = null;
                try { selected = floatingFor(chain.getArg(0)); }
                catch (ReflectiveOperationException | RuntimeException failure) {
                    // Exact layout context could not be established: leave the native input untouched.
                }
                layoutChoice.set(selected);
                try { return chain.proceed(); }
                finally { if (previous == null) layoutChoice.remove(); else layoutChoice.set(previous); }
            }));
            // The frame alone is not enough: an application outside the LSPosed scope still sees the
            // navigation source, so its DecorView paints a (usually black) bar and pads its content.
            // Removing only the navigation-bar source from the state sent to that window makes the
            // native client lay out edge to edge itself; gesture sources and the IME are untouched.
            Class<?> insetsPolicy = Class.forName("com.android.server.wm.InsetsPolicy", false, loader);
            Method adjust = insetsPolicy.getDeclaredMethod("adjustInsetsForWindow", window(loader), insetsClass, boolean.class);
            installed.add(module.hook(adjust).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    NavbarRules.Choice selected = result == null ? null : floatingFor(chain.getArg(0));
                    if (selected != null && selected.consumesNavigationInsets()) return stateTools.strip(result);
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    // Keep the native state when the window context cannot be established.
                }
                return result;
            }));
            NavbarTrace.i("TRANSPARENT_LAYOUT_READY");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            installed.forEach(XposedInterface.HookHandle::unhook);
            NavbarTrace.w("Transparent server layout unavailable", error);
        }
    }

    private static Class<?> window(ClassLoader loader) throws ClassNotFoundException {
        return Class.forName("com.android.server.wm.WindowState", false, loader);
    }

    /** Floating choice for a full-screen portrait application window, or null for native handling. */
    private NavbarRules.Choice floatingFor(Object target) throws ReflectiveOperationException {
        if (!enabled || suspended || !gestures || context == null) return null;
        Object display = NavbarReflect.call(target, "getDisplayContent");
        if (display == null) return null;
        Object raw = NavbarReflect.call(NavbarReflect.call(display, "getInsetsStateController"), "getRawInsetsState");
        NavbarRules.Choice candidate = choice(target, false);
        WindowManager.LayoutParams attrs = (WindowManager.LayoutParams) NavbarReflect.field(target, "mAttrs");
        Rect bounds = (Rect) NavbarReflect.call(display, "getBounds");
        if (candidate.mode() != NavbarStore.FLOATING
                || attrs.type != WindowManager.LayoutParams.TYPE_BASE_APPLICATION
                || attrs.width != WindowManager.LayoutParams.MATCH_PARENT || attrs.height != WindowManager.LayoutParams.MATCH_PARENT
                || (Integer) NavbarReflect.call(target, "getDisplayId") != 0
                || bounds.height() < bounds.width()) return null;
        // Floating only changes layout and never reads pixels, so a secure page may follow an explicit rule.
        boolean secureAllowed = !AUTO_SOURCE.equals(candidate.source());
        if (eligible(target, display, raw, secureAllowed, false)) return candidate;
        return target == stickyFloating && launcherFocused(display) && eligible(target, display, raw, secureAllowed, true)
                ? candidate : null;
    }

    static WindowManager.LayoutParams transparentLayout(WindowManager.LayoutParams original, NavbarRules.Choice selected) throws ReflectiveOperationException {
        WindowManager.LayoutParams adjusted = new WindowManager.LayoutParams();
        adjusted.copyFrom(original);
        if (selected.policyFlags() == 0 || (selected.policyFlags() & 4) != 0) {
            adjusted.flags |= WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION;
            adjusted.systemUiVisibility |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        }
        if (selected.consumesNavigationInsets()) {
            int fit = (Integer) NavbarReflect.call(adjusted, "getFitInsetsTypes");
            WindowManager.LayoutParams.class.getMethod("setFitInsetsTypes", int.class)
                    .invoke(adjusted, fit & ~WindowInsets.Type.navigationBars());
        }
        return adjusted;
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
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    int uid = getSentFromUid();
                    String activity = i.getStringExtra("activity");
                    if (uid < android.os.Process.FIRST_APPLICATION_UID || activity == null || activity.length() > 300) return;
                    boolean adapted = i.getBooleanExtra("adapted", false);
                    String key = uid + "/" + activity;
                    Boolean before = pageAdapted.put(key, adapted);
                    if (pageAdapted.size() > 512) pageAdapted.clear();
                    if (before != null && before == adapted) return;
                    NavbarTrace.i("PAGE " + key + " adapted=" + adapted);
                    Object window = previousWindow;
                    if (window != null) synchronized (lock) {
                        try { NavbarReflect.call(window, "notifyInsetsChanged"); }
                        catch (ReflectiveOperationException | RuntimeException ignored) {}
                    }
                    worker.removeCallbacks(tick);
                    worker.post(tick);
                }
            }, new IntentFilter(NavbarApp.PAGE), null, worker, Context.RECEIVER_EXPORTED);
            IntentFilter packages = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
            packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
            packages.addDataScheme("package");
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) { reload(); }
            }, packages, null, worker, Context.RECEIVER_NOT_EXPORTED);
            reload();
        } catch (ReflectiveOperationException | RuntimeException error) {
            NavbarTrace.w("System connection pending", error);
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
            if (read.report()) NavbarTrace.i(read.summary());
            Bundle config = read.config();
            if (config == null) throw new IllegalStateException("Missing navbar settings");
            if (!read.live()) worker.postDelayed(retryReload, settings.retryDelay(5000));
            if (!read.live() && settingsApplied) return;
            rules = new NavbarRules(config);
            enabled = config.getBoolean("enabled", false);
            landscapeClear = config.getBoolean("landscape_clear", true);
            labelMode = config.getBoolean("label_mode", false);
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
                NavbarTrace.w("Settings unavailable; restoring native handling", failure);
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
        NavbarRules.Choice resolved = rules.resolve(pkg, component.getClassName(), version == null ? -1 : version,
                attrs.type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION);
        if (resolved.mode() != NavbarStore.AUTO) return resolved;
        // The app says this page draws its own navigation area: leave it exactly as the app made it.
        Object owner = NavbarReflect.call(window, "getOwningUid");
        if (Boolean.TRUE.equals(pageAdapted.get(owner + "/" + component.getClassName())))
            return new NavbarRules.Choice(NavbarStore.ORIGINAL, null, "页面自适配", 0);
        // Until the classifier has seen this window it keeps the native layout with sampled color.
        Integer mode = autoModes.get(window);
        if (mode == null) mode = autoCache.get(component.flattenToShortString());
        if (mode == null) mode = rules.seed(pkg, component.getClassName());
        return new NavbarRules.Choice(mode == null ? NavbarStore.COLOR : mode, null, AUTO_SOURCE, 0);
    }

    /**
     * Refresh-triggered single-frame layout classification. Every tick takes a 1/16 thumbnail of the content
     * above the navigation area; the full 320dp classification runs only on the window's first frame and after
     * a large content change has settled (page switch inside the same Activity, scroll end). A change of the
     * decision needs two agreeing classifications, except the window's first one. The relayout caused by our
     * own switch is ignored for 600ms. FLOATING windows are judged against the screen bottom, where their own
     * bottom bar now sits.
     * @return true when the decision changed and the window was asked to relayout.
     */
    private boolean classify(Object window, SurfaceControl surface, Rect frame, int navTop, int navHeight,
                             String component, int mode) throws ReflectiveOperationException {
        if (component == null) return false;
        android.content.res.Configuration config = (android.content.res.Configuration) NavbarReflect.call(window, "getConfiguration");
        float density = config == null ? 0 : config.densityDpi / 160f;
        int width = frame.width();
        if (density <= 0 || navTop <= 0 || width < 64) return false;
        long now = SystemClock.uptimeMillis();
        if (window != autoWindow) {
            autoWindow = window; autoThumb = null; autoReference = null; autoDirty = true; autoPending = -1; autoQuietUntil = 0;
            autoSince = now; autoStartupRecheck = true;
            ComponentName name = ComponentName.unflattenFromString(component);
            autoSeeded = autoModes.get(window) == null && name != null
                    && (autoCache.containsKey(component) || rules.seed(name.getPackageName(), name.getClassName()) != null);
            NavbarTrace.i("AUTO window " + component + " mode=" + label(mode));
        }
        long t0 = System.nanoTime();
        Lowres low = low(surface, frame);
        int[] thumb = low == null ? null : rows(low, 0, Math.min(low.h(), (int) (navTop * low.f())));
        autoThumbNanos += System.nanoTime() - t0;
        if (++autoThumbs >= 64) {
            NavbarTrace.i("AUTO thumb avg="
                    + String.format(java.util.Locale.ROOT, "%.2f", autoThumbNanos / 1e6 / autoThumbs) + "ms");
            autoThumbs = 0; autoThumbNanos = 0;
        }
        if (thumb == null) { autoLog("AUTO thumb unavailable " + component); return false; }
        int[] previousThumb = autoThumb;
        autoThumb = thumb;
        if (now < autoQuietUntil) { autoDirty = false; autoReference = thumb; return false; }
        boolean startup = now - autoSince < STARTUP_MS;
        if (!startup && autoStartupRecheck) { autoStartupRecheck = false; autoDirty = true; }
        // Only a quiet frame is judged; a refresh is a settled frame that differs from the last judged one,
        // so slow or dark-on-dark transitions are caught however gradually they animate.
        if (previousThumb == null || previousThumb.length != thumb.length || changed(previousThumb, thumb) > 0.08) return false;
        if (!autoDirty) {
            double change = autoReference == null || autoReference.length != thumb.length ? 1 : changed(autoReference, thumb);
            if (change <= 0.25) return false;
            NavbarTrace.i("AUTO refresh " + component
                    + " change=" + String.format(java.util.Locale.ROOT, "%.2f", change));
            autoDirty = true;
        }

        long t1 = System.nanoTime();
        boolean floating = mode == NavbarStore.FLOATING;
        int bottom = floating ? frame.height() : navTop;
        int top = Math.max(0, bottom - (int) Math.ceil(320 * density));
        int navRows = floating ? 0 : Math.max(0, Math.min(frame.height(), navTop + navHeight) - navTop);
        Lowres half = capture(surface, frame, CLASSIFY_SCALE);
        if (half == null || !opaque(half.px())) {
            autoLog("AUTO pixels unavailable " + component + (half == null ? " capture" : " nonopaque"));
            return false;
        }
        float hd = density * half.f();
        int hBottom = Math.min(half.h(), Math.round(bottom * half.f()));
        int hTop = Math.max(0, hBottom - (int) Math.ceil(320 * hd));
        int hNavTop = Math.round(navTop * half.f());
        int hNavRows = floating ? 0 : Math.max(0, Math.min(half.h(), Math.round((navTop + navRows) * half.f())) - hNavTop);
        int[] roi = rows(half, hTop, hBottom);
        int[] nav = hNavRows == 0 ? new int[0] : rows(half, hNavTop, hNavTop + hNavRows);
        Bundle verdict = NavbarLayout.analyze(roi, half.w(), hBottom - hTop, hd, nav, half.w(), hNavRows);
        String decision = verdict.getString("reviewDecision", "INCONCLUSIVE");
        if ("app_draws_under_navigation".equals(verdict.getString("reviewReason")) && busyRows(nav, half.w(), hNavRows) < 0.3) {
            // A divider line or a few stray pixels: judge the content above the boundary alone.
            verdict = NavbarLayout.analyze(roi, half.w(), hBottom - hTop, hd, new int[0], half.w(), 0);
            decision = verdict.getString("reviewDecision", "INCONCLUSIVE");
        }
        int next = "app_draws_under_navigation".equals(verdict.getString("reviewReason")) ? NavbarStore.ORIGINAL
                : "EXTEND".equals(decision) ? NavbarStore.COLOR : "NATIVE".equals(decision) ? NavbarStore.FLOATING : -1;
        long ms = (System.nanoTime() - t1) / 1_000_000;
        Integer current = autoModes.get(window);
        NavbarTrace.i("AUTO check " + component + " mode=" + label(mode) + " -> " + decision
                + " reason=" + verdict.getString("reviewReason") + " pending=" + autoPending + " ms=" + ms);
        if (next < 0) { autoDirty = false; autoPending = -1; autoReference = thumb; return false; }
        if (!startup) learn(component, next);
        if (startup && autoSeeded && next != mode) {
            // A known page keeps its learned mode while splash/ads/loading frames are on screen.
            autoDirty = false; autoPending = -1; autoReference = thumb;
            NavbarTrace.i("AUTO startup keep " + component + " " + label(mode) + " (saw " + label(next) + ")");
            return false;
        }
        if (next == mode) {
            if (!startup) offerLabel(component, next);
            autoDirty = false; autoPending = -1; autoReference = thumb;
            if (current == null) {
                autoModes.put(window, next); autoCache.put(component, next);
                NavbarTrace.i("AUTO " + component + " = " + label(next)
                        + " reason=" + verdict.getString("reviewReason") + " ms=" + ms);
            }
            return false;
        }
        if (current != null && autoPending != next) {
            autoPending = next;        // stay dirty: confirm on the next quiet tick
            return false;
        }
        autoDirty = false; autoPending = -1; autoQuietUntil = now + 600; autoReference = thumb;
        autoModes.put(window, next);
        if (autoCache.size() > 256) autoCache.clear();
        autoCache.put(component, next);
        offerLabel(component, next);
        NavbarTrace.i("AUTO " + component + " " + label(mode) + " -> " + label(next)
                + " reason=" + verdict.getString("reviewReason") + " ms=" + ms);
        synchronized (lock) {
            NavbarReflect.call(window, "notifyInsetsChanged");
            NavbarReflect.call(wm, "requestTraversal");
        }
        return true;
    }

    private long autoLogAt;
    /** At most one repeated failure line per 2s. */
    private void autoLog(String message) {
        long now = SystemClock.uptimeMillis();
        if (now - autoLogAt < 2000) return;
        autoLogAt = now;
        NavbarTrace.i(message);
    }

    private void offerLabel(String component, int mode) {
        if (!labelMode || context == null) return;
        ComponentName name = ComponentName.unflattenFromString(component);
        if (name == null) return;
        long now = SystemClock.uptimeMillis();
        String key = component + "=" + mode;
        if (key.equals(labelLast) && now - labelAt < 800) return;
        labelLast = key; labelAt = now;
        Intent intent = new Intent(NavbarLabelHost.LABEL).setPackage("com.android.systemui")
                .putExtra("package", name.getPackageName()).putExtra("activity", name.getClassName()).putExtra("mode", mode);
        try {
            // SystemUI accepts the chip only from uid 1000, which it can see only when identity is shared.
            broadcast.invoke(context, intent, android.os.Process.myUserHandle(), "android.permission.STATUS_BAR_SERVICE",
                    BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
            NavbarTrace.i("LABEL offer " + component + " " + label(mode));
        } catch (ReflectiveOperationException | RuntimeException failure) { NavbarTrace.w("label offer failed", failure); }
    }

    /** One stable vote per classification, persisted by the module (never during startup). */
    private void learn(String component, int mode) {
        ComponentName name = ComponentName.unflattenFromString(component);
        if (name == null || context == null) return;
        Long version = versions.get(name.getPackageName());
        String key = name.getPackageName() + "/" + name.getClassName();
        long code = version == null ? -1 : version;
        statusWriter.execute(() -> {
            try {
                Bundle vote = new Bundle();
                vote.putString("component", key);
                vote.putLong("version", code);
                vote.putInt("mode", mode);
                context.getContentResolver().call(Uri.parse("content://" + NavbarStore.AUTHORITY), "learn", null, vote);
            } catch (RuntimeException unavailable) { /* module stopped: the vote is simply lost */ }
        });
    }

    /** Marks fill changes that revert within a short time: active gaps, mode ping-pong and layer rebuilds. */
    private void watchFill(boolean active, int mode, boolean windowOwned) {
        String state = (active ? "on" : "off") + "/" + label(mode) + "/" + (windowOwned ? "layer" : "host");
        if (state.equals(fillState)) return;
        long now = SystemClock.uptimeMillis();
        if (state.equals(fillPrevState) && now - fillPrevChangedAt < 600)
            NavbarTrace.i("FLICKER " + fillPrevState + " -> " + fillState + " -> " + state
                    + " in " + (now - fillPrevChangedAt) + "ms · " + runtimePage);
        NavbarTrace.i("FILL " + state + " · " + runtimePage);
        fillPrevState = fillState; fillPrevChangedAt = fillChangedAt;
        fillState = state; fillChangedAt = now;
    }

    private void follow(Rect area) {
        if (!samplingTried) {
            samplingTried = true;
            try { sampling = new NavbarSampling(); } catch (LinkageError unavailable) { sampling = null; }
        }
        if (sampling != null) sampling.follow(area, worker::post);
    }

    private static String label(int mode) {
        return mode == NavbarStore.FLOATING ? "floating" : mode == NavbarStore.ORIGINAL ? "original" : "extend";
    }

    /** Share of navigation-area rows the app itself paints non-uniformly (content, gradient, blur). */
    static double busyRows(int[] nav, int width, int rows) {
        if (rows <= 0 || width <= 0) return 0;
        int busy = 0;
        for (int r = 0; r < rows; r++) {
            int base = nav[r * width], near = 0;
            for (int x = 0; x < width; x++) {
                int c = nav[r * width + x];
                if (Math.abs(((c >> 16) & 255) - ((base >> 16) & 255)) <= 12 && Math.abs(((c >> 8) & 255) - ((base >> 8) & 255)) <= 12
                        && Math.abs((c & 255) - (base & 255)) <= 12) near++;
            }
            if (near < width * 0.9) busy++;
        }
        return busy / (double) rows;
    }

    /** Fraction of thumbnail pixels whose largest channel moved by more than 16 (dark pages differ by ~30). */
    static double changed(int[] a, int[] b) {
        int moved = 0;
        for (int i = 0; i < a.length; i++) {
            int x = a[i], y = b[i];
            int d = Math.max(Math.abs(((x >> 16) & 255) - ((y >> 16) & 255)),
                    Math.max(Math.abs(((x >> 8) & 255) - ((y >> 8) & 255)), Math.abs((x & 255) - (y & 255))));
            if (d > 16) moved++;
        }
        return a.length == 0 ? 0 : moved / (double) a.length;
    }

    private static Method optional(Class<?> type, String name) {
        try { return type.getMethod(name, int.class); } catch (NoSuchMethodException missing) { return null; }
    }

    private Object captureArgs(SurfaceControl surface) throws ReflectiveOperationException {
        Object builder = captureBuilder.newInstance(surface);
        if (securePolicy != null) securePolicy.invoke(builder, 2);
        if (protectedPolicy != null) protectedPolicy.invoke(builder, 2);
        SurfaceControl[] exclude = captureExclude;
        if (excludeLayers != null && exclude.length > 0) excludeLayers.invoke(builder, (Object) exclude);
        return builder;
    }

    private long captureLogAt;
    private void captureFailed(String where, Throwable failure) {
        long now = SystemClock.uptimeMillis();
        if (now - captureLogAt < 3000) return;
        captureLogAt = now;
        Throwable cause = failure instanceof java.lang.reflect.InvocationTargetException && failure.getCause() != null
                ? failure.getCause() : failure;
        NavbarTrace.i("CAPTURE " + where + " failed: " + cause);
    }

    /** This tick's 1/8 whole-window capture, taken at most once per tick. */
    private Lowres low(SurfaceControl surface, Rect frame) {
        if (!tickLowTried) { tickLowTried = true; tickLow = capture(surface, frame, TICK_SCALE); }
        return tickLow;
    }

    private Lowres capture(SurfaceControl surface, Rect frame, float factor) {
        HardwareBuffer hardware = null;
        Bitmap bitmap = null, software = null;
        try {
            if (surface == null || !surface.isValid() || frame.isEmpty()) return null;
            Object builder = captureArgs(surface);
            crop.invoke(builder, new Rect(0, 0, frame.width(), frame.height()));
            scale.invoke(builder, factor, factor);
            Object buffer = capture.invoke(null, build.invoke(builder));
            if (buffer == null) return null;
            hardware = (HardwareBuffer) NavbarReflect.call(buffer, "getHardwareBuffer");
            if ((Boolean) NavbarReflect.call(buffer, "containsSecureLayers")) return null;
            bitmap = (Bitmap) NavbarReflect.call(buffer, "asBitmap");
            if (bitmap == null) return null;
            software = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (software == null) return null;
            int w = software.getWidth(), h = software.getHeight();
            int[] out = new int[w * h];
            software.getPixels(out, 0, w, 0, 0, w, h);
            return new Lowres(out, w, h, w / (float) frame.width());
        } catch (ReflectiveOperationException | RuntimeException failure) {
            captureFailed("window@" + factor, failure);
            return null;
        } finally {
            if (software != null) software.recycle();
            if (bitmap != null) bitmap.recycle();
            if (hardware != null) hardware.close();
        }
    }

    private static int[] rows(Lowres image, int from, int to) {
        from = Math.max(0, from); to = Math.min(image.h(), to);
        if (to <= from) return new int[0];
        int[] out = new int[(to - from) * image.w()];
        System.arraycopy(image.px(), from * image.w(), out, 0, out.length);
        return out;
    }

    private static boolean opaque(int[] pixels) {
        for (int pixel : pixels) if ((pixel >>> 24) != 255) return false;
        return true;
    }

    private boolean eligible(Object window, Object display, Object raw) throws ReflectiveOperationException {
        return eligible(window, display, raw, false, false);
    }

    private static boolean launcherFocused(Object display) throws ReflectiveOperationException {
        Object focused = NavbarReflect.field(display, "mCurrentFocus");
        if (focused == null) return true;   // mid-transition into recents
        String pkg = ((WindowManager.LayoutParams) NavbarReflect.field(focused, "mAttrs")).packageName;
        return "com.google.android.apps.nexuslauncher".equals(pkg) || "com.android.systemui".equals(pkg);
    }

    private boolean eligible(Object window, Object display, Object raw, boolean allowSecure, boolean ignoreFocus)
            throws ReflectiveOperationException {
        WindowManager.LayoutParams attrs = (WindowManager.LayoutParams) NavbarReflect.field(window, "mAttrs");
        Rect frame = (Rect) NavbarReflect.call(window, "getFrame");
        return attrs.type >= 1 && attrs.type <= 99 && (allowSecure || (attrs.flags & WindowManager.LayoutParams.FLAG_SECURE) == 0)
                && (Integer) NavbarReflect.call(window, "getWindowingMode") == 1 && frame.height() > 0 && frame.width() > 0
                && (ignoreFocus || targetWindow(display, previousWindow) == window)
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
            // ScreenshotUI, the notification shade / control centre and other SystemUI panels sit on top of the
            // app without owning the bottom: keep the app (same focused activity) as the target.
            boolean systemOverlay = "com.android.systemui".equals(attrs.packageName);
            if (!systemOverlay) {
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
        tickLow = null;
        tickLowTried = false;
        captureExclude = new SurfaceControl[0];
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
            synchronized (lock) {
                NavbarWindowLayer own = windowLayers.get(window);
                SurfaceControl child = own == null ? null : own.surface();
                captureExclude = child == null ? new SurfaceControl[0] : new SurfaceControl[]{child};
            }
            Object sticky = stickyFloating;
            if (sticky != null && sticky != window) {
                // Another application owns the bottom now: give the old window its native insets back.
                stickyFloating = null;
                synchronized (lock) { NavbarReflect.call(sticky, "notifyInsetsChanged"); }
            }
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
            if (AUTO_SOURCE.equals(selected.source()) && selected.mode() == NavbarStore.ORIGINAL && !landscape) {
                Insets own = (Insets) insetsClass.getMethod("calculateInsets", Rect.class, Rect.class, int.class, boolean.class)
                        .invoke(raw, bounds, bounds, WindowInsets.Type.navigationBars(), false);
                if (own.bottom > 0 && classify(window, surface, frame, navigationTop(frame, bounds, own.bottom), own.bottom,
                        snapshot.component(), NavbarStore.ORIGINAL)) selected = choice(window, true);
                if (selected.mode() == NavbarStore.ORIGINAL) {
                    runtime("页面已自行适配 · 保留原有");
                    publish(false, 0, 0);
                    return;
                }
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
            if (!landscape && AUTO_SOURCE.equals(selected.source())) {
                int boundary = bounds.bottom - nav.bottom;
                android.content.res.Configuration config = (android.content.res.Configuration) NavbarReflect.call(window, "getConfiguration");
                int span = config == null ? 0 : (int) Math.ceil(320 * config.densityDpi / 160f);
                follow(span <= 0 ? null : new Rect(bounds.left, Math.max(bounds.top, boundary - span), bounds.right, boundary));
            } else follow(null);
            if (landscape) {
                adapting = false;
                runtime("横屏导航条透明");
                publish(true, 0, NavbarStore.FLOATING);
                return;
            }
            if (AUTO_SOURCE.equals(selected.source())
                    && classify(window, surface, frame, navigationTop(frame, bounds, nav.bottom), nav.bottom, snapshot.component(),
                        selected.mode()))
                selected = choice(window, true);
            if (selected.mode() == NavbarStore.ORIGINAL) { runtime("页面已自行适配 · 保留原有"); publish(false, 0, 0); return; }
            if (selected.mode() == NavbarStore.FLOATING) { stickyFloating = window; runtime("悬浮中 · " + selected.source()); publish(true, 0, NavbarStore.FLOATING); return; }
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
                    ? sample(low(surface, frame), navigationTop(frame, bounds, nav.bottom)) : selected.color();
            if (selected.color() != null) stretchRow = null;
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
            runtime("取色中 #" + Integer.toHexString(color) + " · " + selected.source());
            publish(true, color, NavbarStore.COLOR);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (SystemClock.uptimeMillis() - lastFailure > 10_000) {
                lastFailure = SystemClock.uptimeMillis();
                NavbarTrace.w("System frame unavailable", failure);
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
        if (!message.equals(loggedRuntime)) { loggedRuntime = message; NavbarTrace.i("STATE " + message); }
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

    /**
     * The 1/8 row lying wholly above the navigation boundary (average of the last ~8 app rows). Its dominant
     * colour feeds the host fallback; the row itself is stretched down over the navigation area.
     */
    private int sample(Lowres low, int navigationTop) {
        stretchRow = null;
        if (low == null || navigationTop < 16) return 0;
        int y = (int) Math.floor(navigationTop * low.f()) - 1;
        if (y < 0 || y >= low.h()) return 0;
        int[] row = rows(low, y, y + 1);
        if (!opaque(row)) return 0;
        stretchRow = row;
        int picked = NavbarColors.background(row, 25);
        if (picked != 0) return stretchColor = picked;
        long r = 0, g = 0, b = 0;
        for (int c : row) { r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; }
        int n = row.length;
        return stretchColor = 0xff000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
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
            int[] row = stretchRow;
            layer.update(parent, rectangle, color, row != null && stretchColor == color ? row : null);
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
            NavbarTrace.w("Window color layer unavailable; using host fallback", failure);
        }
        if (!active) { stability.reset(); lastGood = null; }
        watchFill(active, mode, windowOwned);
        if (!active) follow(null);
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
                NavbarTrace.w("Navigation host frame delivery failed", failure);
            }
        }
    }
}
