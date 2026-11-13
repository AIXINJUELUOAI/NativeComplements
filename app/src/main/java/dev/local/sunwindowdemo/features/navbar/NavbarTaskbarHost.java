package dev.local.sunwindowdemo.features.navbar;

import android.app.Application;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.View;
import android.graphics.drawable.Drawable;
import io.github.libxposed.api.*;
import java.lang.reflect.Method;
import java.util.*;

/** The phone taskbar owns the navigation background and stashed handle on this launcher. */
final class NavbarTaskbarHost {
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Set<View> layers = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<Object, java.lang.ref.WeakReference<View>> backgrounds = new WeakHashMap<>();
    private static final Map<Object, Boolean> handles = new WeakHashMap<>();
    private static final Map<Object, java.lang.ref.WeakReference<View>> transitionBackgrounds = new WeakHashMap<>();
    private static boolean active, connected, settingHandle;
    private static int color, mode, navHeight;
    private static final Paint band = new Paint();
    private static Method updateHandle;
    private static final Runnable expired = () -> apply(false, 0, 0, 0);

    static void install(XposedModule module, ClassLoader loader) {
        try {
            Class<?> renderer = Class.forName("com.android.launcher3.taskbar.TaskbarBackgroundRenderer", false, loader);
            Method draw = renderer.getDeclaredMethod("draw", Canvas.class);
            Class<?> handle = Class.forName("com.android.launcher3.taskbar.StashedHandleView", false, loader);
            updateHandle = handle.getDeclaredMethod("updateHandleColor", boolean.class, boolean.class);
            Class<?> layer = Class.forName("com.android.launcher3.taskbar.TaskbarDragLayer", false, loader);
            for (var constructor : layer.getDeclaredConstructors()) {
                module.hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object result = chain.proceed();
                    View view = (View) chain.getThisObject();
                    main.post(() -> {
                        layers.add(view);
                        try {
                            backgrounds.put(NavbarReflect.field(view, "mBackgroundRenderer"), new java.lang.ref.WeakReference<>(view));
                        } catch (ReflectiveOperationException error) {
                            android.util.Log.w("NativeNavbar", "Taskbar background unavailable", error);
                        }
                    });
                    return result;
                });
            }
            module.hook(draw).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                var reference = backgrounds.get(chain.getThisObject());
                View owner = reference == null ? null : reference.get();
                if (!active || owner == null || owner.getDisplay() == null || owner.getDisplay().getDisplayId() != 0) return chain.proceed();
                Paint paint = (Paint) NavbarReflect.field(chain.getThisObject(), "paint");
                Paint stroke = (Paint) NavbarReflect.field(chain.getThisObject(), "strokePaint");
                int previousAlpha = paint.getAlpha(), previousStrokeAlpha = stroke.getAlpha();
                int previousColor = paint.getColor(), previousStrokeColor = stroke.getColor();
                Paint previous = new Paint(paint), previousStroke = new Paint(stroke);
                try {
                    tint(paint, mode, color);
                    if (mode == NavbarStore.FLOATING) stroke.setAlpha(0);
                    Object result = chain.proceed();
                    if (mode == NavbarStore.COLOR) {
                        band.setColor(color);
                        drawBand((Canvas) chain.getArg(0), band, navHeight);
                    }
                    return result;
                } finally {
                    restore(paint, previous, previousColor, previousAlpha);
                    restore(stroke, previousStroke, previousStrokeColor, previousStrokeAlpha);
                }
            });
            module.hook(updateHandle).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (!settingHandle) handles.put(chain.getThisObject(), (Boolean) chain.getArg(0));
                View view = (View) chain.getThisObject();
                if (active && mode == NavbarStore.COLOR && view.getDisplay() != null && view.getDisplay().getDisplayId() == 0)
                    return chain.proceed(new Object[]{Color.luminance(color) < 0.5f, false});
                return chain.proceed();
            });
            android.util.Log.i("NativeNavbar", "TASKBAR_HOOK_READY");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            android.util.Log.e("NativeNavbar", "Taskbar preflight failed", error);
        }
        installTransitions(module, loader);
    }

    private static void installTransitions(XposedModule module, ClassLoader loader) {
        try {
            Class<?> taskbar = Class.forName("com.android.launcher3.taskbar.TaskbarTransitions", false, loader);
            Class<?> transitions = Class.forName("com.android.systemui.shared.statusbar.phone.BarTransitions", false, loader);
            Class<?> background = Class.forName(transitions.getName() + "$BarBackgroundDrawable", false, loader);
            module.hook(transitions.getDeclaredConstructor(View.class, int.class))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                if (taskbar.isInstance(chain.getThisObject())) {
                    View view = (View) chain.getArg(0);
                    transitionBackgrounds.put(NavbarReflect.field(chain.getThisObject(), "mBarBackground"),
                            new java.lang.ref.WeakReference<>(view));
                }
                return result;
            });
            module.hook(background.getDeclaredMethod("draw", Canvas.class))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                var reference = transitionBackgrounds.get(chain.getThisObject());
                View owner = reference == null ? null : reference.get();
                if (!active || mode != NavbarStore.FLOATING || owner == null || owner.getDisplay() == null
                        || owner.getDisplay().getDisplayId() != 0) return chain.proceed();
                Canvas canvas = (Canvas) chain.getArg(0);
                int save = canvas.save();
                try {
                    // Keep native mode/animation updates running while suppressing only this background.
                    canvas.clipRect(0, 0, 0, 0);
                    return chain.proceed();
                } finally { canvas.restoreToCount(save); }
            });
            android.util.Log.i("NativeNavbar", "TASKBAR_TRANSITIONS_READY");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            android.util.Log.e("NativeNavbar", "Taskbar transitions preflight failed", error);
        }
    }

    static void tint(Paint paint, int mode, int color) {
        if (mode == NavbarStore.FLOATING) paint.setAlpha(0);
        else { paint.setColor(color); paint.setAlpha(255); }
    }

    static void restore(Paint target, Paint snapshot, int color, int alpha) {
        target.set(snapshot);
        target.setColor(color);
        target.setAlpha(alpha);
    }

    static void drawBand(Canvas canvas, Paint paint, int height) {
        if (height <= 0) return;
        canvas.drawRect(0, Math.max(0, canvas.getHeight() - height), canvas.getWidth(), canvas.getHeight(), paint);
    }

    static void connect(Application app) {
        if (connected || !"com.google.android.apps.nexuslauncher".equals(app.getPackageName())) return;
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (getSentFromUid() != 1000) return;
                main.removeCallbacks(expired);
                apply(intent.getBooleanExtra("active", false), intent.getIntExtra("color", 0), intent.getIntExtra("mode", 0), intent.getIntExtra("navHeight", 0));
                main.postDelayed(expired, NavbarSystem.FRAME_TIMEOUT_MS);
            }
        }, new IntentFilter(NavbarSystem.FRAME), "android.permission.STATUS_BAR_SERVICE", main, Context.RECEIVER_EXPORTED);
        connected = true;
    }

    private static void apply(boolean enabled, int nextColor, int nextMode, int nextHeight) {
        boolean wasActive = active;
        active = enabled;
        color = nextColor;
        mode = nextMode;
        navHeight = nextHeight;
        if (!active && !wasActive) return;
        for (View layer : layers) if (layer.getDisplay() != null && layer.getDisplay().getDisplayId() == 0) layer.invalidate();
        for (var entry : transitionBackgrounds.entrySet()) {
            View owner = entry.getValue().get();
            if (owner != null && owner.getDisplay() != null && owner.getDisplay().getDisplayId() == 0)
                ((Drawable) entry.getKey()).invalidateSelf();
        }
        settingHandle = true;
        try {
            for (var entry : handles.entrySet()) {
                View view = (View) entry.getKey();
                if (view.getDisplay() == null || view.getDisplay().getDisplayId() != 0) continue;
                boolean dark = active && mode == NavbarStore.COLOR ? Color.luminance(color) < 0.5f : entry.getValue();
                try { updateHandle.invoke(entry.getKey(), dark, false); }
                catch (ReflectiveOperationException ignored) {}
            }
        } finally { settingHandle = false; }
    }
}
