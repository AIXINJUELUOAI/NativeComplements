package dev.local.sunwindowdemo.features.navbar;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.drawable.Drawable;
import android.os.*;
import android.view.Display;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import io.github.libxposed.api.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/** The verified Pixel launcher, not SystemUI, owns the phone navigation source on this target. */
final class NavbarTaskbarHost {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Object> controllers = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<Object, WeakReference<Object>> backgrounds = new WeakHashMap<>();
    private final Field context, destroyed, dragLayer, renderer, ownerControllers, navButtons, transitions, background, windowManager, windowLayout;
    private final Method refreshInsets, gestureNav, providerType, setInsetsSize;
    private XposedModule module;
    private Application application;
    private Handler reader;
    private boolean enabled, observing;
    private final Runnable reload = this::readSettings;
    private final Runnable refresh = this::refreshControllers;

    NavbarTaskbarHost(Class<?> controller, Class<?> provider) throws ReflectiveOperationException {
        context = field(controller, "context");
        Class<?> owner = context.getType();
        destroyed = field(owner, "mIsDestroyed");
        dragLayer = field(owner, "mDragLayer");
        windowManager = field(owner, "mWindowManager");
        windowLayout = field(owner, "mWindowLayoutParams");
        renderer = field(dragLayer.getType(), "mBackgroundRenderer");
        ownerControllers = field(owner, "mControllers");
        navButtons = field(ownerControllers.getType(), "navbarButtonsViewController");
        transitions = field(navButtons.getType(), "mTaskbarTransitions");
        background = field(transitions.getType().getSuperclass(), "mBarBackground");
        gestureNav = owner.getDeclaredMethod("isGestureNav");
        gestureNav.setAccessible(true);
        refreshInsets = controller.getDeclaredMethod("onTaskbarOrBubblebarWindowHeightOrInsetsChanged");
        refreshInsets.setAccessible(true);
        providerType = provider.getMethod("getType");
        setInsetsSize = provider.getMethod("setInsetsSize", Insets.class);
    }

    static NavbarTaskbarHost install(XposedModule module, ClassLoader loader) {
        var hooks = new ArrayList<XposedInterface.HookHandle>();
        try {
            Class<?> controller = Class.forName("com.android.launcher3.taskbar.TaskbarInsetsController", false, loader);
            Class<?> provider = Class.forName("android.view.InsetsFrameProvider", false, loader);
            NavbarTaskbarHost host = new NavbarTaskbarHost(controller, provider);
            host.module = module;
            // This exact three-argument ABI is verified against the live launcher's SHA-256.
            Method setter = controller.getDeclaredMethod("setProviderInsets", provider, int.class, int.class);
            setter.setAccessible(true);
            Method rendererDraw = host.renderer.getType().getDeclaredMethod("draw", Canvas.class);
            Method backgroundDraw = host.background.getType().getDeclaredMethod("draw", Canvas.class);
            Method dispatchDraw = host.dragLayer.getType().getDeclaredMethod("dispatchDraw", Canvas.class);
            dispatchDraw.setAccessible(true);
            if (!module.deoptimize(host.refreshInsets) || !module.deoptimize(dispatchDraw))
                throw new IllegalStateException("Taskbar native caller deoptimize failed");
            hooks.add(module.hook(setter).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                host.onProvider(chain.getThisObject(), chain.getArg(0));
                return result;
            }));
            for (Method draw : new Method[]{rendererDraw, backgroundDraw}) {
                hooks.add(module.hook(draw).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    if (!host.hideBackground(chain.getThisObject())) return chain.proceed();
                    Canvas canvas = (Canvas) chain.getArg(0);
                    int save = canvas.save();
                    try {
                        canvas.clipRect(0, 0, 0, 0);
                        return chain.proceed();
                    } finally { canvas.restoreToCount(save); }
                }));
            }
            hooks.add(module.hook(Instrumentation.class.getDeclaredMethod("callApplicationOnCreate", Application.class))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        Object result = chain.proceed();
                        host.connect((Application) chain.getArg(0));
                        return result;
                    }));
            NavbarHost.log(module, "IMMERSIVE_TASKBAR_READY hooks=" + hooks.size(), null);
            return host;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            for (var hook : hooks) try { hook.unhook(); } catch (RuntimeException ignored) {}
            NavbarHost.log(module, "IMMERSIVE_TASKBAR_INSTALL_FAILED", error);
            return null;
        }
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    boolean eligible(Object owner) throws ReflectiveOperationException {
        if (!enabled || destroyed.getBoolean(owner) || !(Boolean) gestureNav.invoke(owner)) return false;
        Display display = ((Context) owner).getDisplay();
        return display != null && display.getDisplayId() == Display.DEFAULT_DISPLAY;
    }

    void onProvider(Object controller, Object provider) throws ReflectiveOperationException {
        if (Looper.myLooper() != Looper.getMainLooper()) return;
        Object owner = context.get(controller);
        if (destroyed.getBoolean(owner)) { controllers.remove(controller); return; }
        if (controllers.add(controller)) NavbarHost.log(module, "IMMERSIVE_TASKBAR_SEEN", null);
        View layer = (View) dragLayer.get(owner);
        if (layer != null) backgrounds.put(renderer.get(layer), new WeakReference<>(owner));
        Object buttons = navButtons.get(ownerControllers.get(owner));
        Object barTransitions = buttons == null ? null : transitions.get(buttons);
        if (barTransitions != null) backgrounds.put(background.get(barTransitions), new WeakReference<>(owner));
        if (eligible(owner) && (Integer) providerType.invoke(provider) == WindowInsets.Type.navigationBars())
            setInsetsSize.invoke(provider, Insets.NONE);
    }

    boolean hideBackground(Object drawable) throws ReflectiveOperationException {
        if (Looper.myLooper() != Looper.getMainLooper()) return false;
        WeakReference<Object> reference = backgrounds.get(drawable);
        Object owner = reference == null ? null : reference.get();
        return owner != null && eligible(owner);
    }

    void connect(Application app) {
        if (!dev.local.sunwindowdemo.core.LauncherTargets.isLauncher(app.getPackageName())) return;
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> connect(app)); return; }
        if (application != null) return;
        application = app;
        HandlerThread thread = new HandlerThread("NativeTaskbarConfig");
        thread.start();
        reader = new Handler(thread.getLooper());
        NavbarHost.log(module, "IMMERSIVE_TASKBAR_CONNECTED", null);
        reader.post(reload);
    }

    private void readSettings() {
        reader.removeCallbacks(reload);
        boolean next = false;
        try {
            if (!observing) {
                application.getContentResolver().registerContentObserver(NavbarProvider.URI, false, new ContentObserver(reader) {
                    @Override public void onChange(boolean selfChange) {
                        reader.removeCallbacks(reload);
                        reader.post(reload);
                    }
                });
                observing = true;
            }
            Bundle settings = application.getContentResolver().call(NavbarProvider.URI, "system", null, null);
            if (settings == null) throw new IllegalStateException("No taskbar settings");
            next = settings.getBoolean("enabled", false);
            NavbarHost.log(module, "IMMERSIVE_TASKBAR_CONFIG enabled=" + next, null);
        } catch (RuntimeException error) {
            NavbarHost.log(module, "IMMERSIVE_TASKBAR_CONFIG_UNAVAILABLE", error);
            reader.postDelayed(reload, 5000);
        }
        boolean desired = next;
        main.post(() -> applyEnabled(desired));
    }

    void applyEnabled(boolean desired) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Taskbar requires main thread");
        if (enabled == desired) return;
        enabled = desired;
        refreshControllers();
    }

    private void refreshControllers() {
        main.removeCallbacks(refresh);
        int updated = 0;
        boolean retry = false;
        for (Object controller : new ArrayList<>(controllers)) {
            try {
                Object owner = context.get(controller);
                if (destroyed.getBoolean(owner)) { controllers.remove(controller); continue; }
                Display display = ((Context) owner).getDisplay();
                if (display == null || display.getDisplayId() != Display.DEFAULT_DISPLAY) continue;
                // Native method resets EVERY provider for current params and all four rotations before notifying WM.
                // On disable it recomputes original sizes; no restoration from already-mutated params.
                refreshInsets.invoke(controller);
                View layer = (View) dragLayer.get(owner);
                // Native notify compares shallow provider references and can skip a changed source size.
                // Submit its freshly recomputed params on this config edge, never mutate the native cache.
                if (layer.isAttachedToWindow()) ((WindowManager) windowManager.get(owner))
                        .updateViewLayout(layer.getRootView(), (WindowManager.LayoutParams) windowLayout.get(owner));
                layer.invalidate();
                updated++;
            } catch (ReflectiveOperationException | RuntimeException error) {
                retry = true;
                NavbarHost.log(module, "IMMERSIVE_TASKBAR_UPDATE_UNAVAILABLE", error);
            }
        }
        for (Object drawable : new ArrayList<>(backgrounds.keySet())) {
            if (drawable instanceof Drawable) ((Drawable) drawable).invalidateSelf();
        }
        NavbarHost.log(module, "IMMERSIVE_TASKBAR_REFRESH enabled=" + enabled + " updated=" + updated + " retry=" + retry, null);
        if (retry) main.postDelayed(refresh, 5000);
    }
}
