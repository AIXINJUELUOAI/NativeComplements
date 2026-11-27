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
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import dev.local.sunwindowdemo.core.LauncherTargets;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.WeakHashMap;
import java.util.function.Consumer;

public final class LauncherMinusOne {
    private static final String ACTION = "com.android.launcher3.WINDOW_OVERLAY";
    private static final String PACKAGE = "dev.nativecomplements.module";
    private static final String ACTIVITY = PACKAGE + ".MinusOneOverlay";
    private static final String DISCOVER = "com.google.android.googlequicksearchbox";
    private static final long LAUNCHER_VERSION = 907;

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
                                && supported(app) && MinusOneSettings.enabledForLauncher(app)) {
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
            module.hook(resolve).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                var intent = (Intent) chain.getArg(0);
                if (intent == null || !ACTION.equals(intent.getAction()) || intent.getComponent() != null
                        || (intent.getPackage() != null && !DISCOVER.equals(intent.getPackage()))) {
                    return chain.proceed();
                }
                var stock = (ResolveInfo) chain.proceed();
                if (stock != null && stock.activityInfo != null
                        && !DISCOVER.equals(stock.activityInfo.packageName)) return stock;
                try {
                    var redirect = new Intent(intent).setPackage(PACKAGE);
                    var target = app.getPackageManager().resolveActivity(redirect,
                            PackageManager.ResolveInfoFlags.of(Integer.toUnsignedLong((Integer) chain.getArg(1))));
                    if (target != null && isOurOverlay(target.activityInfo)) return target;
                } catch (RuntimeException unavailable) {
                    note.accept("MINUS_ONE_RESOLVE_FAILED");
                }
                return stock;
            });
            watchGrid(app, loader, note);
            LauncherWidgetPicker.install(module, loader, app, note);
            installTransparentStartingView(module, loader, note);
            note.accept("MINUS_ONE_READY");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            note.accept("MINUS_ONE_CONNECT_FAILED " + unavailable.getClass().getSimpleName());
        }
    }

    private static boolean isOurOverlay(ActivityInfo target) {
        return target != null && PACKAGE.equals(target.packageName) && ACTIVITY.equals(target.name);
    }

    private static void watchGrid(Application app, ClassLoader loader, Consumer<String> note) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                if (!activity.getClass().getName().equals(LauncherTargets.PIXEL + ".NexusLauncherActivity")) return;
                activity.getWindow().getDecorView().post(() -> publishGrid(activity, loader, note));
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
            int width = field(type, "cellWidthPx").getInt(workspace);
            int height = field(type, "cellHeightPx").getInt(workspace);
            MinusOneSettings.publishWidgetGrid(activity,
                    new WidgetGrid(columns, rows, width, height, gap.x, gap.y));
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
