package dev.local.sunwindowdemo.features.iconlayers;

import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Launcher-side access to the AOSP/LineageOS "Minimal" icon style. The module only uses this
 * while its own drawer-Monet switch is enabled; it never changes the launcher on its own.
 */
final class LauncherMonoTheme {
    private final Object preference;
    private final Method getValue;
    private final Method setValue;
    private final Object monoValue;
    private final Class<?> valueType;

    private LauncherMonoTheme(Object preference, Method getValue, Method setValue,
                              Object monoValue, Class<?> valueType) {
        this.preference = preference;
        this.getValue = getValue;
        this.setValue = setValue;
        this.monoValue = monoValue;
        this.valueType = valueType;
    }

    static LauncherMonoTheme resolve(Context context) throws Exception {
        ClassLoader loader = context.getClassLoader();
        Class<?> managerClass = Class.forName("com.android.launcher3.graphics.ThemeManager", false, loader);
        Object singleton = managerClass.getField("INSTANCE").get(null);
        Object manager = singleton.getClass().getMethod("get", Context.class).invoke(singleton, context);
        Field preferenceField = managerClass.getDeclaredField("themePreference");
        preferenceField.setAccessible(true);
        Object preference = preferenceField.get(manager);

        Class<?> preferenceClass = Class.forName(
                "com.android.launcher3.graphics.theme.ThemePreference", false, loader);
        Class<?> valueClass = Class.forName(
                "com.android.launcher3.graphics.theme.ThemePreference$ThemeValue", false, loader);
        Object monoValue = preferenceClass.getField("MONO_THEME_VALUE").get(null);
        Method getValue = null;
        for (Method candidate : preference.getClass().getMethods()) {
            if (candidate.getName().equals("getValue") && candidate.getParameterCount() == 0
                    && candidate.getReturnType() == valueClass) {
                getValue = candidate;
                break;
            }
        }
        Method setValue = preference.getClass().getMethod("setValue", valueClass);
        if (!valueClass.isInstance(monoValue) || getValue == null
                || setValue.getReturnType() != void.class) {
            throw new NoSuchMethodException("Launcher Minimal icon style ABI");
        }
        getValue.setAccessible(true);
        setValue.setAccessible(true);
        return new LauncherMonoTheme(preference, getValue, setValue, monoValue, valueClass);
    }

    boolean isMono() throws Exception {
        return monoValue.equals(getValue.invoke(preference));
    }

    Object current() throws Exception {
        return getValue.invoke(preference);
    }

    void setMono() throws Exception {
        setValue.invoke(preference, monoValue);
    }

    void set(Object value) throws Exception {
        if (value != null && !valueType.isInstance(value)) throw new IllegalArgumentException("theme value");
        setValue.invoke(preference, value);
    }

    /** Restores a persisted value encoded as factoryId:themeId, or null for the default style. */
    Object parse(String encoded) throws Exception {
        if (encoded == null || encoded.isEmpty()) return null;
        int split = encoded.indexOf(':');
        if (split <= 0 || split == encoded.length() - 1) return null;
        Constructor<?> constructor = valueType.getDeclaredConstructor(String.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(encoded.substring(0, split), encoded.substring(split + 1));
    }

    static String encode(Object value) {
        return value == null ? "" : value.toString();
    }
}
