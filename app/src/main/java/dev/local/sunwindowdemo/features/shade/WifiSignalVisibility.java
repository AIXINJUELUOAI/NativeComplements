package dev.local.sunwindowdemo.features.shade;

import android.os.Build;
import android.view.ViewGroup;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Lets the native status icon layout hide cellular entries when Wi-Fi is shown. */
public final class WifiSignalVisibility {
    private static final String WIFI = "wifi";
    private static final String MOBILE = "mobile";
    private static final String STACKED_MOBILE = "stacked_mobile";
    private static final Set<ViewGroup> CONTAINERS = Collections.newSetFromMap(new WeakHashMap<>());
    private static Class<?> displayable;
    private static Field ignoredSlots;
    private static Method getSlot, isIconVisible, isIconBlocked;
    private static boolean available, enabled;

    private WifiSignalVisibility() {}

    public static void install(XposedInterface module, ClassLoader loader, Consumer<String> note) {
        if (available || Build.VERSION.SDK_INT != 37) return;
        var hooks = new ArrayList<XposedInterface.HookHandle>();
        try {
            String statusbar = ShadeStore.SYSTEM_UI + ".statusbar.";
            var container = Class.forName(statusbar + "phone.StatusIconContainer", false, loader);
            if (!ViewGroup.class.isAssignableFrom(container)) throw new NoSuchMethodException("status icon container");
            displayable = Class.forName(statusbar + "StatusIconDisplayable", false, loader);
            getSlot = displayable.getMethod("getSlot");
            isIconVisible = displayable.getMethod("isIconVisible");
            isIconBlocked = displayable.getMethod("isIconBlocked");
            ignoredSlots = container.getDeclaredField("mIgnoredSlots");
            if (!Set.class.isAssignableFrom(ignoredSlots.getType())) throw new NoSuchFieldException("ignored slots");
            ignoredSlots.setAccessible(true);
            var measure = container.getDeclaredMethod("onMeasure", int.class, int.class);
            var layout = container.getDeclaredMethod("onLayout", boolean.class, int.class, int.class, int.class, int.class);
            hooks.add(module.hook(measure).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(WifiSignalVisibility::applyIconPolicy));
            hooks.add(module.hook(layout).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(WifiSignalVisibility::applyIconPolicy));
            available = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            for (var hook : hooks) {
                try { hook.unhook(); }
                catch (RuntimeException ignored) {}
            }
            note.accept("WIFI_SIGNAL_VISIBILITY_UNAVAILABLE " + error.getClass().getSimpleName());
        }
    }

    static void setEnabled(boolean value) {
        if (!available || enabled == value) return;
        enabled = value;
        for (var container : CONTAINERS) container.requestLayout();
    }

    @SuppressWarnings("unchecked")
    private static Object applyIconPolicy(XposedInterface.Chain chain) throws Throwable {
        var container = (ViewGroup) chain.getThisObject();
        CONTAINERS.add(container);
        if (!enabled) return chain.proceed();

        var ignored = (Set<String>) ignoredSlots.get(container);
        if (ignored.contains(WIFI) || !hasVisibleWifi(container)) return chain.proceed();
        boolean addedMobile = ignored.add(MOBILE);
        boolean addedStacked = false;
        try {
            addedStacked = ignored.add(STACKED_MOBILE);
            return chain.proceed();
        } finally {
            if (addedMobile) ignored.remove(MOBILE);
            if (addedStacked) ignored.remove(STACKED_MOBILE);
        }
    }

    private static boolean hasVisibleWifi(ViewGroup container) throws ReflectiveOperationException {
        for (int i = 0; i < container.getChildCount(); i++) {
            var child = container.getChildAt(i);
            if (displayable.isInstance(child) && WIFI.equals(getSlot.invoke(child))
                    && Boolean.TRUE.equals(isIconVisible.invoke(child))
                    && Boolean.FALSE.equals(isIconBlocked.invoke(child))) return true;
        }
        return false;
    }
}
