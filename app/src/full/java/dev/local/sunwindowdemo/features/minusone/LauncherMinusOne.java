package dev.local.sunwindowdemo.features.minusone;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.view.View;
import dev.local.sunwindowdemo.core.LauncherTargets;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.WeakHashMap;
import java.lang.ref.WeakReference;
import java.util.function.Consumer;

public final class LauncherMinusOne {
    private static final String ACTION = "com.android.launcher3.WINDOW_OVERLAY";
    private static final String PACKAGE = "dev.nativecomplements.module";
    private static final String ACTIVITY = PACKAGE + ".MinusOneOverlay";
    private static final String DISCOVER = "com.google.android.googlequicksearchbox";
    private static final long LAUNCHER_VERSION = 907;
    private static WeakReference<Object> oseManager = new WeakReference<>(null);

    private LauncherMinusOne() {}

    public static boolean supported(Context context) {
        if (Build.VERSION.SDK_INT != 37 || !LauncherTargets.PIXEL.equals(LauncherTargets.home(context))) return false;
        try {
            return context.getPackageManager().getPackageInfo(LauncherTargets.PIXEL, 0)
                    .getLongVersionCode() == LAUNCHER_VERSION;
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return false;
        }
    }

    public static void install(XposedModule module, ClassLoader loader, Consumer<String> note) {
        if (Build.VERSION.SDK_INT != 37) return;
        try {
            var application = Class.forName("com.android.launcher3.LauncherApplication", false, loader);
            module.hook(application.getDeclaredMethod("onCreate"))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        var app = (Application) chain.getThisObject();
                        if (LauncherTargets.PIXEL.equals(app.getPackageName())
                                && supported(app)) {
                            connect(module, loader, app, note);
                        }
                        return chain.proceed();
                    });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            note.accept("MINUS_ONE_UNAVAILABLE " + unavailable.getClass().getSimpleName());
        }
    }

    private static void connect(XposedModule module, ClassLoader loader, Application app, Consumer<String> note) {
        try {
            var manager = Class.forName("android.app.ApplicationPackageManager", false, loader);
            var resolve = manager.getMethod("resolveActivity", Intent.class, int.class);
            var oseType = Class.forName("com.android.launcher3.qsb.OSEManager", false, loader);
            var reload = oseType.getDeclaredMethod("reloadOse");
            var launcher = Class.forName("com.android.launcher3.Launcher", false, loader);
            var recreateOverlay = launcher.getMethod("recreateOverlay");
            var nexus = Class.forName("com.google.android.apps.nexuslauncher.NexusLauncherActivity", false, loader);
            var chooseOverlay = nexus.getDeclaredMethod("getDefaultOverlay");
            var item = Class.forName("com.android.launcher3.Item", false, loader);
            var prefKey = item.getMethod("getSharedPrefKey");
            var prefs = Class.forName("com.android.launcher3.LauncherPrefs", false, loader);
            var readPreference = prefs.getMethod("getInner", item, Object.class);
            ThreadLocal<Boolean> selectingOwnOverlay = new ThreadLocal<>();
            module.hook(chooseOverlay).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Boolean previous = selectingOwnOverlay.get();
                selectingOwnOverlay.set(supported(app) && MinusOneSettings.enabledForLauncher(app));
                try { return chain.proceed(); }
                finally {
                    if (previous == null) selectingOwnOverlay.remove();
                    else selectingOwnOverlay.set(previous);
                }
            });
            module.hook(readPreference).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (Boolean.TRUE.equals(selectingOwnOverlay.get())
                        && "pref_enable_minus_one".equals(prefKey.invoke(chain.getArg(0)))) return true;
                return chain.proceed();
            });
            if (!module.deoptimize(chooseOverlay)) throw new IllegalStateException("overlay selection deoptimize");
            module.hook(reload).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                oseManager = new WeakReference<>(chain.getThisObject());
                return chain.proceed();
            });
            module.hook(resolve).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                var intent = (Intent) chain.getArg(0);
                if (intent == null || !ACTION.equals(intent.getAction()) || intent.getComponent() != null
                        || (intent.getPackage() != null && !DISCOVER.equals(intent.getPackage()))) {
                    return chain.proceed();
                }
                var stock = (ResolveInfo) chain.proceed();
                if (!supported(app) || !MinusOneSettings.enabledForLauncher(app)) return stock;
                if (stock != null && stock.activityInfo != null
                        && !DISCOVER.equals(stock.activityInfo.packageName)) return stock;
                try {
                    var redirect = new Intent(intent).setPackage(PACKAGE);
                    var target = app.getPackageManager().resolveActivity(redirect,
                            PackageManager.ResolveInfoFlags.of(Integer.toUnsignedLong((Integer) chain.getArg(1))));
                    if (target != null && isOurOverlay(target.activityInfo)) {
                        note.accept("MINUS_ONE_TARGET_RESOLVED");
                        return target;
                    }
                    note.accept("MINUS_ONE_TARGET_MISSING");
                } catch (RuntimeException unavailable) {
                    note.accept("MINUS_ONE_RESOLVE_FAILED");
                }
                return stock;
            });
            if (!module.deoptimize(reload)) throw new IllegalStateException("OSE reload deoptimize");
            watchGrid(app, loader, reload, recreateOverlay, note);
            LauncherWidgetPicker.install(module, loader, app, note);
            installTransparentStartingView(module, loader, note);
            installOverlayScrim(module, loader, note);
            note.accept("MINUS_ONE_READY");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            note.accept("MINUS_ONE_CONNECT_FAILED " + unavailable.getClass().getSimpleName());
        }
    }

    private static boolean isOurOverlay(ActivityInfo target) {
        return target != null && PACKAGE.equals(target.packageName) && ACTIVITY.equals(target.name);
    }

    private static void installOverlayScrim(XposedModule module, ClassLoader loader, Consumer<String> note) {
        var hooks = new java.util.ArrayList<XposedInterface.HookHandle>();
        try {
            var manager = Class.forName("com.google.android.apps.nexuslauncher.overlay.TaskFragmentOverlayManager", false, loader);
            var rootType = Class.forName("com.android.launcher3.LauncherRootView", false, loader);
            var scrimType = Class.forName("com.android.launcher3.graphics.SysUiScrim", false, loader);
            var target = field(manager, "k");
            var activity = field(manager.getSuperclass(), "c");
            var getRoot = activity.getType().getMethod("getRootView");
            var scrim = field(rootType, "mSysUiScrim");
            var topBounds = field(scrimType, "mTopMaskRect");
            var progressByRoot = new WeakHashMap<View, Float>();
            hooks.add(module.hook(manager.getDeclaredMethod("onOverlayScrollChanged", float.class))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        Object owner = chain.getThisObject();
                        if (isOurOverlay((ActivityInfo) target.get(owner))) {
                            var root = (View) getRoot.invoke(activity.get(owner));
                            progressByRoot.put(root, (Float) chain.getArg(0));
                            root.invalidate();
                        }
                        return chain.proceed();
                    }));
            hooks.add(module.hook(manager.getDeclaredMethod("onActivityDestroyed"))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        Object owner = chain.getThisObject();
                        if (isOurOverlay((ActivityInfo) target.get(owner))) {
                            var root = (View) getRoot.invoke(activity.get(owner));
                            progressByRoot.remove(root);
                            root.invalidate();
                        }
                        return chain.proceed();
                    }));
            hooks.add(module.hook(rootType.getDeclaredMethod("dispatchDraw", Canvas.class))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        var root = (View) chain.getThisObject();
                        Float progress = progressByRoot.get(root);
                        if (progress == null || progress <= 0) return chain.proceed();
                        var bounds = (RectF) topBounds.get(scrim.get(root));
                        float left = bounds.left, right = bounds.right;
                        // The transparent feed must not reveal a second shadow when native
                        // input preparation briefly shows the launcher during vertical drags.
                        hideTopScrim(bounds);
                        try { return chain.proceed(); }
                        finally { bounds.left = left; bounds.right = right; }
                    }));
            note.accept("MINUS_ONE_SCRIM_READY");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            for (var hook : hooks) hook.unhook();
            note.accept("MINUS_ONE_SCRIM_UNAVAILABLE " + unavailable.getClass().getSimpleName());
        }
    }

    static void hideTopScrim(RectF bounds) {
        bounds.right = bounds.left;
    }

    private static void watchGrid(Application app, ClassLoader loader, Method reload, Method recreateOverlay,
            Consumer<String> note) {
        var applied = new WeakHashMap<Activity, Boolean>();
        var resumed = new java.util.concurrent.atomic.AtomicReference<>(new WeakReference<Activity>(null));
        java.util.function.Consumer<Activity> refresh = activity -> {
            if (activity == null || activity.isDestroyed() || activity.isFinishing()
                    || !app.getSystemService(UserManager.class).isUserUnlocked()) return;
            boolean enabled = MinusOneSettings.enabledForLauncher(app);
            Boolean previous = applied.get(activity);
            if (Boolean.valueOf(enabled).equals(previous)) return;
            Object ose = oseManager.get();
            if (ose == null) return;
            try {
                if (enabled || Boolean.TRUE.equals(previous)) {
                    reload.invoke(ose);
                    recreateOverlay.invoke(activity);
                    note.accept("MINUS_ONE_OVERLAY_REFRESHED enabled=" + enabled);
                }
                applied.put(activity, enabled);
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                note.accept("MINUS_ONE_REFRESH_UNAVAILABLE " + unavailable.getClass().getSimpleName());
            }
        };
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                Activity activity = resumed.get().get();
                if (activity != null) activity.getWindow().getDecorView().post(() -> refresh.accept(activity));
            }
        }, new IntentFilter(Intent.ACTION_USER_UNLOCKED), Context.RECEIVER_NOT_EXPORTED);
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                if (!activity.getClass().getName().equals(LauncherTargets.PIXEL + ".NexusLauncherActivity")) return;
                resumed.set(new WeakReference<>(activity));
                activity.getWindow().getDecorView().post(() -> {
                    publishGrid(activity, loader, note);
                    refresh.accept(activity);
                });
            }

            @Override public void onActivityCreated(Activity a, Bundle state) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle state) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }

    private static void publishGrid(Activity activity, ClassLoader loader, Consumer<String> note) {
        try {
            var base = Class.forName("com.android.launcher3.BaseActivity", false, loader);
            var profile = method(base, "getDeviceProfile").invoke(activity);
            var inv = field(profile.getClass(), "inv").get(profile);
            int columns = field(inv.getClass(), "numColumns").getInt(inv);
            int rows = field(inv.getClass(), "numRows").getInt(inv);
            var workspace = method(profile.getClass(), "getWorkspaceProfile").invoke(profile);
            var type = workspace.getClass();
            var gap = (Point) field(type, "cellLayoutBorderSpacePx").get(workspace);
            var cell = (Point) field(type, "cellSize").get(workspace);
            var padding = (android.graphics.Rect) field(type, "widgetPadding").get(workspace);
            MinusOneSettings.publishWidgetGrid(activity,
                    new WidgetGrid(columns, rows, cell.x, cell.y, gap.x, gap.y,
                            padding.left, padding.top, padding.right, padding.bottom));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            note.accept("MINUS_ONE_GRID_UNAVAILABLE " + unavailable.getClass().getSimpleName());
        }
    }

    private static Method method(Class<?> type, String name) throws NoSuchMethodException {
        var member = type.getDeclaredMethod(name);
        member.setAccessible(true);
        return member;
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        var member = type.getDeclaredField(name);
        member.setAccessible(true);
        return member;
    }

    private static void installTransparentStartingView(XposedModule module, ClassLoader loader,
            Consumer<String> note) {
        XposedInterface.HookHandle drawHook = null;
        try {
            // Starting window renderer in Pixel Launcher 907.
            var type = Class.forName("f7.n", false, loader).asSubclass(View.class);
            var loadTheme = type.getDeclaredMethod("a", ActivityInfo.class);
            var draw = type.getDeclaredMethod("onDraw", Canvas.class);
            var transparent = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
            drawHook = module.hook(draw).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> transparent.contains(chain.getThisObject()) ? null : chain.proceed());
            module.hook(loadTheme).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                var view = (View) chain.getThisObject();
                if (isOurOverlay((ActivityInfo) chain.getArg(0))) {
                    if (transparent.add(view)) view.invalidate();
                    return null;
                }
                if (transparent.remove(view)) view.invalidate();
                return chain.proceed();
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            if (drawHook != null) drawHook.unhook();
            note.accept("MINUS_ONE_STARTING_VIEW_UNAVAILABLE " + unavailable.getClass().getSimpleName());
        }
    }
}
