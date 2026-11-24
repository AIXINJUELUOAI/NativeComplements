package dev.local.sunwindowdemo.features.shade;

import android.content.res.Configuration;
import android.content.res.Resources;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Feeds the native Compose shade-mode flow; never rewrites its readonly StateFlow. */
final class ComposeShadeLayout {
    private static final ThreadLocal<Boolean> readingShadeConfig = new ThreadLocal<>();
    private static final List<WeakReference<Object>> callbacks = new ArrayList<>();
    private static final List<WeakReference<Resources>> repositoryResources = new ArrayList<>();
    private static volatile String repositoryOwner;
    private static Method onConfigChanged;
    private static volatile int splitId, fullWidthId;
    private ComposeShadeLayout() {}

    private record Targets(Constructor<?>[] initial, Method emit, Constructor<?>[] callbacks, Method configChanged,
                           Field resources) {}

    private static Targets resolve(ClassLoader loader) throws Exception {
        // Without this native trigger a preference change cannot restore a cached
        // distinct flow until rotation. Require it before installing any override.
        Class<?> callback = Class.forName("com.android.systemui.common.ui.data.repository.ConfigurationRepositoryImpl$onConfigurationChange$1$callback$1", false, loader);
        Method configChanged = callback.getDeclaredMethod("onConfigChanged", Configuration.class);
        if (configChanged.getReturnType() != void.class || java.lang.reflect.Modifier.isStatic(configChanged.getModifiers()))
            throw new NoSuchMethodException("shade configuration callback");
        configChanged.setAccessible(true);
        Class<?> repository = Class.forName("com.android.systemui.shade.data.repository.ShadeConfigRepository", false, loader);
        Field resources = null;
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
            Method flowGetter = repository.getDeclaredMethod("getLegacyUseSplitShade");
            flowGetter.setAccessible(true);
            if (java.lang.reflect.Modifier.isStatic(flowGetter.getModifiers())
                    || !Class.forName("kotlinx.coroutines.flow.Flow", false, loader)
                    .isAssignableFrom(flowGetter.getReturnType()))
                throw new NoSuchMethodException("legacyUseSplitShade is not a Flow");
            resources = repository.getDeclaredField("resources");
            if (resources.getType() != Resources.class) throw new NoSuchFieldException("shade Resources");
            resources.setAccessible(true);
            initial = repository;
        }
        return new Targets(initial.getDeclaredConstructors(), emit, callback.getDeclaredConstructors(), configChanged, resources);
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
                    return readBoolean(original, (Resources) chain.getThisObject(), (Integer) chain.getArg(0), force);
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
                    if (targets.resources() != null) {
                        Resources resources = (Resources) targets.resources().get(chain.getThisObject());
                        resolveResourceIds(resources);
                        synchronized (repositoryResources) { repositoryResources.add(new WeakReference<>(resources)); }
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
            if (!module.deoptimize(constructor)) throw new IllegalStateException("compose shade callback deoptimize");
        }
        if (targets.emit() != null && !module.deoptimize(targets.emit()))
            throw new IllegalStateException("compose shade emitter deoptimize");
        if (targets.resources() != null) {
            Class<?> repository = targets.resources().getDeclaringClass();
            for (Method method : repository.getDeclaredMethods())
                if (!module.deoptimize(method)) throw new IllegalStateException("compose shade repository deoptimize");
            repositoryOwner = repository.getName();
        }
        onConfigChanged = targets.configChanged();
    }

    static Object readBoolean(Object original, Resources resources, int id, Predicate<Resources> force) {
        boolean scoped = Boolean.TRUE.equals(readingShadeConfig.get());
        if (!scoped && !ownsResources(resources)) return original;
        resolveResourceIds(resources);
        if (id != splitId && id != fullWidthId) return original;
        if (!scoped && !repositoryEvaluation(Thread.currentThread().getStackTrace(), repositoryOwner)) return original;
        return overrideBoolean(original, id, true, force.test(resources), splitId, fullWidthId);
    }

    private static boolean ownsResources(Resources resources) {
        synchronized (repositoryResources) {
            repositoryResources.removeIf(ref -> ref.get() == null);
            for (WeakReference<Resources> ref : repositoryResources) if (ref.get() == resources) return true;
        }
        return false;
    }

    // Scope each resource evaluation, not collect(): collect returns COROUTINE_SUSPENDED
    // before later map emissions (possibly on another thread). Override BEFORE native
    // distinctUntilChanged so a settings refresh can emit the restored native value.
    static boolean repositoryEvaluation(StackTraceElement[] stack, String owner) {
        if (owner == null) return false;
        for (StackTraceElement frame : stack) {
            String type = frame.getClassName();
            if (type.equals(owner) && frame.getMethodName().equals("isFullWidthShade")) return true;
            if (type.startsWith(owner + "$") && frame.getMethodName().equals("emit")) return true;
        }
        return false;
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
