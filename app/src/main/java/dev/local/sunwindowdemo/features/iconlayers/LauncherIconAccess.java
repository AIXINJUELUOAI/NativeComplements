package dev.local.sunwindowdemo.features.iconlayers;

import android.os.UserHandle;
import dev.local.sunwindowdemo.core.ReflectiveAccess;
import java.lang.reflect.Method;

/** The iconloader ABI differences only; rendering and cache/refresh policy stay in their callers. */
final class LauncherIconAccess {
    private LauncherIconAccess() {}

    static Object themedBitmap(Object bitmap) throws Exception {
        return ReflectiveAccess.fieldOrGetter(bitmap, "themedBitmap", "getThemedBitmap");
    }

    static Object iconCache(Object state) throws Exception {
        return ReflectiveAccess.fieldOrGetter(state, "iconCache", "getIconCache");
    }

    static Object model(Object state) throws Exception {
        return ReflectiveAccess.fieldOrGetter(state, "model", "getModel");
    }

    static Object themeController(Object manager) throws Exception {
        Object state = ReflectiveAccess.fieldOrGetter(manager, "iconState", "getIconState");
        return state == null ? null : ReflectiveAccess.fieldOrGetter(
                state, "themeController", "getThemeController");
    }

    static Method removeIconsForPkg(Class<?> base) throws NoSuchMethodException {
        Method method;
        try { method = base.getDeclaredMethod("removeIconsForPkg", UserHandle.class, String.class); }
        catch (NoSuchMethodException packageFirst) {
            method = base.getDeclaredMethod("removeIconsForPkg", String.class, UserHandle.class);
        }
        if (method.getReturnType() != void.class) throw new NoSuchMethodException("removeIconsForPkg return");
        method.setAccessible(true);
        return method;
    }

    static void remove(Method method, Object cache, String pkg, UserHandle user) throws Exception {
        if (method.getParameterTypes()[0] == UserHandle.class) method.invoke(cache, user, pkg);
        else method.invoke(cache, pkg, user);
    }
}
