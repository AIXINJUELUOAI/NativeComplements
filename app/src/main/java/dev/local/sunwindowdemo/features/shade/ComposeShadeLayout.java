package dev.local.sunwindowdemo.features.shade;

import android.content.res.Configuration;
import android.content.res.Resources;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/** Feeds the native Compose shade-mode flow; never rewrites its readonly StateFlow. */
final class ComposeShadeLayout {
    private static final ThreadLocal<Boolean> readingShadeConfig = new ThreadLocal<>();
    private static final List<WeakReference<Object>> callbacks = new ArrayList<>();
    private static final Set<Object> flows = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<Class<?>, Method> collectMethods = new java.util.concurrent.ConcurrentHashMap<>();
    private static Method onConfigChanged;
    private static volatile int splitId, fullWidthId;
    private ComposeShadeLayout() {}

    private record Targets(Constructor<?>[] initial, Method emit, Constructor<?>[] callbacks, Method configChanged,
                           Method flowGetter) {}

    private static Targets resolve(ClassLoader loader) throws Exception {
        Class<?> repository = Class.forName("com.android.systemui.shade.data.repository.ShadeConfigRepository", false, loader);
        Method flowGetter = null;
        Class<?> initial = null;
        Method emit = null;
        // Keep the previously verified PixelOS field/R8 lambda path first; fall back to the real
        // Kotlin getter/Flow instance when a build no longer exposes that emitter class.
        try {
            if (hasField(repository, "legacyUseSplitShade")) {
                initial = Class.forName(
                        "com.android.systemui.shade.domain.interactor.ShadeModeInteractorImpl", false, loader);
                emit = emitter(repository, "$special$$inlined$map$1$2", loader);
            } else if (hasField(repository, "legacyUseSplitShade$delegate")) {
                initial = repository;
                emit = emitter(repository, "$onActivated$2$1$1", loader);
            } else {
                throw new NoSuchMethodException("no legacyUseSplitShade field");
            }
        } catch (ClassNotFoundException | NoSuchMethodException oldShapeUnavailable) {
            flowGetter = repository.getDeclaredMethod("getLegacyUseSplitShade");
            flowGetter.setAccessible(true);
            if (!Class.forName("kotlinx.coroutines.flow.Flow", false, loader)
                    .isAssignableFrom(flowGetter.getReturnType()))
                throw new NoSuchMethodException("legacyUseSplitShade is not a Flow");
            initial = repository;
        }
        Constructor<?>[] callbackCtors = new Constructor<?>[0];
        Method configChanged = null;
        try {
            Class<?> callback = Class.forName("com.android.systemui.common.ui.data.repository.ConfigurationRepositoryImpl$onConfigurationChange$1$callback$1", false, loader);
            configChanged = callback.getDeclaredMethod("onConfigChanged", Configuration.class);
            configChanged.setAccessible(true);
            callbackCtors = callback.getDeclaredConstructors();
        } catch (ClassNotFoundException | NoSuchMethodException absent) {
            // Optional refresh only; the host flow remains the authoritative trigger.
        }
        return new Targets(initial.getDeclaredConstructors(), emit, callbackCtors, configChanged, flowGetter);
    }

    private static Method emitter(Class<?> repository, String suffix, ClassLoader loader) throws Exception {
        Class<?> emitter = Class.forName(repository.getName() + suffix, false, loader);
        Method emit = emitter.getDeclaredMethod("emit", Object.class, Class.forName("kotlin.coroutines.Continuation", false, loader));
        emit.setAccessible(true);
        return emit;
    }

    private static boolean hasField(Class<?> type, String name) {
        try { type.getDeclaredField(name); return true; }
        catch (NoSuchFieldException absent) { return false; }
    }

    static void install(XposedModule module, ClassLoader loader, List<XposedInterface.HookHandle> hooks,
                        Predicate<Resources> force) throws Exception {
        Targets targets = resolve(loader);
        hooks.add(module.hook(Resources.class.getDeclaredMethod("getBoolean", int.class))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object original = chain.proceed();
                    int id = (Integer) chain.getArg(0);
                    if (!Boolean.TRUE.equals(readingShadeConfig.get())) return original;
                    Resources resources = (Resources) chain.getThisObject();
                    resolveResourceIds(resources);
                    if (id != splitId && id != fullWidthId) return original;
                    return overrideBoolean(original, id, true,
                            force.test(resources), splitId, fullWidthId);
                }));
        if (targets.emit() != null) hooks.add(module.hook(targets.emit())
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Boolean before = readingShadeConfig.get();
            readingShadeConfig.set(true);
            try { return chain.proceed(); }
            finally { restoreScope(before); }
        }));
        for (Constructor<?> constructor : targets.initial()) {
            hooks.add(module.hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Boolean before = readingShadeConfig.get();
                readingShadeConfig.set(true);
                try {
                    Object result = chain.proceed();
                    if (targets.flowGetter() != null) try {
                        captureFlow(module, chain.getThisObject(), targets.flowGetter(), loader, hooks);
                    } catch (Throwable unavailable) {
                        // Keep the host constructor intact; only the optional forced layout is unavailable.
                        android.util.Log.w("NativeShade", "compose shade flow unavailable", unavailable);
                    }
                    return result;
                } finally { restoreScope(before); }
            }));
            if (!module.deoptimize(constructor)) throw new IllegalStateException("compose shade initial state deoptimize");
        }
        for (Constructor<?> constructor : targets.callbacks()) {
            hooks.add(module.hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                synchronized (callbacks) { callbacks.add(new WeakReference<>(chain.getThisObject())); }
                return result;
            }));
        }
        if (targets.emit() != null && !module.deoptimize(targets.emit()))
            throw new IllegalStateException("compose shade emitter deoptimize");
        onConfigChanged = targets.configChanged();
    }

    /** LineageOS 24.0: scope the real Flow instance, not an R8-numbered lambda class. */
    private static void captureFlow(XposedModule module, Object repository, Method getter,
                                    ClassLoader loader, List<XposedInterface.HookHandle> hooks) throws Exception {
        Object flow = getter.invoke(repository);
        if (flow == null) throw new IllegalStateException("shade legacy flow is null");
        synchronized (flows) { if (!flows.add(flow)) return; }
        Method collect = collectMethods.get(flow.getClass());
        if (collect == null) {
            Class<?> collector = Class.forName("kotlinx.coroutines.flow.FlowCollector", false, loader);
            Class<?> continuation = Class.forName("kotlin.coroutines.Continuation", false, loader);
            for (Class<?> type = flow.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    collect = type.getDeclaredMethod("collect", collector, continuation);
                    collect.setAccessible(true);
                    break;
                } catch (NoSuchMethodException absent) { }
            }
            if (collect == null) {
                try {
                    collect = flow.getClass().getMethod("collect", collector, continuation);
                    collect.setAccessible(true);
                } catch (NoSuchMethodException absent) { }
            }
            if (collect == null) throw new NoSuchMethodException(flow.getClass().getName() + "#collect");
            collectMethods.put(flow.getClass(), collect);
        }
        if (!module.deoptimize(collect)) throw new IllegalStateException("compose shade flow deoptimize");
        Method target = collect;
        hooks.add(module.hook(target).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            if (!flows.contains(chain.getThisObject())) return chain.proceed();
            Boolean before = readingShadeConfig.get();
            readingShadeConfig.set(true);
            try { return chain.proceed(); }
            finally { restoreScope(before); }
        }));
    }

    // The field is only an ABI discriminator; never read or write the native flow.
    static boolean hydrated(Class<?> repository) throws NoSuchFieldException {
        try {
            repository.getDeclaredField("legacyUseSplitShade");
            return false;
        } catch (NoSuchFieldException newer) {
            repository.getDeclaredField("legacyUseSplitShade$delegate");
            return true;
        }
    }

    // R classes can be removed by R8 even when JADX prints symbolic R.bool references.
    // Resolve from the actual host resource table, never the module's resource namespace.
    static synchronized void resolveResourceIds(Resources resources) {
        if (splitId != 0 && fullWidthId != 0) return;
        int split = resources.getIdentifier("config_use_split_notification_shade", "bool", "com.android.systemui");
        int fullWidth = resources.getIdentifier("config_isFullWidthShade", "bool", "com.android.systemui");
        if (split == 0 || fullWidth == 0) throw new Resources.NotFoundException("SystemUI shade booleans");
        splitId = split;
        fullWidthId = fullWidth;
    }

    private static void restoreScope(Boolean before) {
        if (before == null) readingShadeConfig.remove(); else readingShadeConfig.set(before);
    }

    static Object overrideBoolean(Object original, int id, boolean scoped, boolean force, int split, int fullWidth) {
        Boolean override = ShadePolicy.resourceOverride(id, scoped, force, split, fullWidth);
        return override == null ? original : override;
    }

    static void refresh(Configuration current) throws Exception {
        if (onConfigChanged == null) return;
        List<Object> live = new ArrayList<>();
        synchronized (callbacks) {
            callbacks.removeIf(ref -> ref.get() == null);
            for (WeakReference<Object> ref : callbacks) { Object value = ref.get(); if (value != null) live.add(value); }
        }
        // Only notify this repository's callback: no global fake configuration or rotation.
        for (Object callback : live) onConfigChanged.invoke(callback, new Configuration(current));
    }
}
