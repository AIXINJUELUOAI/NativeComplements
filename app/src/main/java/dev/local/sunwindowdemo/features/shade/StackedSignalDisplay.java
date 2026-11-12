package dev.local.sunwindowdemo.features.shade;

import java.lang.reflect.*;
import java.util.List;
import java.util.ArrayList;
import java.lang.ref.WeakReference;
import java.util.function.IntBinaryOperator;

/** Copies the native presentation model; never mutates the shared signal model. */
final class StackedSignalDisplay {
    private final Field primary, secondary, primarySub, secondarySub, level, count, changing, exclamation;
    private final Constructor<?> cellular, pair;
    private final Method parse;
    private record Copy(WeakReference<Object> adjusted, Object original) {}
    private final List<Copy> copies = new ArrayList<>();

    Object original(Object model) {
        copies.removeIf(copy -> copy.adjusted.get() == null);
        for (Copy copy : copies) if (copy.adjusted.get() == model) return copy.original;
        return model;
    }

    StackedSignalDisplay(Class<?> dual, Class<?> cell, Class<?> pairClass, Method parse) throws Exception {
        primary = member(dual, "primary"); secondary = member(dual, "secondary");
        primarySub = member(dual, "primarySubId"); secondarySub = member(dual, "secondarySubId");
        level = member(cell, "level"); count = member(cell, "numberOfLevels");
        changing = member(cell, "carrierNetworkChange"); exclamation = member(cell, "showExclamationMark");
        cellular = cell.getDeclaredConstructor(int.class, int.class, boolean.class, boolean.class);
        pair = pairClass.getDeclaredConstructor(Object.class, Object.class);
        this.parse = parse;
    }
    private static Field member(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    Object adjust(Object nativeModel, IntBinaryOperator adjust) throws Exception {
        nativeModel = original(nativeModel);
        if (nativeModel == null) return null;
        Object first = primary.get(nativeModel), second = secondary.get(nativeModel);
        int firstSub = primarySub.getInt(nativeModel), secondSub = secondarySub.getInt(nativeModel);
        if (firstSub < 0 || secondSub < 0 || firstSub == secondSub) return nativeModel;
        Object a = cell(first, firstSub, adjust), b = cell(second, secondSub, adjust);
        if (a == first && b == second) return nativeModel;
        Object result = parse.invoke(null, List.of(pair.newInstance(firstSub, a), pair.newInstance(secondSub, b)));
        if (result == null) return nativeModel;
        for (Copy copy : copies) {
            Object previous = copy.adjusted.get();
            if (copy.original == nativeModel && result.equals(previous)) return previous;
        }
        copies.add(new Copy(new WeakReference<>(result), nativeModel));
        return result;
    }
    private Object cell(Object model, int sub, IntBinaryOperator adjust) throws Exception {
        int levels = count.getInt(model), nativeLevel = level.getInt(model);
        if (changing.getBoolean(model) || (levels != 5 && levels != 6)) return model;
        int state = (levels << 8) | nativeLevel;
        int adjusted = adjust.applyAsInt(state, sub) & 255;
        if (adjusted == nativeLevel || adjusted < 0 || adjusted >= levels) return model;
        return cellular.newInstance(adjusted, levels, exclamation.getBoolean(model), false);
    }
}
