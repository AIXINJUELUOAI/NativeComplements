package dev.local.sunwindowdemo.features.background;

import android.location.LocationRequest;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.WeakHashMap;

import static dev.local.sunwindowdemo.features.background.BackgroundHost.*;

final class BackgroundGpsAccess {
    private BackgroundGpsAccess() {}

    static void install(io.github.libxposed.api.XposedModule module, ClassLoader loader,
                        BackgroundHost host, java.util.function.Consumer<String> log) {
        try {
            Class<?> manager = target(loader, "location.provider.LocationProviderManager");
            Class<?> registration = target(loader, "location.provider.LocationProviderManager$Registration");
            Class<?> identity = type(loader, "android.location.util.identity.CallerIdentity");
            Field owner = field(registration, "this$0", manager);
            Method provider = method(manager, "getName", String.class);
            Method request = method(registration, "getRequest", LocationRequest.class);
            Method caller = method(registration, "getIdentity", identity);
            Method uid = method(identity, "getUid", int.class);
            Method active = method(registration, "onActive", void.class);
            Method inactive = method(registration, "onInactive", void.class);
            Map<Object, Integer> registrations = new WeakHashMap<>();
            hook(module, active, chain -> {
                Object result = chain.proceed();
                Object entry = chain.getThisObject();
                String name = (String) provider.invoke(owner.get(entry));
                LocationRequest location = (LocationRequest) request.invoke(entry);
                if (location.getMaxUpdates() > 1 && location.getIntervalMillis() <= 60_000
                        && ("gps".equals(name) || "fused".equals(name) && location.getQuality() == LocationRequest.QUALITY_HIGH_ACCURACY)) {
                    int app = (Integer) uid.invoke(caller.invoke(entry));
                    host.worker.post(() -> {
                        registrations.put(entry, app);
                        host.gps(new HashSet<>(registrations.values()));
                    });
                }
                return result;
            });
            hook(module, inactive, chain -> {
                Object result = chain.proceed();
                Object entry = chain.getThisObject();
                host.worker.post(() -> {
                    if (registrations.remove(entry) != null) host.gps(new HashSet<>(registrations.values()));
                });
                return result;
            });
            host.gpsReady();
            log.accept("BACKGROUND_GPS_READY");
        } catch (ReflectiveOperationException | RuntimeException failure) { host.unavailable("GPS", failure); }
    }
}
