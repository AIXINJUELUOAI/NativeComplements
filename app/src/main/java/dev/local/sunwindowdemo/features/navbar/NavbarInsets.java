package dev.local.sunwindowdemo.features.navbar;

import android.view.WindowInsets;
import java.lang.reflect.*;

/** Copies the per-window state before removing only navigation sources. */
final class NavbarInsets {
    private final Constructor<?> copy;
    private final Method size, at, type, remove;
    NavbarInsets(Class<?> state) throws ReflectiveOperationException {
        copy = state.getConstructor(state, boolean.class);
        size = state.getMethod("sourceSize");
        at = state.getMethod("sourceAt", int.class);
        type = at.getReturnType().getMethod("getType");
        remove = state.getMethod("removeSourceAt", int.class);
    }
    Object copy(Object original) throws ReflectiveOperationException { return copy.newInstance(original, true); }
    Object strip(Object original) throws ReflectiveOperationException {
        Object result = copy(original);
        for (int index = (Integer) size.invoke(result) - 1; index >= 0; index--) {
            if (((Integer) type.invoke(at.invoke(result, index)) & WindowInsets.Type.navigationBars()) != 0)
                remove.invoke(result, index);
        }
        return result;
    }
}
