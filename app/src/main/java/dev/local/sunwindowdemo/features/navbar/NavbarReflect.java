package dev.local.sunwindowdemo.features.navbar;

import java.lang.reflect.*;
import java.util.concurrent.ConcurrentHashMap;

/** Cached, exact signatures for the archived API37 framework. */
final class NavbarReflect {
    private static final ConcurrentHashMap<String, Field> fields = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Method> methods = new ConcurrentHashMap<>();
    static Object field(Object target, String name) throws ReflectiveOperationException {
        String key = target.getClass().getName() + "/" + name;
        Field field = fields.get(key);
        if (field == null) {
            Class<?> type = target.getClass();
            while (type != null) {
                try { field = type.getDeclaredField(name); break; }
                catch (NoSuchFieldException missing) { type = type.getSuperclass(); }
            }
            if (field == null) throw new NoSuchFieldException(key);
            field.setAccessible(true);
            fields.put(key, field);
        }
        return field.get(target);
    }
    static Object call(Object target, String name) throws ReflectiveOperationException {
        String key = target.getClass().getName() + "/" + name;
        Method method = methods.get(key);
        if (method == null) {
            method = target.getClass().getMethod(name);
            method.setAccessible(true);
            methods.put(key, method);
        }
        return method.invoke(target);
    }
}
