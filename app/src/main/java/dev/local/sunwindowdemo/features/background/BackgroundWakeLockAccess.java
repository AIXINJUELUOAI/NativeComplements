package dev.local.sunwindowdemo.features.background;

import android.content.Context;
import android.media.AudioManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.WorkSource;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import static dev.local.sunwindowdemo.features.background.BackgroundHost.*;
import dev.local.sunwindowdemo.features.background.BackgroundStore.WakeLockRule;

final class BackgroundWakeLockAccess {
    private static final int WAKE_LOCK_LEVEL_MASK = 0xffff;
    private final BackgroundHost host;
    private final Field powerLock;
    private final Field wakeLocks;
    private final Field flags;
    private final Field owner;
    private final Field pkg;
    private final Field workSource;
    private final Field disabled;
    private final Method updateDisabled;
    private final ThreadLocal<Object> restrict = new ThreadLocal<>();
    // Budget and lock state are accessed under the native power service lock.
    private final Map<Integer, BackgroundWakeBudget> budgets = new HashMap<>();
    private final Set<Object> limited = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Runnable refresh = this::reconcile;
    private final Runnable timer = this::onDeadline;
    private long scheduled = Long.MAX_VALUE;
    private volatile Object service;
    private volatile boolean callProtected = true;
    private volatile boolean healthy = true;

    private BackgroundWakeLockAccess(BackgroundHost host, Class<?> power, Class<?> wakeLock)
            throws ReflectiveOperationException {
        this.host = host;
        powerLock = field(power, "mLock", Object.class);
        wakeLocks = field(power, "mWakeLocks", ArrayList.class);
        flags = field(wakeLock, "mFlags", int.class);
        owner = field(wakeLock, "mOwnerUid", int.class);
        pkg = field(wakeLock, "mPackageName", String.class);
        workSource = field(wakeLock, "mWorkSource", WorkSource.class);
        disabled = field(wakeLock, "mDisabled", boolean.class);
        updateDisabled = method(power, "updateWakeLockDisabledStatesLocked", void.class);
    }

    static BackgroundWakeLockAccess install(XposedModule module, ClassLoader loader, BackgroundHost host)
            throws ReflectiveOperationException {
        Class<?> power = target(loader, "power.PowerManagerService");
        Class<?> wakeLock = target(loader, "power.PowerManagerService$WakeLock");
        var access = new BackgroundWakeLockAccess(host, power, wakeLock);
        Method decide = method(power, "setWakeLockDisabledStateLocked", boolean.class, wakeLock);
        // Verified AOSP/LineageOS setter. A second boolean has no established semantics here.
        Method setDisabled = disabledMethod(wakeLock);
        Method update = method(power, "updatePowerStateLocked", void.class);
        Method attribution = method(wakeLock, "updateWorkSource", void.class, WorkSource.class);
        Method ready = method(power, "systemReady", void.class);
        // The native decision can inline the small WakeLock setter in a compiled system image.
        if (!module.deoptimize(decide)) throw new IllegalStateException("wake lock decision deoptimization unavailable");
        var handles = new ArrayList<XposedInterface.HookHandle>();
        try {
            handles.add(hook(module, setDisabled, chain -> access.restrict.get() == chain.getThisObject()
                ? chain.proceed(new Object[]{true}) : chain.proceed()));
            handles.add(hook(module, decide, chain -> access.decide(chain)));
            handles.add(hook(module, update, chain -> {
                Object result = chain.proceed();
                access.observe(chain.getThisObject());
                return result;
            }));
            handles.add(hook(module, attribution, chain -> {
                if (!host.wakeLocksConfigured()) return chain.proceed();
                Object lock = chain.getThisObject();
                boolean delegated = access.workSource.get(lock) != null;
                Object result = chain.proceed();
                if (delegated != (access.workSource.get(lock) != null)) access.requestRefresh();
                return result;
            }));
            handles.add(hook(module, ready, chain -> {
                Object result = chain.proceed();
                access.service = chain.getThisObject();
                access.requestRefresh();
                return result;
            }));
            return access;
        } catch (RuntimeException failure) {
            for (var handle : handles) handle.unhook();
            throw failure;
        }
    }

    static Method disabledMethod(Class<?> wakeLock)throws NoSuchMethodException{
        return method(wakeLock,"setDisabled",boolean.class,boolean.class);
    }

    void connect(Context context) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) return;
        try {
            audio.addOnModeChangedListener(host.worker::post, this::audioModeChanged);
            audioModeChanged(audio.getMode());
        } catch (RuntimeException failure) { host.unavailable("WAKE_LOCK_CALL_STATE", failure); }
    }

    private void audioModeChanged(int mode) {
        callProtected = mode != AudioManager.MODE_NORMAL;
        requestRefresh();
    }

    void requestRefresh() {
        if (service == null) return;
        host.worker.removeCallbacks(refresh);
        host.worker.post(refresh);
    }

    private Object decide(XposedInterface.Chain chain) throws Throwable {
        if (chain.getThisObject() != service || (!host.wakeLocksConfigured() && limited.isEmpty())) return chain.proceed();
        Object lock = chain.getArg(0);
        long now = SystemClock.elapsedRealtime();
        var budget = lockBudget(lock, now);
        if (budget == null || !budget.exhausted(now)) {
            limited.remove(lock);
            return chain.proceed();
        }
        limited.add(lock);
        Object previous = restrict.get();
        restrict.set(lock);
        try { return chain.proceed(); }
        finally { if (previous == null) restrict.remove(); else restrict.set(previous); }
    }

    private BackgroundWakeBudget lockBudget(Object lock, long now) throws IllegalAccessException {
        if (!healthy || callProtected || !host.wakeLocksConfigured()
                || (flags.getInt(lock) & WAKE_LOCK_LEVEL_MASK) != PowerManager.PARTIAL_WAKE_LOCK
                || workSource.get(lock) != null) return null;
        String name = (String) pkg.get(lock);
        if (name == null) return null;
        int uid = owner.getInt(lock);
        var rule = host.wakeLockRule(uid, name);
        return rule == null ? null : budget(uid, rule, now);
    }

    private BackgroundWakeBudget budget(int uid, WakeLockRule rule, long now) {
        long change = host.foregroundChange(uid);
        var state = budgets.get(uid);
        if (state == null || state.foregroundChange != change || !state.rule.equals(rule)) {
            state = new BackgroundWakeBudget(rule, change, now);
            budgets.put(uid, state);
        }
        return state;
    }

    private void observe(Object power) {
        if (power != service || (!host.wakeLocksConfigured() && limited.isEmpty() && budgets.isEmpty())) return;
        try { sample(power); }
        catch (IllegalAccessException failure) {
            healthy = false;
            host.unavailable("WAKE_LOCK", failure);
            requestRefresh();
        }
    }

    private void sample(Object power) throws IllegalAccessException {
        long now = SystemClock.elapsedRealtime();
        budgets.keySet().removeIf(uid -> callProtected || !healthy || host.wakeLockRule(uid, null) == null);
        var held = new HashSet<Integer>();
        var active = new HashSet<Integer>();
        var liveLimited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        boolean changed = false;
        for (Object lock : (ArrayList<?>) wakeLocks.get(power)) {
            boolean wasLimited = limited.contains(lock);
            if (wasLimited) liveLimited.add(lock);
            var budget = lockBudget(lock, now);
            if (budget != null) {
                int uid = owner.getInt(lock);
                held.add(uid);
                if (!disabled.getBoolean(lock)) active.add(uid);
            }
            if (wasLimited != (budget != null && budget.exhausted(now))) changed = true;
        }
        limited.retainAll(liveLimited);
        long next = changed ? now : Long.MAX_VALUE;
        for (var entry : budgets.entrySet()) {
            int uid = entry.getKey();
            var budget = entry.getValue();
            budget.holding(active.contains(uid), now);
            next = Math.min(next, budget.deadline(now, held.contains(uid)));
        }
        long due = next == Long.MAX_VALUE ? next : SystemClock.uptimeMillis() + Math.max(0, next - now);
        if (due == scheduled) return;
        host.worker.removeCallbacks(timer);
        scheduled = due;
        if (due != Long.MAX_VALUE) host.worker.postAtTime(timer, due);
    }

    private void onDeadline() { reconcile(true); }

    private void reconcile() { reconcile(false); }

    private void reconcile(boolean deadline) {
        Object power = service;
        if (power == null) return;
        try {
            synchronized (powerLock.get(power)) {
                if (deadline) scheduled = Long.MAX_VALUE;
                if (!host.wakeLocksConfigured() && limited.isEmpty() && budgets.isEmpty()) return;
                if (!deadline && healthy && !callProtected) {
                    long now = SystemClock.elapsedRealtime();
                    for (int uid : host.activeUids()) {
                        var rule = host.wakeLockRule(uid, null);
                        if (rule != null) budget(uid, rule, now);
                    }
                }
                sample(power);
                updateDisabled.invoke(power);
                sample(power);
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            healthy = false;
            host.unavailable("WAKE_LOCK", failure);
            host.worker.removeCallbacks(timer);
            try {
                synchronized (powerLock.get(power)) {
                    scheduled = Long.MAX_VALUE;
                    if (!limited.isEmpty()) updateDisabled.invoke(power);
                }
            } catch (ReflectiveOperationException | RuntimeException restoreFailure) {
                host.unavailable("WAKE_LOCK_RESTORE", restoreFailure);
            }
        }
    }
}
