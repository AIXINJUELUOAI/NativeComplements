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

    private final Class<?> dual, cell;
    private final Object unsafe;
    private final Method allocate;

    /**
     * Legacy SystemUI builds copies through DualSimKt.tryParseDualSim. QPR2 removed the parser and R8
     * dropped the DualSim constructor (the view model writes the four fields after new-instance), so a
     * null parser selects allocation plus the same four field writes.
     */
    StackedSignalDisplay(Class<?> dual, Class<?> cell, Class<?> pairClass, Method parse) throws Exception {
        this.dual = dual; this.cell = cell;
        primary = member(dual, "primary"); secondary = member(dual, "secondary");
        primarySub = member(dual, "primarySubId"); secondarySub = member(dual, "secondarySubId");
        level = member(cell, "level"); count = member(cell, "numberOfLevels");
        changing = member(cell, "carrierNetworkChange"); exclamation = member(cell, "showExclamationMark");
        cellular = StackedSignalTargets.constructor(cell, int.class, int.class, boolean.class, boolean.class);
        pair = StackedSignalTargets.constructor(pairClass, Object.class, Object.class);
        this.parse = parse;
        if (parse == null) {
            for (Field f : new Field[]{primary, secondary})
                if (!f.getType().isAssignableFrom(cell)) throw new NoSuchFieldException(dual.getName() + "#" + f.getName() + " type " + f.getType().getName());
            Class<?> type = Class.forName("sun.misc.Unsafe");
            Field instance = type.getDeclaredField("theUnsafe"); instance.setAccessible(true);
            unsafe = instance.get(null);
            allocate = type.getMethod("allocateInstance", Class.class);
            // Fail preflight before installing a hook if ART rejects allocation or final-field writes.
            Object probeCell = cellular.newInstance(0, 5, false, false);
            Object probe = build(1, probeCell, 2, probeCell);
            if (primary.get(probe) != probeCell || secondary.get(probe) != probeCell
                    || primarySub.getInt(probe) != 1 || secondarySub.getInt(probe) != 2)
                throw new IllegalStateException("DualSim field write preflight");
        } else { unsafe = null; allocate = null; }
    }
    private Object build(int firstSub, Object a, int secondSub, Object b) throws Exception {
        if (parse != null) return parse.invoke(null, List.of(pair.newInstance(firstSub, a), pair.newInstance(secondSub, b)));
        Object result = allocate.invoke(unsafe, dual);
        primarySub.setInt(result, firstSub); secondarySub.setInt(result, secondSub);
        primary.set(result, a); secondary.set(result, b);
        return result;
    }
    private static Field member(Class<?> type, String name) throws Exception {
        return StackedSignalTargets.field(type, name);
    }
    Object adjust(Object nativeModel, IntBinaryOperator adjust) throws Exception {
        nativeModel = original(nativeModel);
        if (nativeModel == null) return null;
        Object first = primary.get(nativeModel), second = secondary.get(nativeModel);
        int firstSub = primarySub.getInt(nativeModel), secondSub = secondarySub.getInt(nativeModel);
        if (firstSub < 0 || secondSub < 0 || firstSub == secondSub) return nativeModel;
        Object a = cell(first, firstSub, adjust), b = cell(second, secondSub, adjust);
        if (a == first && b == second) return nativeModel;
        Object result = build(firstSub, a, secondSub, b);
        if (result == null) return nativeModel;
        for (Copy copy : copies) {
            Object previous = copy.adjusted.get();
            if (copy.original == nativeModel && result.equals(previous)) return previous;
        }
        copies.add(new Copy(new WeakReference<>(result), nativeModel));
        return result;
    }
    private Object cell(Object model, int sub, IntBinaryOperator adjust) throws Exception {
        // QPR2 types the DualSim slots as CellularTypeIconModel; satellite entries stay native.
        if (model == null || model.getClass() != cell) return model;
        int levels = count.getInt(model), nativeLevel = level.getInt(model);
        if (changing.getBoolean(model) || (levels != 5 && levels != 6)) return model;
        int state = (levels << 8) | nativeLevel;
        int adjusted = adjust.applyAsInt(state, sub) & 255;
        if (adjusted == nativeLevel || adjusted < 0 || adjusted >= levels) return model;
        return cellular.newInstance(adjusted, levels, exclamation.getBoolean(model), false);
    }
}
