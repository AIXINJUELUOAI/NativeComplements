package dev.local.sunwindowdemo.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Field/getter probes for host classes whose Java/Kotlin property shape differs by product. */
public final class ReflectiveAccess {
    private ReflectiveAccess() {}

    public static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(owner.getName() + "#" + name);
    }

    public static Method method(Class<?> owner, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(owner.getName() + "#" + name);
    }

    /** Reads a Java field when present, otherwise invokes the Kotlin-style getter. */
    public static Object fieldOrGetter(Object owner, String field, String getter) throws Exception {
        return fieldOrGetter(owner.getClass(), owner, field, getter);
    }

    public static Object fieldOrGetter(Class<?> declared, Object owner, String field, String getter)
            throws Exception {
        try {
            return field(declared, field).get(owner);
        } catch (NoSuchFieldException absent) {
            return method(declared, getter).invoke(owner);
        }
    }
}
