package dev.local.sunwindowdemo.features.navbar;

import android.app.Application;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.os.*;
import android.view.View;
import io.github.libxposed.api.*;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/** Colors the native navigation window, preserving its original touch and gesture handling. */
final class NavbarHost {
    private static final Handler main = new Handler(Looper.getMainLooper());
    private record Bar(Drawable original, ColorDrawable tint) {}
    private static final Map<View, Bar> bars = new WeakHashMap<>();
    private static final Map<Object, Float> handles = new WeakHashMap<>();
    private static Method setIntensity;
    private static boolean active, connected, settingIntensity;
    private static int mode, color;
    private static final Runnable expired = () -> apply(false, 0, 0);

    static void install(XposedModule module, ClassLoader loader) {
        try {
            Class<?> bar = Class.forName("com.android.systemui.navigationbar.views.NavigationBarView", false, loader);
            for (var constructor : bar.getDeclaredConstructors()) {
                module.hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object result = chain.proceed();
                    View view = (View) chain.getThisObject();
                    main.post(() -> bars.put(view, new Bar(view.getBackground(), new ColorDrawable())));
                    return result;
                });
            }
            Class<?> handle = Class.forName("com.android.systemui.navigationbar.gestural.NavigationHandle", false, loader);
            setIntensity = handle.getDeclaredMethod("setDarkIntensity", float.class);
            module.hook(setIntensity).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (!settingIntensity) handles.put(chain.getThisObject(), (Float) chain.getArg(0));
                View view = (View) chain.getThisObject();
                if (active && mode == NavbarStore.COLOR && view.getDisplay() != null && view.getDisplay().getDisplayId() == 0)
                    return chain.proceed(new Object[]{Color.luminance(color) > 0.5f ? 1f : 0f});
                return chain.proceed();
            });
            android.util.Log.i("NativeNavbar", "SYSTEM_UI_HOOK_READY");
        } catch (ReflectiveOperationException error) { android.util.Log.e("NativeNavbar", "SystemUI preflight failed", error); }
    }

    static void connect(Application app) {
        if (connected || !app.getPackageName().equals("com.android.systemui")) return;
        connected = true;
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (getSentFromUid() != 1000) return;
                main.removeCallbacks(expired);
                apply(intent.getBooleanExtra("active", false), intent.getIntExtra("color", 0), intent.getIntExtra("mode", 0));
                main.postDelayed(expired, NavbarSystem.FRAME_TIMEOUT_MS);
            }
        }, new IntentFilter(NavbarSystem.FRAME), "android.permission.STATUS_BAR_SERVICE", main, Context.RECEIVER_EXPORTED);
    }

    private static void apply(boolean enabled, int nextColor, int nextMode) {
        boolean wasActive = active;
        active = enabled;
        color = nextColor;
        mode = nextMode;
        if (!active && !wasActive) return;
        bars.forEach((view, bar) -> {
            if (view.getDisplay() == null || view.getDisplay().getDisplayId() != 0) return;
            if (active) {
                bar.tint().setColor(mode == NavbarStore.FLOATING ? Color.TRANSPARENT : color);
                if (view.getBackground() != bar.tint()) view.setBackground(bar.tint());
            } else view.setBackground(bar.original());
        });
        settingIntensity = true;
        try {
            for (var entry : handles.entrySet()) {
                View view = (View) entry.getKey();
                if (view.getDisplay() == null || view.getDisplay().getDisplayId() != 0) continue;
                float intensity = active && mode == NavbarStore.COLOR
                        ? (Color.luminance(color) > 0.5f ? 1f : 0f) : entry.getValue();
                try { setIntensity.invoke(entry.getKey(), intensity); }
                catch (ReflectiveOperationException ignored) {}
            }
        } finally { settingIntensity = false; }
    }
}
