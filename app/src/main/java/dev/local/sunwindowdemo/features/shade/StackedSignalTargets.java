package dev.local.sunwindowdemo.features.shade;

import java.lang.reflect.*;
import java.util.*;

/**
 * Selects stacked Compose entry points by declared parameter types, not by the
 * Compose value-class name suffix. Android-free so the selection is unit-testable.
 *
 * Legacy (split-shade device): StackedMobileIcon-sW7UJKQ(DualSim,long,String,Modifier,Composer,int)
 * QPR2 (CP4A.261105.040.A1):   StackedMobileIcon-3IgeMak(DualSim,long,Modifier,Composer,int)
 */
final class StackedSignalTargets {
    static final String NAME = "StackedMobileIcon";
    private StackedSignalTargets() {}

    private static boolean named(Method m) {
        return Modifier.isStatic(m.getModifiers()) && m.getReturnType() == void.class
            && (m.getName().equals(NAME) || m.getName().startsWith(NAME + "-"));
    }

    static Method render(Class<?> draw, Class<?> dual, Class<?> modifier, Class<?> composer) throws NoSuchMethodException {
        List<List<Class<?>>> shapes = List.of(
            List.<Class<?>>of(dual, long.class, String.class, modifier, composer, int.class),
            List.<Class<?>>of(dual, long.class, modifier, composer, int.class));
        Method found = null;
        for (Method m : draw.getDeclaredMethods()) {
            if (!named(m) || !shapes.contains(Arrays.asList(m.getParameterTypes()))) continue;
            if (found != null) throw new NoSuchMethodException(draw.getName() + "#" + NAME + "-* ambiguous render: " + found.getName() + ", " + m.getName());
            found = m;
        }
        if (found == null) throw new NoSuchMethodException(draw.getName() + "#" + NAME + "-*(DualSim,long,[String,]Modifier,Composer,int)V; declared " + declared(draw));
        found.setAccessible(true);
        return found;
    }

    /** Compose passes the {@code $changed} bit mask as the last int parameter. */
    static int changedIndex(Method render) {
        Class<?>[] types = render.getParameterTypes();
        return types.length - 1;
    }

    /** Parent composable taking the stacked view model (interface on legacy, Impl class on QPR2). */
    static Method parent(Class<?> draw, Class<?> modifier, Class<?> composer) throws NoSuchMethodException {
        Method found = null;
        for (Method m : draw.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (!named(m) || !m.getName().equals(NAME) || p.length != 4 || p[0].isPrimitive()
                || p[1] != modifier || p[2] != composer || p[3] != int.class
                || !p[0].getSimpleName().startsWith("StackedMobileIconViewModel")) continue;
            if (found != null) throw new NoSuchMethodException(draw.getName() + "#" + NAME + " ambiguous parent");
            found = m;
        }
        if (found == null) throw new NoSuchMethodException(draw.getName() + "#" + NAME + "(StackedMobileIconViewModel*,Modifier,Composer,int)V; declared " + declared(draw));
        return found;
    }

    /** The restart lambda captures DualSim and is a two-argument Compose function. */
    static Method restart(Class<?> lambda, Class<?> dual) throws NoSuchMethodException {
        boolean captures = false;
        for (Field f : lambda.getDeclaredFields()) if (f.getType() == dual) captures = true;
        if (!captures) throw new NoSuchMethodException(lambda.getName() + " has no " + dual.getSimpleName() + " field");
        try {
            return lambda.getDeclaredMethod("invoke", Object.class, Object.class);
        } catch (NoSuchMethodException missing) {
            throw new NoSuchMethodException(lambda.getName() + "#invoke(Object,Object)");
        }
    }

    static Method method(Class<?> owner, String name, Class<?>... types) throws NoSuchMethodException {
        try {
            Method m = owner.getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException missing) {
            throw new NoSuchMethodException(owner.getName() + "#" + name + signature(types));
        }
    }

    static Constructor<?> constructor(Class<?> owner, Class<?>... types) throws NoSuchMethodException {
        try {
            Constructor<?> c = owner.getDeclaredConstructor(types);
            c.setAccessible(true);
            return c;
        } catch (NoSuchMethodException missing) {
            throw new NoSuchMethodException(owner.getName() + "#<init>" + signature(types));
        }
    }

    static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException missing) {
            throw new NoSuchFieldException(owner.getName() + "." + name);
        }
    }

    /** Exception class plus the missing member, bounded for the module log. */
    static String describe(Throwable failure) {
        String message = failure.getMessage();
        String text = failure.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : " " + message);
        return text.length() > 400 ? text.substring(0, 400) + "..." : text;
    }

    private static String signature(Class<?>[] types) {
        StringJoiner joiner = new StringJoiner(",", "(", ")");
        for (Class<?> type : types) joiner.add(type.getSimpleName());
        return joiner.toString();
    }

    private static String declared(Class<?> draw) {
        StringJoiner joiner = new StringJoiner(" ");
        for (Method m : draw.getDeclaredMethods()) if (named(m)) joiner.add(m.getName() + signature(m.getParameterTypes()));
        return joiner.toString();
    }
}
