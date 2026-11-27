package dev.local.sunwindowdemo.features.callrefresh;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.view.Display;
import android.view.WindowManager;
import dev.local.sunwindowdemo.features.shade.ShadeStore;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Leaves the display refresh vote to the foreground app while a call is floating. */
public final class FloatingCallRefresh {
    private static final UserHandle SYSTEM_USER = UserHandle.getUserHandleForUid(android.os.Process.SYSTEM_UID);
    private volatile boolean enabled;
    private boolean observing;

    public static void install(XposedModule module, ClassLoader loader, Consumer<String> note) {
        if (Build.VERSION.SDK_INT != 37) return;
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        try {
            var policy = Class.forName("com.android.server.wm.RefreshRatePolicy", false, loader);
            var window = Class.forName("com.android.server.wm.WindowState", false, loader);
            var wms = Class.forName("com.android.server.wm.WindowManagerService", false, loader);
            var display = Class.forName("android.view.DisplayInfo", false, loader);
            var range = Class.forName("android.view.SurfaceControl$RefreshRateRange", false, loader);
            var min = method(policy, "getPreferredMinRefreshRate", float.class, window);
            var max = method(policy, "getPreferredMaxRefreshRate", float.class, window);
            var ranges = accessibleField(policy, "mNonHighRefreshRatePackages");
            var access = new Access(
                    field(policy, "mDisplayInfo", display), field(display, "displayId", int.class),
                    field(window, "mAttrs", WindowManager.LayoutParams.class),
                    method(window, "getOwningPackage", String.class),
                    method(window, "getOwningUid", int.class),
                    method(window, "isFocused", boolean.class),
                    method(window, "inPinnedWindowingMode", boolean.class),
                    field(WindowManager.LayoutParams.class, "preferredMinDisplayRefreshRate", float.class),
                    field(WindowManager.LayoutParams.class, "preferredMaxDisplayRefreshRate", float.class),
                    ranges, method(ranges.getType(), "get", range, String.class),
                    field(range, "min", float.class), field(range, "max", float.class));
            var ready = method(wms, "systemReady", void.class);
            var context = field(wms, "mContext", Context.class);
            var requestTraversal = method(wms, "requestTraversal", void.class);
            var feature = new FloatingCallRefresh();
            var main = new Handler(Looper.getMainLooper());
            handles.add(hookVote(module, min, access, feature, true));
            handles.add(hookVote(module, max, access, feature, false));
            handles.add(module.hook(ready).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object service = chain.getThisObject();
                        try {
                            var host = (Context) context.get(service);
                            if (host != null) main.post(() -> feature.observe(host, main, service, requestTraversal, note));
                        } catch (ReflectiveOperationException | RuntimeException failure) {
                            note.accept("FLOATING_CALL_REFRESH_CONFIG_UNAVAILABLE");
                        }
                        return result;
                    }));
            note.accept("FLOATING_CALL_REFRESH_HOOK_READY");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            for (var handle : handles) {
                try { handle.unhook(); } catch (RuntimeException | LinkageError ignored) {}
            }
            note.accept("FLOATING_CALL_REFRESH_UNAVAILABLE " + unavailable.getClass().getSimpleName());
        }
    }

    private static XposedInterface.HookHandle hookVote(XposedModule module, Method target, Access access,
                                                       FloatingCallRefresh feature, boolean minimum) {
        return module.hook(target).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            float original = (Float) chain.proceed();
            if (!feature.enabled || !FloatingCallRefreshPolicy.possibleVote(original)) return original;
            try {
                return access.adjust(original, minimum, chain.getThisObject(), chain.getArg(0));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                return original;
            }
        });
    }

    private void observe(Context context, Handler main, Object service, Method requestTraversal, Consumer<String> note) {
        if (observing) return;
        Runnable reload = () -> {
            boolean next = false;
            try {
                var snapshot = context.getContentResolver().call(
                        ShadeStore.CALL_REFRESH_URI, ShadeStore.CALL_REFRESH_READ, null, null);
                next = snapshot != null && snapshot.getBoolean("enabled");
            } catch (RuntimeException ignored) {
                // Settings may be temporarily unavailable. Keep native display policy.
            }
            if (enabled == next) return;
            enabled = next;
            try { requestTraversal.invoke(service); }
            catch (ReflectiveOperationException | RuntimeException ignored) {}
        };
        try {
            context.getContentResolver().registerContentObserver(ShadeStore.CALL_REFRESH_URI, false,
                    new ContentObserver(main) {
                        @Override public void onChange(boolean selfChange) { reload.run(); }
                    });
            observing = true;
            reload.run();
        } catch (RuntimeException unavailable) {
            note.accept("FLOATING_CALL_REFRESH_CONFIG_UNAVAILABLE");
        }
    }

    private record Access(Field displayInfo, Field displayId, Field attrs, Method owner, Method ownerUid, Method focused,
                          Method pinned, Field explicitMin, Field explicitMax, Field ranges, Method getRange,
                          Field rangeMin, Field rangeMax) {
        float adjust(float original, boolean minimum, Object policy, Object window)
                throws ReflectiveOperationException {
            var packageName = (String) owner.invoke(window);
            if (!FloatingCallRefreshPolicy.isCallApp(packageName)) return original;
            int uid = (Integer) ownerUid.invoke(window);
            if (uid < 0 || !UserHandle.getUserHandleForUid(uid).equals(SYSTEM_USER)) return original;
            var info = displayInfo.get(policy);
            if (info == null || displayId.getInt(info) != Display.DEFAULT_DISPLAY) return original;
            var layout = (WindowManager.LayoutParams) attrs.get(window);
            if (layout == null || layout.preferredDisplayModeId > 0 || layout.preferredRefreshRate > 0f
                    || explicitMin.getFloat(layout) > 0f || explicitMax.getFloat(layout) > 0f) return original;
            boolean floating = layout.type < WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW
                    || layout.type > WindowManager.LayoutParams.LAST_APPLICATION_WINDOW
                    || Boolean.TRUE.equals(pinned.invoke(window)) || !Boolean.TRUE.equals(focused.invoke(window));
            if (!floating) return original;
            // Only strip a vote that matches the actual package range from RefreshRatePolicy.
            var range = getRange.invoke(ranges.get(policy), packageName);
            if (range == null) return original;
            return FloatingCallRefreshPolicy.adjust(original, minimum, rangeMin.getFloat(range),
                    rangeMax.getFloat(range));
        }
    }

    private static Field accessibleField(Class<?> type, String name) throws NoSuchFieldException {
        var result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static Field field(Class<?> type, String name, Class<?> expected) throws NoSuchFieldException {
        var result = accessibleField(type, name);
        if (result.getType() != expected) throw new NoSuchFieldException(type.getName() + "." + name);
        return result;
    }

    private static Method method(Class<?> type, String name, Class<?> result, Class<?>... parameters)
            throws NoSuchMethodException {
        for (Class<?> host = type; host != null; host = host.getSuperclass()) {
            Method match;
            try { match = host.getDeclaredMethod(name, parameters); }
            catch (NoSuchMethodException missing) { continue; }
            if (match.getReturnType() != result) break;
            match.setAccessible(true);
            return match;
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
}
