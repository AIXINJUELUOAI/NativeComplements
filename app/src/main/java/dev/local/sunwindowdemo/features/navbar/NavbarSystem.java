package dev.local.sunwindowdemo.features.navbar;

import android.app.*;
import android.content.*;
import android.database.ContentObserver;
import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.*;
import android.view.*;
import io.github.libxposed.api.*;
import java.lang.reflect.*;
import java.util.Map;

/** All Binder calls, view dumps and cropped GPU readbacks run outside the WM lock. */
final class NavbarSystem {
    static final String FRAME = "dev.nativecomplements.module.NAVBAR_FRAME";
    static final long FRAME_TIMEOUT_MS = 3000;
    private final Handler worker;
    private final Map<String, Long> versions = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile NavbarRules rules = new NavbarRules(Bundle.EMPTY);
    private volatile boolean enabled;
    private volatile boolean suspended = true;
    private volatile boolean gestures;
    private volatile boolean landscapeClear = true;
    private Object wm, lock;
    private volatile Object previousWindow;
    private Context context;
    private Rect control;
    private long treeTime;
    private long treeGeneration;
    private boolean treePending;
    private final java.util.concurrent.ExecutorService viewReader = java.util.concurrent.Executors.newSingleThreadExecutor();
    private long lastFailure;
    private String lastRuntime = "";
    private int frameNavHeight;
    private Frame lastGood;
    private long lastPublished;
    private boolean publishedActive;
    private int publishedColor, publishedMode, publishedHeight;
    private final java.util.concurrent.atomic.AtomicBoolean refreshPending = new java.util.concurrent.atomic.AtomicBoolean();
    private final NavbarColors.Stability stability = new NavbarColors.Stability();
    private final Runnable tick = this::tick;
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
        HandlerThread thread = new HandlerThread("NativeNavbar", android.os.Process.THREAD_PRIORITY_BACKGROUND);
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
                    if (event != draw || chain.getThisObject() == backend.previousWindow) backend.requestTick();
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
        long identity = Binder.clearCallingIdentity();
        try {
            Bundle config = context.getContentResolver().call(Uri.parse("content://" + NavbarStore.AUTHORITY), "system", null, null);
            if (config == null) throw new IllegalStateException("Missing navbar snapshot");
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
            Bundle status = new Bundle();
            status.putString("host", "系统框架已连接 · " + dev.local.sunwindowdemo.BuildConfig.VERSION_NAME);
            context.getContentResolver().call(Uri.parse("content://" + NavbarStore.AUTHORITY), "status", null, status);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            enabled = false;
            android.util.Log.w("NativeNavbar", "Settings unavailable; restoring native handling", failure);
            worker.postDelayed(this::reload, 5000);
        } finally { Binder.restoreCallingIdentity(identity); }
        if (previousWindow != null) {
            try { synchronized (lock) { NavbarReflect.call(previousWindow, "notifyInsetsChanged"); } }
            catch (ReflectiveOperationException ignored) {}
        }
        previousWindow = null;
        lastGood = null;
        treeGeneration++;
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
        return rules.resolve(pkg, component.getClassName(), version == null ? -1 : version);
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

    /** ScreenshotUI owns input temporarily while the same application still owns the visible page. */
    static Object targetWindow(Object display, Object previous) throws ReflectiveOperationException {
        Object focused = NavbarReflect.field(display, "mCurrentFocus");
        if (focused != null) {
            WindowManager.LayoutParams attrs = (WindowManager.LayoutParams) NavbarReflect.field(focused, "mAttrs");
            boolean screenshot = "com.android.systemui".equals(attrs.packageName)
                    && "ScreenshotUI".contentEquals(attrs.getTitle());
            if (!screenshot) return focused;
        }
        if (previous == null || NavbarReflect.field(previous, "mActivityRecord") == null
                || NavbarReflect.field(previous, "mActivityRecord") != NavbarReflect.field(display, "mFocusedApp")) return null;
        return (Boolean) NavbarReflect.call(previous, "isVisible") ? previous : null;
    }

    private void requestTick() {
        if (!enabled || context == null || !refreshPending.compareAndSet(false, true)) return;
        worker.postDelayed(() -> {
            refreshPending.set(false);
            worker.removeCallbacks(tick);
            tick();
        }, 64);
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

    private void tick() {
        boolean adapting = false;
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
            if (snapshot == null) { runtime("当前窗口暂不适配"); publish(false, 0, 0); return; }
            Object window = snapshot.window();
            Object raw = snapshot.raw();
            Rect frame = snapshot.frame(), bounds = snapshot.bounds();
            SurfaceControl surface = snapshot.surface();
            NavbarRules.Choice selected = choice(window, true);
            boolean landscape = frame.width() > frame.height();
            if (window != previousWindow) {
                previousWindow = window;
                control = null;
                treeTime = 0;
                treeGeneration++;
                stability.reset();
                synchronized (lock) { NavbarReflect.call(window, "notifyInsetsChanged"); }
            }
            if (selected.mode() == NavbarStore.ORIGINAL && (!landscape || "系统宿主".equals(selected.source()) || "系统窗口".equals(selected.source()))) {
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
            if (selected.color() == null && !treePending && SystemClock.uptimeMillis() - treeTime > 10_000)
                readControl(window, frame.width(), frame.height(), nav.bottom);
            int y = control == null ? frame.height() - nav.bottom - 2 : control.bottom - 3;
            int left = control == null ? frame.width() * 2 / 1000 : Math.max(0, control.left) + 29;
            int right = control == null ? frame.width() - frame.width() * 2 / 1000 : Math.min(frame.width(), control.right) - 29;
            int color = selected.color() == null ? sample(surface, left, right, y) : selected.color();
            boolean current;
            synchronized (lock) {
                Object display = NavbarReflect.call(window, "getDisplayContent");
                Object currentRaw = NavbarReflect.call(NavbarReflect.call(display, "getInsetsStateController"), "getRawInsetsState");
                current = enabled && eligible(window, display, currentRaw);
            }
            if (!current) { requestTick(); return; }
            if (color == 0) {
                if (lastGood != null && lastGood.canRetain(window, selected.mode(), nav.bottom, SystemClock.uptimeMillis())) {
                    runtime("暂用最近颜色 · " + selected.source());
                    publish(true, lastGood.color(), lastGood.mode());
                } else {
                    runtime("颜色采样暂不可用");
                    publish(false, 0, 0);
                }
                return;
            }
            color = stability.choose(color);
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
            if (enabled && context.getSystemService(PowerManager.class).isInteractive())
                worker.postDelayed(tick, adapting ? (stability.needsConfirmation() ? 64 : 160) : 1000);
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

    private void runtime(String message) {
        if (message.equals(lastRuntime)) return;
        lastRuntime = message;
        Bundle status = new Bundle();
        status.putString("runtime", message);
        try { context.getContentResolver().call(Uri.parse("content://" + NavbarStore.AUTHORITY), "status", null, status); }
        catch (RuntimeException ignored) {}
    }

    private record Snapshot(Object window, Object raw, Rect frame, Rect bounds, SurfaceControl surface, boolean screenshot) {}
    private Snapshot snapshot() throws ReflectiveOperationException {
        synchronized (lock) {
            Object display = NavbarReflect.call(NavbarReflect.field(wm, "mRoot"), "getDefaultDisplay");
            Object window = targetWindow(display, previousWindow);
            Object raw = NavbarReflect.call(NavbarReflect.call(display, "getInsetsStateController"), "getRawInsetsState");
            if (window == null || !eligible(window, display, raw)) return null;
            return new Snapshot(window, stateTools.copy(raw),
                    new Rect((Rect) NavbarReflect.call(window, "getFrame")),
                    new Rect((Rect) NavbarReflect.call(display, "getBounds")),
                    (SurfaceControl) NavbarReflect.call(window, "getSurfaceControl"),
                    NavbarReflect.field(display, "mCurrentFocus") != null && NavbarReflect.field(display, "mCurrentFocus") != window);
        }
    }

    private Rect bottomControl(Object window, int width, int height, int nav) {
        Object pipe = null;
        try {
            Class<?> pipeClass = Class.forName("com.android.internal.os.ByteTransferPipe");
            pipe = pipeClass.getConstructor().newInstance();
            ParcelFileDescriptor output = (ParcelFileDescriptor) pipeClass.getMethod("getWriteFd").invoke(pipe);
            Object client = NavbarReflect.field(window, "mClient");
            Class<?> clientClass = Class.forName("android.view.IWindow");
            clientClass.getMethod("executeCommand", String.class, String.class, ParcelFileDescriptor.class)
                    .invoke(client, "DUMP_ENCODED", null, output);
            byte[] bytes = (byte[]) pipeClass.getMethod("get").invoke(pipe);
            return NavbarViewTree.bottomControl(bytes, width, height, nav);
        } catch (ReflectiveOperationException | java.io.IOException | RuntimeException failure) { return null; }
        finally { if (pipe != null) try { NavbarReflect.call(pipe, "kill"); } catch (ReflectiveOperationException ignored) {} }
    }

    private void readControl(Object window, int width, int height, int nav) {
        treePending = true;
        treeTime = SystemClock.uptimeMillis();
        long generation = treeGeneration;
        viewReader.execute(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            Rect found = bottomControl(window, width, height, nav);
            worker.post(() -> {
                treePending = false;
                if (generation == treeGeneration && window == previousWindow) control = found;
            });
        });
    }

    private int sample(SurfaceControl surface, int left, int right, int y) {
        int width = right - left;
        HardwareBuffer hardware = null;
        Bitmap bitmap = null, software = null;
        try {
            if (surface == null || !surface.isValid() || width < 10 || y < 0) return 0;
            Object builder = captureBuilder.newInstance(surface);
            crop.invoke(builder, new Rect(left, y, right, y + 1));
            scale.invoke(builder, 48f / width, 1f);
            Object buffer = capture.invoke(null, build.invoke(builder));
            if (buffer == null) return 0;
            hardware = (HardwareBuffer) NavbarReflect.call(buffer, "getHardwareBuffer");
            if ((Boolean) NavbarReflect.call(buffer, "containsSecureLayers")) return 0;
            bitmap = (Bitmap) NavbarReflect.call(buffer, "asBitmap");
            if (bitmap == null) return 0;
            software = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (software == null) return 0;
            int[] pixels = new int[software.getWidth()];
            software.getPixels(pixels, 0, pixels.length, 0, 0, pixels.length, 1);
            return NavbarColors.background(pixels);
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

    private void publish(boolean active, int color, int mode) {
        if (context == null) return;
        if (!active) { stability.reset(); lastGood = null; }
        int height = active ? frameNavHeight : 0;
        long now = SystemClock.uptimeMillis();
        if (publishedActive == active && publishedColor == color && publishedMode == mode
                && publishedHeight == height && now - lastPublished < 750) return;
        publishedActive = active;
        publishedColor = color;
        publishedMode = mode;
        publishedHeight = height;
        lastPublished = now;
        Intent intent = new Intent(FRAME)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra("active", active).putExtra("color", color).putExtra("mode", mode)
                .putExtra("navHeight", height);
        for (String host : new String[]{"com.android.systemui", "com.google.android.apps.nexuslauncher"}) {
            try {
                broadcast.invoke(context, new Intent(intent).setPackage(host), android.os.Process.myUserHandle(), "android.permission.STATUS_BAR_SERVICE",
                        BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
            } catch (ReflectiveOperationException failure) {
                android.util.Log.w("NativeNavbar", "Navigation host frame delivery failed", failure);
            }
        }
    }
}
