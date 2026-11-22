package dev.local.sunwindowdemo.features.alive;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.UserHandle;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Local permission exception for applying the module's own live wallpaper.
 *
 * <p>LineageOS' WallpaperPicker2 does not export an
 * {@link WallpaperManager#ACTION_CHANGE_LIVE_WALLPAPER} activity. The app can therefore ask this
 * system_server bridge to bind its own component. Both the direct binder entry and the explicit
 * broadcast are limited to the module package/UID, the two exact Alive services, the current user
 * and FLAG_SYSTEM|FLAG_LOCK. No other package, component or destination is accepted.</p>
 */
public final class AliveWallpaperAccess {
    private static final String MODULE = "dev.nativecomplements.module";
    private static final ComponentName PLAIN = new ComponentName(MODULE,
        "dev.local.sunwindowdemo.features.alive.AliveWallpaperService");
    private static final ComponentName AMBIENT = new ComponentName(MODULE,
        "dev.local.sunwindowdemo.features.alive.AliveAmbientWallpaperService");
    private static final int BOTH = WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK;

    private AliveWallpaperAccess() {}

    public static void install(XposedModule module, ClassLoader loader, Consumer<String> log) {
        List<XposedInterface.HookHandle> hooks = new ArrayList<>();
        try {
            Class<?> service = Class.forName("com.android.server.wallpaper.WallpaperManagerService", false, loader);
            Class<?> description = Class.forName("android.app.wallpaper.WallpaperDescription", false, loader);
            Class<?> callback = Class.forName("android.os.IRemoteCallback", false, loader);
            Method checked = service.getDeclaredMethod("setWallpaperComponentChecked",
                description, String.class, int.class, int.class);
            Method component = description.getDeclaredMethod("getComponent");
            Field context = service.getDeclaredField("mContext");
            Method apply = descriptionApply(service, description, callback);
            Method supported = service.getMethod("isWallpaperSupported", String.class);
            Method allowed = service.getMethod("isSetWallpaperAllowed", String.class);
            Class<?> builder = Class.forName(description.getName() + "$Builder", false, loader);
            Constructor<?> builderInit = builder.getDeclaredConstructor();
            Method setComponent = builder.getDeclaredMethod("setComponent", ComponentName.class);
            Method build = builder.getDeclaredMethod("build");
            Method ready = service.getDeclaredMethod("systemReady");
            if (checked.getReturnType() != void.class || component.getReturnType() != ComponentName.class
                    || !Context.class.isAssignableFrom(context.getType()) || apply == null
                    || apply.getReturnType() != boolean.class || supported.getReturnType() != boolean.class
                    || allowed.getReturnType() != boolean.class || build.getReturnType() != description
                    || setComponent.getReturnType() != builder || ready.getReturnType() != void.class) {
                throw new NoSuchMethodException("live wallpaper apply ABI");
            }
            checked.setAccessible(true);
            component.setAccessible(true);
            context.setAccessible(true);
            apply.setAccessible(true);
            supported.setAccessible(true);
            allowed.setAccessible(true);
            builderInit.setAccessible(true);
            setComponent.setAccessible(true);
            build.setAccessible(true);
            ready.setAccessible(true);
            Access access = new Access(context, apply, supported, allowed, builderInit, setComponent, build, log);
            hooks.add(module.hook(checked).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(access::checked));
            hooks.add(module.hook(ready).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    access.connect(chain.getThisObject());
                    return result;
                }));
            if (!module.deoptimize(checked)) throw new IllegalStateException("wallpaper apply deoptimize");
            log.accept("ALIVE_WALLPAPER_ACCESS_READY own component only");
        } catch (Throwable unavailable) {
            for (int index = hooks.size() - 1; index >= 0; index--) {
                try { hooks.get(index).unhook(); } catch (Throwable ignored) {}
            }
            log.accept("ALIVE_WALLPAPER_ACCESS_UNAVAILABLE " + unavailable.getClass().getSimpleName());
        }
    }

    /** LOS exposes the private description helper; older AOSP shapes expose one of the others. */
    private static Method descriptionApply(Class<?> service, Class<?> description, Class<?> callback) {
        try {
            return service.getDeclaredMethod("setWallpaperDescriptionInternal", description,
                String.class, int.class, int.class, boolean.class, boolean.class, callback);
        } catch (NoSuchMethodException absent) {}
        try {
            return service.getDeclaredMethod("setWallpaperDescription", description,
                String.class, int.class, int.class);
        } catch (NoSuchMethodException absent) {}
        try {
            return service.getDeclaredMethod("setWallpaperComponent", ComponentName.class,
                String.class, int.class, int.class);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static final class Access {
        private final Field context;
        private final Method apply, supported, allowed, setComponent, build;
        private final Constructor<?> builderInit;
        private final Consumer<String> log;
        private BroadcastReceiver receiver;

        Access(Field context, Method apply, Method supported, Method allowed,
               Constructor<?> builderInit, Method setComponent, Method build, Consumer<String> log) {
            this.context = context;
            this.apply = apply;
            this.supported = supported;
            this.allowed = allowed;
            this.builderInit = builderInit;
            this.setComponent = setComponent;
            this.build = build;
            this.log = log;
        }

        Object checked(XposedInterface.Chain chain) throws Throwable {
            Object owner = chain.getThisObject();
            Object request = chain.getArg(0);
            String packageName = (String) chain.getArg(1);
            int which = (Integer) chain.getArg(2);
            int user = (Integer) chain.getArg(3);
            int caller = Binder.getCallingUid();
            ComponentName target;
            Context system;
            try {
                // The hook already verified the description class; read its component without a
                // second arbitrary class lookup.
                target = descriptionComponent(request);
                system = (Context) context.get(owner);
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                return chain.proceed();
            }
            if (!own(system, caller, packageName, target, which, user)) return chain.proceed();
            if (!policy(owner, packageName)) return chain.proceed();
            apply(owner, request, packageName, which, user);
            log.accept("ALIVE_WALLPAPER_DIRECT_READY applied " + target.getClassName());
            return null;
        }

        void connect(Object service) {
            if (receiver != null) return;
            try {
                Context system = (Context) context.get(service);
                if (system == null) throw new IllegalStateException("wallpaper context");
                BroadcastReceiver next = new BroadcastReceiver() {
                    @Override public void onReceive(Context ignored, Intent intent) {
                        if (!AliveWallpaperProtocol.ACTION.equals(intent.getAction())) return;
                        ResultReceiver reply = intent.getParcelableExtra(
                            AliveWallpaperProtocol.EXTRA_RESULT, ResultReceiver.class);
                        Bundle answer = new Bundle();
                        int code = Activity.RESULT_CANCELED;
                        try {
                            int caller = getSentFromUid();
                            ComponentName target = intent.getParcelableExtra(
                                AliveWallpaperProtocol.EXTRA_COMPONENT, ComponentName.class);
                            int user = caller / 100000;
                            String[] packages = system.getPackageManager().getPackagesForUid(caller);
                            boolean owned = false;
                            if (packages != null) for (String name : packages) owned |= MODULE.equals(name);
                            if (owned && own(system, caller, MODULE, target, BOTH, user)) {
                                Object request = build(target);
                                // The broadcast callback is not a binder transaction, so the service's
                                // Binder.getCallingUid() policy helpers no longer see the sender.
                                AppOpsManager ops = system.getSystemService(AppOpsManager.class);
                                if (ops != null && ops.checkOpNoThrow("android:write_wallpaper",
                                        caller, MODULE) != AppOpsManager.MODE_ALLOWED)
                                    throw new IllegalStateException("wallpaper op denied");
                                apply(service, request, MODULE, BOTH, user);
                                code = Activity.RESULT_OK;
                                log.accept("ALIVE_WALLPAPER_BROADCAST_READY applied " + target.getClassName());
                            } else {
                                answer.putString(AliveWallpaperProtocol.EXTRA_MESSAGE, "request rejected");
                            }
                        } catch (Throwable failure) {
                            answer.putString(AliveWallpaperProtocol.EXTRA_MESSAGE,
                                failure.getClass().getSimpleName());
                            log.accept("ALIVE_WALLPAPER_BROADCAST_FAILED " + failure.getClass().getSimpleName());
                        }
                        if (reply != null) reply.send(code, answer);
                    }
                };
                system.registerReceiver(next, new IntentFilter(AliveWallpaperProtocol.ACTION),
                    AliveWallpaperProtocol.CONTROL, new Handler(Looper.getMainLooper()),
                    Context.RECEIVER_EXPORTED);
                receiver = next;
                log.accept("ALIVE_WALLPAPER_BROADCAST_READY");
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                log.accept("ALIVE_WALLPAPER_BROADCAST_UNAVAILABLE " + unavailable.getClass().getSimpleName());
            }
        }

        private ComponentName descriptionComponent(Object description) throws ReflectiveOperationException {
            try {
                Method method = description.getClass().getDeclaredMethod("getComponent");
                method.setAccessible(true);
                return (ComponentName) method.invoke(description);
            } catch (NoSuchMethodException absent) {
                return (ComponentName) description.getClass().getMethod("getComponent").invoke(description);
            } catch (InvocationTargetException failure) {
                throw new ReflectiveOperationException(failure.getCause());
            }
        }

        private Object build(ComponentName target) throws ReflectiveOperationException {
            Object value = builderInit.newInstance();
            setComponent.invoke(value, target);
            Object result = build.invoke(value);
            if (result == null) throw new IllegalStateException("wallpaper description");
            return result;
        }

        private boolean own(Context system, int caller, String packageName, ComponentName target,
                            int which, int user) {
            if (system == null || target == null || !MODULE.equals(packageName)
                    || !(PLAIN.equals(target) || AMBIENT.equals(target))
                    || which != BOTH || user != caller / 100000) return false;
            try {
                String[] packages = system.getPackageManager().getPackagesForUid(caller);
                if (packages != null) for (String name : packages) if (MODULE.equals(name)) return true;
            } catch (RuntimeException ignored) {}
            return false;
        }

        private boolean policy(Object owner, String packageName) {
            try {
                return Boolean.TRUE.equals(supported.invoke(owner, packageName))
                    && Boolean.TRUE.equals(allowed.invoke(owner, packageName));
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                return false;
            }
        }

        private void apply(Object owner, Object description, String packageName, int which, int user)
                throws ReflectiveOperationException {
            long identity = Binder.clearCallingIdentity();
            try {
                Object result;
                if (apply.getParameterCount() == 7) {
                    result = apply.invoke(owner, description, packageName, which, user, true, true, null);
                } else if (apply.getParameterCount() == 4) {
                    result = apply.invoke(owner, description, packageName, which, user);
                } else {
                    throw new NoSuchMethodException("wallpaper apply arity");
                }
                if (Boolean.FALSE.equals(result)) throw new IllegalStateException("Alive wallpaper bind failed");
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new ReflectiveOperationException(cause);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }
}
