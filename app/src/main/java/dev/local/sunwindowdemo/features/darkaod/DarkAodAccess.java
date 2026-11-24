package dev.local.sunwindowdemo.features.darkaod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;

/** The verified DozeMachine ABI only; an incomplete ABI leaves native doze untouched. */
final class DarkAodAccess {
    final Class<?> service, triggers;
    final Method create, destroy, transition, request, userId, alwaysOn, near, proximityChanged, proximityEvent;
    final Field machine, state, parts, queue, tracker, config, host, suppressed, battery, powerSave;
    final Field sensors, proximity, registered, paused;
    final Object aod, pausing, pausedState;

    DarkAodAccess(ClassLoader loader) throws ReflectiveOperationException {
        service = Class.forName("com.android.systemui.doze.DozeService", false, loader);
        Class<?> machineClass = Class.forName("com.android.systemui.doze.DozeMachine", false, loader);
        Class<?> states = Class.forName("com.android.systemui.doze.DozeMachine$State", false, loader);
        triggers = Class.forName("com.android.systemui.doze.DozeTriggers", false, loader);
        Class<?> prox = Class.forName("com.android.systemui.util.sensors.ProximitySensorImpl", false, loader);
        create = method(service, "onCreate");
        destroy = method(service, "onDestroy");
        transition = method(machineClass, "transitionTo", states, int.class);
        request = method(machineClass, "requestState", states);
        machine = field(service, "mDozeMachine");
        state = field(machineClass, "mState");
        parts = field(machineClass, "mParts");
        queue = field(machineClass, "mQueuedRequests");
        tracker = field(machineClass, "mUserTracker");
        config = field(machineClass, "mAmbientDisplayConfig");
        host = field(machineClass, "mDozeHost");
        // LineageOS 24.0 declares mDozeHost as the DozeHost interface; PixelOS narrows it to the
        // implementation. Resolve the verified implementation class without assuming a field shape.
        Class<?> dozeHost = Class.forName("com.android.systemui.statusbar.phone.DozeServiceHost", false, loader);
        if (!host.getType().isAssignableFrom(dozeHost))
            throw new NoSuchFieldException("DozeServiceHost is not the native mDozeHost");
        suppressed = field(dozeHost, "mAlwaysOnSuppressed");
        battery = field(dozeHost, "mBatteryController");
        powerSave = field(Class.forName("com.android.systemui.statusbar.policy.BatteryControllerImpl", false, loader), "mAodPowerSave");
        userId = method(Class.forName("com.android.systemui.settings.UserTrackerImpl", false, loader), "getUserId");
        alwaysOn = method(config.getType(), "alwaysOnEnabled", int.class);
        sensors = field(triggers, "mDozeSensors");
        proximity = field(sensors.getType(), "mProximitySensor");
        near = method(prox, "isNear");
        Method alert = null, event = null;
        try {
            Method callback = method(prox, "alertListeners");
            if (callback.getReturnType() == void.class) {
                event = method(prox, "onSensorEvent",
                        Class.forName("com.android.systemui.util.sensors.ThresholdSensorEvent", false, loader));
                alert = callback;
            }
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            // Other builds can keep the original lux/transition-based behavior.
        }
        proximityChanged = alert;
        proximityEvent = event;
        registered = field(prox, "mRegistered");
        paused = field(prox, "mPaused");
        if (near.getReturnType() != Boolean.class || transition.getReturnType() != void.class)
            throw new NoSuchMethodException("Doze ABI types");
        aod = states.getField("DOZE_AOD").get(null);
        pausing = states.getField("DOZE_AOD_PAUSING").get(null);
        pausedState = states.getField("DOZE_AOD_PAUSED").get(null);
    }

    Object proximity(Object owner) throws ReflectiveOperationException {
        for (Object part : (Object[]) parts.get(owner))
            if (triggers.isInstance(part)) return proximity.get(sensors.get(part));
        throw new IllegalStateException("DozeTriggers absent");
    }

    Boolean near(Object prox) throws ReflectiveOperationException {
        // isNear alone can return a stale cached far while native sensing is paused.
        return registered.getBoolean(prox) && !paused.getBoolean(prox) ? (Boolean) near.invoke(prox) : null;
    }

    boolean allowed(Object owner, int processUser) throws ReflectiveOperationException {
        Object nativeHost = host.get(owner);
        return (Integer) userId.invoke(tracker.get(owner)) == processUser
                && (Boolean) alwaysOn.invoke(config.get(owner), processUser)
                && !suppressed.getBoolean(nativeHost)
                && !powerSave.getBoolean(battery.get(nativeHost));
    }

    boolean idle(Object owner) throws IllegalAccessException {
        return ((Collection<?>) queue.get(owner)).isEmpty();
    }

    boolean isPaused(Object value) { return value == pausing || value == pausedState; }
    String name(Object value) { return ((Enum<?>) value).name(); }

    static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { Field value = c.getDeclaredField(name); value.setAccessible(true); return value; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    static Method method(Class<?> type, String name, Class<?>... args) throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { Method value = c.getDeclaredMethod(name, args); value.setAccessible(true); return value; }
            catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(name);
    }
}
