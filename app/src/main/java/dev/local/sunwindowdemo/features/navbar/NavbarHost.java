package dev.local.sunwindowdemo.features.navbar;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Display;
import android.view.WindowInsets;
import android.view.WindowManager;
import io.github.libxposed.api.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/** Removes only the native navigation source's occupied space, not its window or gestures. */
final class NavbarHost {
    private static final String TAG = "NativeNavbar";
    private final Handler main = new Handler(Looper.getMainLooper());
    // All controller access, configuration publication and layout mutation stay on the main thread.
    private final Map<Object, Context> bars = new WeakHashMap<>();
    private final Map<Drawable, java.lang.ref.WeakReference<Object>> backgrounds = new WeakHashMap<>();
    private final Field context, navigationMode, transitions, background, providedInsets;
    private final Method reposition, providerType, setInsetsSize;
    private Handler reader;
    private Application application;
    private boolean enabled, observing;
    private final Runnable reload = this::readSettings;
    private final Runnable refresh = this::refreshBars;

    NavbarHost(Class<?> bar, Class<?> provider) throws ReflectiveOperationException {
        context = bar.getDeclaredField("mContext");
        navigationMode = bar.getDeclaredField("mNavBarMode");
        transitions = bar.getDeclaredField("mNavigationBarTransitions");
        background = transitions.getType().getSuperclass().getDeclaredField("mBarBackground");
        transitions.setAccessible(true);
        background.setAccessible(true);
        reposition = bar.getDeclaredMethod("repositionNavigationBar", int.class);
        context.setAccessible(true);
        navigationMode.setAccessible(true);
        reposition.setAccessible(true);
        providedInsets = WindowManager.LayoutParams.class.getField("providedInsets");
        providerType = provider.getMethod("getType");
        setInsetsSize = provider.getMethod("setInsetsSize", Insets.class);
    }

    static NavbarHost install(XposedModule module, ClassLoader loader) {
        var hooks = new ArrayList<XposedInterface.HookHandle>();
        try {
            Class<?> bar = Class.forName("com.android.systemui.navigationbar.views.NavigationBar", false, loader);
            NavbarHost host = new NavbarHost(bar,
                    Class.forName("android.view.InsetsFrameProvider", false, loader));
            Method layout = bar.getDeclaredMethod("getBarLayoutParamsForRotation", int.class);
            if (layout.getReturnType() != WindowManager.LayoutParams.class)
                throw new NoSuchMethodException("NavigationBar layout return type");
            Method init = bar.getDeclaredMethod("onInit");
            Method draw = host.background.getType().getDeclaredMethod("draw", Canvas.class);
            Method applicationCreate = Instrumentation.class.getDeclaredMethod("callApplicationOnCreate", Application.class);
            if (!module.deoptimize(host.reposition) || !module.deoptimize(init))
                throw new IllegalStateException("Navigation layout callers deoptimize failed");
            hooks.add(module.hook(layout).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                host.onLayout(chain.getThisObject(), (WindowManager.LayoutParams) result);
                return result;
            }));
            hooks.add(module.hook(draw).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Drawable drawable = (Drawable) chain.getThisObject();
                if (!host.hideBackground(drawable)) return chain.proceed();
                Canvas canvas = (Canvas) chain.getArg(0);
                int save = canvas.save();
                try {
                    canvas.clipRect(0, 0, 0, 0);
                    // Run native animation/time/invalidation logic without painting the backdrop.
                    return chain.proceed();
                } finally { canvas.restoreToCount(save); }
            }));
            hooks.add(module.hook(applicationCreate).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                host.connect((Application) chain.getArg(0));
                return result;
            }));
            android.util.Log.i(TAG, "IMMERSIVE_HOOK_READY");
            return host;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            for (var hook : hooks) try { hook.unhook(); } catch (RuntimeException ignored) {}
            android.util.Log.e(TAG, "Immersive SystemUI preflight failed", error);
            return null;
        }
    }

    static boolean eligible(boolean enabled, Display display, int navigationMode) {
        return enabled && display != null && display.getDisplayId() == Display.DEFAULT_DISPLAY
                && navigationMode == 2;
    }

    void onLayout(Object controller, WindowManager.LayoutParams params) throws ReflectiveOperationException {
        if (Looper.myLooper() != Looper.getMainLooper()) return;
        Context owner = (Context) context.get(controller);
        bars.put(controller, owner);
        Drawable drawable = (Drawable) background.get(transitions.get(controller));
        if (drawable != null) backgrounds.put(drawable, new java.lang.ref.WeakReference<>(controller));
        if (eligible(enabled, owner.getDisplay(), navigationMode.getInt(controller))) removeNavigationInset(params);
    }

    boolean hideBackground(Drawable drawable) throws ReflectiveOperationException {
        if (Looper.myLooper() != Looper.getMainLooper()) return false;
        var reference = backgrounds.get(drawable);
        Object controller = reference == null ? null : reference.get();
        return controller != null && eligible(enabled, ((Context) context.get(controller)).getDisplay(),
                navigationMode.getInt(controller));
    }

    // Each native call builds fresh params, including all rotations. Disabled calls return them untouched.
    void removeNavigationInset(WindowManager.LayoutParams params) throws ReflectiveOperationException {
        Object[] providers = (Object[]) providedInsets.get(params);
        if (providers == null) return;
        for (Object provider : providers) {
            if (provider != null && (Integer) providerType.invoke(provider) == WindowInsets.Type.navigationBars()) {
                // Keep IME InsetsSizeOverrides, minimal cutout policy, source flags and gesture providers intact.
                setInsetsSize.invoke(provider, Insets.NONE);
            }
        }
    }

    void connect(Application app) {
        if (!"com.android.systemui".equals(app.getPackageName())) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> connect(app));
            return;
        }
        if (application != null) return;
        application = app;
        HandlerThread thread = new HandlerThread("NativeNavbarConfig");
        thread.start();
        reader = new Handler(thread.getLooper());
        reader.post(reload);
    }

    private void readSettings() {
        reader.removeCallbacks(reload);
        boolean next = false;
        try {
            if (!observing) {
                application.getContentResolver().registerContentObserver(NavbarProvider.URI, false,
                        new ContentObserver(reader) {
                            @Override public void onChange(boolean selfChange) {
                                reader.removeCallbacks(reload);
                                reader.post(reload);
                            }
                        });
                observing = true;
            }
            Bundle settings = application.getContentResolver().call(NavbarProvider.URI, "system", null, null);
            if (settings == null) throw new IllegalStateException("No navbar system snapshot");
            next = settings.getBoolean("enabled", false);
        } catch (RuntimeException error) {
            // Provider restart/unlock failure must restore native layout rather than leave stale immersion on.
            android.util.Log.w(TAG, "IMMERSIVE_CONFIG_UNAVAILABLE", error);
            reader.postDelayed(reload, 5000);
        }
        boolean desired = next;
        main.post(() -> applyEnabled(desired));
    }

    void applyEnabled(boolean desired) {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("Navigation layout requires main thread");
        if (enabled == desired) return;
        enabled = desired;
        refreshBars();
    }

    private void refreshBars() {
        main.removeCallbacks(refresh);
        boolean retry = false;
        for (var entry : new ArrayList<>(bars.entrySet())) {
            Context owner = entry.getValue();
            Display display = owner.getDisplay();
            if (display == null || display.getDisplayId() != Display.DEFAULT_DISPLAY) continue;
            try {
                // Native reposition rebuilds the current params and paramsForRotation[0..3], on BOTH edges.
                reposition.invoke(entry.getKey(), display.getRotation());
            } catch (ReflectiveOperationException | RuntimeException error) {
                retry = true;
                android.util.Log.w(TAG, "IMMERSIVE_UPDATE_UNAVAILABLE", error);
            }
        }
        for (Drawable drawable : new ArrayList<>(backgrounds.keySet())) drawable.invalidateSelf();
        if (retry) main.postDelayed(refresh, 5000);
    }
}
