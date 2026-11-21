package dev.local.sunwindowdemo.features.background;

import android.app.ApplicationExitInfo;
import android.content.pm.ApplicationInfo;
import android.os.SystemClock;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

import static dev.local.sunwindowdemo.features.background.BackgroundHost.*;
import static dev.local.sunwindowdemo.features.background.BackgroundPolicy.*;

/** Android 17's process-state controller. Keep Android's Binder/freezer transactions and scheduling. */
final class BackgroundProcessAccess {
    private static final int CACHED_MIN_ADJ = 900;
    private static final int CACHED_MAX_ADJ = 999;
    private static final int MAX_ORDINARY_CACHE_EXEMPTIONS = 6;
    private static final int RETAINED_ADJ = 850;
    private static final int SERVICE_STATE = 10;
    private static final int LAST_ACTIVITY_STATE = 15;
    private static final int LAST_CACHED_STATE = 19;
    private static final long TRIM_DELAY_MS = 5000;
    private static final long MEMORY_SAMPLE_MS = 1000;
    static final long FREEZE_DELAY_MS = 5000;

    private record Decision(Action action, boolean message, boolean runningWork, Mode mode, int uid,
                            boolean keepActive, boolean reclaim) {
        boolean keepRunnable() { return runningWork || action == Action.ALLOW; }
        boolean strictFreeze() { return mode == Mode.STRICT && action == Action.FREEZE && !runningWork; }
    }

    private record ProcessStart(int pid, long sequence) {}
    private record CacheStateAccess(Method getState, Method setState) {}
    private record ReclaimKey(int uid, String pkg, String name) {}
    private record ReclaimTarget(Object app, ProcessStart start, ReclaimKey key, long session) {}

    private final BackgroundHost host;
    private final Class<?> process;
    private final Field appInfo;
    private final Field realUid;
    private final Field name;
    private final Field processLock;
    private final Method getAdj;
    private final Method setAdj;
    private final Method getPid;
    private final Method getStartSequence;
    private final Method getThread;
    private final Method kill;
    private final CacheStateAccess cacheState;
    private volatile StrictFreezer strict;
    // Held under the process lock. The record key distinguishes PID reuse.
    private final Map<Object, ProcessStart> pendingTrim = new WeakHashMap<>();
    private final Map<Object, ProcessStart> cacheExemptions = new WeakHashMap<>();
    private final Map<Object, ProcessStart> reclaimCandidates = new WeakHashMap<>();
    private boolean memoryCheckScheduled;
    // Only the host worker reads or writes the pressure round and session history.
    private final BackgroundMemoryPressure pressure = new BackgroundMemoryPressure();
    private final Map<ReclaimKey, Long> reclaimed = new HashMap<>();

    private BackgroundProcessAccess(ClassLoader loader, BackgroundHost host) throws ReflectiveOperationException {
        this.host = host;
        process = target(loader, "am.ProcessRecord");
        Class<?> record = target(loader, "am.psc.ProcessRecordInternal");
        if (!record.isAssignableFrom(process)) throw new NoSuchMethodException("process state controller");
        appInfo = field(process, "info", ApplicationInfo.class);
        realUid = field(record, "uid", int.class);
        name = field(record, "processName", String.class);
        processLock = field(target(loader, "am.ActivityManagerService"), "mProcLock", target(loader, "am.ActivityManagerGlobalLock"));
        getAdj = method(record, "getCurAdj", int.class);
        setAdj = method(record, "setCurAdj", void.class, int.class);
        getPid = method(process, "getPid", int.class);
        getStartSequence = method(process, "getStartSeq", long.class);
        getThread = method(process, "getThread", type(loader, "android.app.IApplicationThread"));
        kill = method(process, "killLocked", void.class, String.class, String.class, int.class, int.class,
            type(loader, "android.app.ApplicationExitInfo$AnrInfo"), boolean.class, boolean.class);
        CacheStateAccess retention = null;
        try {
            retention = new CacheStateAccess(method(record, "getCurProcState", int.class),
                method(record, "setCurProcState", void.class, int.class));
        } catch (ReflectiveOperationException | RuntimeException failure) { host.unavailable("CACHE_STATE", failure); }
        cacheState = retention;
    }

    static void install(XposedModule module, ClassLoader loader, BackgroundHost host, Consumer<String> log) {
        try {
            BackgroundProcessAccess access = new BackgroundProcessAccess(loader, host);
            Class<?> adjuster = target(loader, "am.psc.OomAdjuster");
            Class<?> record = target(loader, "am.psc.ProcessRecordInternal");
            Method apply = method(adjuster, "applyOomAdjLSP", void.class, record, boolean.class);
            Method canFreeze = method(adjuster, "getFreezePolicy", boolean.class, record);
            var oom = hook(module, apply, chain -> {
                Object app = chain.getArg(0);
                if (host.enabled()) access.apply(app);
                return chain.proceed();
            });
            try {
                hook(module, canFreeze, chain -> {
                    if (host.enabled()) {
                        Decision decision = access.decision(chain.getArg(0));
                        if (decision != null && decision.keepRunnable()) return false;
                    }
                    return chain.proceed();
                });
            } catch (RuntimeException failure) { oom.unhook(); throw failure; }
            log.accept("BACKGROUND_PROCESS_READY");
            try { access.installFreezeDelay(module, loader); log.accept("BACKGROUND_FREEZE_DELAY_READY"); }
            catch (ReflectiveOperationException | RuntimeException failure) { host.unavailable("FREEZE_DELAY", failure); }
            try {
                host.freezerAccess(BackgroundFreezerAccess.install(module, loader, host,
                    access::shouldFreeze, access::blockFreeze));
                try { access.strict = access.new StrictFreezer(module, loader, record); }
                catch (ReflectiveOperationException | RuntimeException failure) { host.unavailable("STRICT_FREEZE", failure); }
            } catch (ReflectiveOperationException | RuntimeException failure) { host.unavailable("FREEZER_METHODS", failure); }
            try {
                hook(module, access.kill, chain -> {
                    if (host.enabled() && (Integer) chain.getArg(2) == ApplicationExitInfo.REASON_OTHER
                            && ("too many cached".equals(chain.getArg(1)) && (Integer) chain.getArg(3) == 2
                                || "too many empty".equals(chain.getArg(1)) && (Integer) chain.getArg(3) == 3)
                            && access.keepForOrdinaryCacheLimit(chain.getThisObject())) return null;
                    return chain.proceed();
                });
                log.accept("BACKGROUND_CACHE_LIMIT_READY");
            } catch (RuntimeException failure) { host.unavailable("CACHE_LIMIT", failure); }
        } catch (ReflectiveOperationException | RuntimeException failure) { host.unavailable("PROCESS", failure); }
    }

    private void installFreezeDelay(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> freezer = target(loader, "am.CachedAppOptimizer");
        Method normalFreeze = method(freezer, "freezeAppAsyncLSP", void.class, process);
        Method earliest = method(freezer, "updateEarliestFreezableTime", long.class, process, long.class);
        ThreadLocal<Object> scheduled = new ThreadLocal<>();
        var request = hook(module, normalFreeze, chain -> {
            if (!host.enabled()) return chain.proceed();
            Object app = chain.getArg(0);
            Decision rule = decision(app);
            if (rule == null || rule.action != Action.FREEZE || rule.runningWork) return chain.proceed();
            Object previous = scheduled.get();
            scheduled.set(app);
            try { return chain.proceed(); }
            finally { if (previous == null) scheduled.remove(); else scheduled.set(previous); }
        });
        try {
            hook(module, earliest, chain -> {
                Object app = chain.getArg(0);
                // Native earliest-freezable time still preserves any existing Binder grace period.
                if (host.enabled() && scheduled.get() == app && (Long) chain.getArg(1) != FREEZE_DELAY_MS) {
                    return chain.proceed(new Object[]{app, FREEZE_DELAY_MS});
                }
                return chain.proceed();
            });
        } catch (RuntimeException failure) { request.unhook(); throw failure; }
    }

    private Decision decision(Object record) throws ReflectiveOperationException {
        if (!process.isInstance(record)) return null;
        ApplicationInfo app = (ApplicationInfo) appInfo.get(record);
        if (app == null || realUid.getInt(record) != app.uid) return null;
        AppRule rule = host.rule(app);
        if (rule == null) return null;
        boolean work = host.working(app.uid, rule);
        String processName = (String) name.get(record);
        if (processName == null) return null;
        String main = app.processName != null ? app.processName : app.packageName;
        Action action = action(rule, app.packageName, processName, main);
        if (action == null && !work && !host.visibleWindow(app.uid)) return null;
        if (host.foreground(app.uid)) return new Decision(Action.ALLOW, false, true, rule.mode(), app.uid, false, false);
        if (action == null && !work) return null;
        boolean reclaim = action == Action.RECLAIM;
        if (reclaim) action = Action.FREEZE;
        boolean message = rule.mode() == Mode.DEFAULT && action == Action.ALLOW
            && messageProcess(app.packageName, processName, main);
        return new Decision(action, message, work, rule.mode(), app.uid, rule.keepActive() && action == Action.ALLOW,
            reclaim);
    }

    private boolean shouldFreeze(Object record) throws ReflectiveOperationException {
        Decision current = decision(record);
        return current != null && current.action == Action.FREEZE && !current.runningWork;
    }

    private boolean blockFreeze(Object app) throws ReflectiveOperationException {
        StrictFreezer control = strict;
        if (control == null || !host.strictSignalsReady()) return false;
        StrictRequest request = control.requests.get(app);
        Decision current = decision(app);
        if (current != null && current.mode == Mode.STRICT && current.keepRunnable()) return true;
        if (current != null && current.strictFreeze()
                && usesStrictTiming(current.mode, current.action, current.runningWork, (Integer) getAdj.invoke(app))) {
            return !control.matches(app, current, request) || !request.submitted;
        }
        return request != null && request.submitted;
    }

    private static final class StrictRequest {
        final ProcessStart start;
        final long foregroundChange;
        final long deadline;
        boolean submitted;

        StrictRequest(ProcessStart start, long foregroundChange, long deadline) {
            this.start = start;
            this.foregroundChange = foregroundChange;
            this.deadline = deadline;
        }
    }

    private final class StrictFreezer {
        // Native freezability callbacks hold the AM and process locks.
        final Map<Object, StrictRequest> requests = new WeakHashMap<>();
        private final Field optimizerField;
        private final Field optRecord;
        private final Method useFreezer;
        private final Method freeze;
        private final Method thaw;
        private final Method isFrozen;
        private final Method setLastUsedTimeout;

        StrictFreezer(XposedModule module, ClassLoader loader, Class<?> record) throws ReflectiveOperationException {
            Class<?> am = target(loader, "am.ActivityManagerService");
            Class<?> optimizer = target(loader, "am.CachedAppOptimizer");
            Class<?> opt = target(loader, "am.ProcessCachedOptimizerRecord");
            optimizerField = field(am, "mCachedAppOptimizer", optimizer);
            optRecord = field(process, "mOptRecord", opt);
            useFreezer = method(optimizer, "useFreezer", boolean.class);
            freeze = method(optimizer, "freezeAppAsyncLSP", void.class, process, long.class);
            thaw = method(optimizer, "unfreezeAppLSP", void.class, process, int.class);
            isFrozen = method(opt, "isFrozen", boolean.class);
            setLastUsedTimeout = method(opt, "setLastUsedTimeout", void.class, long.class);
            Method callback = method(target(loader, "am.ActivityManagerService$OomAdjusterCallback"),
                "onProcessFreezabilityChanged", void.class, record, boolean.class, int.class, boolean.class, int.class, int.class);
            hook(module, callback, this::onFreezabilityChanged);
            host.freezerEvent("STRICT_READY");
        }

        private Object onFreezabilityChanged(XposedInterface.Chain chain) throws Throwable {
            if (!host.strictSignalsReady() || host.manager() == null) return chain.proceed();
            Object app = chain.getArg(0);
            Decision current = decision(app);
            StrictRequest request = requests.get(app);
            int adj = (Integer) chain.getArg(5);
            if (current == null || !usesStrictTiming(current.mode, current.action, false, adj)
                    || getThread.invoke(app) == null) {
                if (request != null) {
                    requests.remove(app);
                    if (request.submitted && processStart(app).equals(request.start)) {
                        thaw.invoke(optimizerField.get(host.manager()), app, 0);
                    }
                }
                return chain.proceed();
            }
            if (!matches(app, current, request)) {
                ProcessStart start = processStart(app);
                if (start.pid() <= 0 || start.sequence() <= 0) return chain.proceed();
                StrictRequest next = new StrictRequest(start, host.foregroundChange(current.uid),
                    SystemClock.uptimeMillis() + FREEZE_DELAY_MS);
                requests.put(app, next);
                host.worker.postAtTime(() -> onDeadline(app, next), next.deadline);
                request = next;
            }
            if (current.runningWork) request.submitted = false;
            else if (!request.submitted && SystemClock.uptimeMillis() >= request.deadline) submit(app, request);
            if (!request.submitted) {
                return chain.proceed(new Object[]{app, false, chain.getArg(2), false, chain.getArg(4), adj});
            }
            // Keep normal thaw paths for actual launches and Binder work. Only the freezer's OOM
            // callback sees the projected cached adj. Real OOM and LMK priorities stay untouched.
            return chain.proceed(new Object[]{app, true, chain.getArg(2), false, CACHED_MAX_ADJ, CACHED_MAX_ADJ});
        }

        private boolean matches(Object app, Decision current, StrictRequest request) throws ReflectiveOperationException {
            return request != null && request.foregroundChange == host.foregroundChange(current.uid)
                && request.start.equals(processStart(app));
        }

        private void onDeadline(Object app, StrictRequest request) {
            Object manager = host.manager();
            if (manager == null) return;
            try {
                synchronized (manager) {
                    synchronized (processLock.get(manager)) {
                        if (requests.get(app) != request || request.submitted) return;
                        Decision current = decision(app);
                        if (current == null || !matches(app, current, request)
                                || getThread.invoke(app) == null) return;
                        int adj = (Integer) getAdj.invoke(app);
                        if (usesStrictTiming(current.mode, current.action, current.runningWork, adj)) submit(app, request);
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException failure) { host.unavailable("STRICT_FREEZE_REQUEST", failure); }
        }

        private void submit(Object app, StrictRequest request) throws ReflectiveOperationException {
            Object optimizer = optimizerField.get(host.manager());
            if (!(Boolean) useFreezer.invoke(optimizer)) return;
            Object optimization = optRecord.get(app);
            if (!(Boolean) isFrozen.invoke(optimization)) {
                freeze.invoke(optimizer, app, 0L);
                // A zero timeout would make the native Binder retry path treat its first failure as exhausted.
                setLastUsedTimeout.invoke(optimization, FREEZE_DELAY_MS);
            }
            request.submitted = true;
        }
    }

    static boolean usesStrictTiming(Mode mode, Action action, boolean protectedWork, int adj) {
        return mode == Mode.STRICT && action == Action.FREEZE && !protectedWork && adj >= 0 && adj <= CACHED_MAX_ADJ;
    }

    private void apply(Object record) throws ReflectiveOperationException {
        if (process.isInstance(record)) {
            ApplicationInfo info = (ApplicationInfo) appInfo.get(record);
            String processName = (String) name.get(record);
            if (info != null && processName != null && info.uid == realUid.getInt(record) && host.rule(info) != null) {
                host.observeProcess(info.packageName, processName);
            }
        }
        Decision decision = decision(record);
        trackReclaim(record, decision);
        if (decision == null) return;
        int adj = (Integer) getAdj.invoke(record);
        if (adj < 0 || adj > CACHED_MAX_ADJ) return;
        if (decision.keepActive && cacheState != null && getThread.invoke(record) != null) {
            int current = (Integer) cacheState.getState.invoke(record);
            if (current >= LAST_ACTIVITY_STATE && current <= LAST_CACHED_STATE) {
                cacheState.setState.invoke(record, SERVICE_STATE);
            }
            if (adj >= CACHED_MIN_ADJ) setAdj.invoke(record, RETAINED_ADJ);
        }
        if (adj < CACHED_MIN_ADJ) return;
        if (decision.action == Action.TRIM && !decision.runningWork) {
            scheduleTrim(record);
            return;
        }
        if (decision.message) setAdj.invoke(record, RETAINED_ADJ);
    }

    private boolean keepForOrdinaryCacheLimit(Object app) throws ReflectiveOperationException {
        Decision decision = decision(app);
        if (decision == null || !decision.message) return false;
        ProcessStart start = processStart(app);
        if (start.pid() <= 0 || start.sequence() <= 0) return false;
        cacheExemptions.entrySet().removeIf(entry -> !isCurrentMessageProcess(entry.getKey(), entry.getValue()));
        if (cacheExemptions.containsKey(app)) return true;
        if (cacheExemptions.size() >= MAX_ORDINARY_CACHE_EXEMPTIONS) return false;
        cacheExemptions.put(app, start);
        return true;
    }

    private boolean isCurrentMessageProcess(Object app, ProcessStart start) {
        try {
            Decision decision = decision(app);
            return decision != null && decision.message && processStart(app).equals(start)
                && getThread.invoke(app) != null;
        } catch (ReflectiveOperationException failure) { return false; }
    }

    private void scheduleTrim(Object app) throws ReflectiveOperationException {
        ProcessStart start = processStart(app);
        if (start.pid() <= 0 || start.sequence() <= 0 || start.equals(pendingTrim.get(app))) return;
        pendingTrim.put(app, start);
        host.worker.postDelayed(() -> trim(app, start), TRIM_DELAY_MS);
    }

    private ProcessStart processStart(Object app) throws ReflectiveOperationException {
        return new ProcessStart((Integer) getPid.invoke(app), (Long) getStartSequence.invoke(app));
    }

    private void trackReclaim(Object app, Decision decision) throws ReflectiveOperationException {
        if (decision == null || !decision.reclaim || strict == null || host.manager() == null) {
            reclaimCandidates.remove(app);
            return;
        }
        ProcessStart start = processStart(app);
        if (start.pid <= 0 || start.sequence <= 0 || getThread.invoke(app) == null) return;
        reclaimCandidates.put(app, start);
        scheduleMemoryCheck();
    }

    private void scheduleMemoryCheck() {
        if (!memoryCheckScheduled && !reclaimCandidates.isEmpty()) {
            memoryCheckScheduled = host.worker.postDelayed(this::checkMemory, MEMORY_SAMPLE_MS);
        }
    }

    private void checkMemory() {
        Object manager = host.manager();
        if (manager == null) return;
        try {
            Object lock = processLock.get(manager);
            try {
                if (hasReclaimCandidates(manager, lock)) {
                    long available = BackgroundMemoryPressure.availableKiB();
                    if (pressure.shouldTrim(SystemClock.uptimeMillis(), available)) {
                        if (reclaimOne(manager, lock)) pressure.trimmed(available);
                        else pressure.finishRound();
                    }
                } else pressure.finishRound();
            } finally {
                synchronized (lock) {
                    memoryCheckScheduled = false;
                    scheduleMemoryCheck();
                }
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            pressure.finishRound();
            host.unavailable("MEMORY_TRIM", failure);
        }
    }

    private boolean hasReclaimCandidates(Object manager, Object lock) throws ReflectiveOperationException {
        synchronized (manager) {
            synchronized (lock) {
                if (!host.enabled()) reclaimCandidates.clear();
                var iterator = reclaimCandidates.entrySet().iterator();
                while (iterator.hasNext()) {
                    var entry = iterator.next();
                    if (!entry.getValue().equals(processStart(entry.getKey())) || getThread.invoke(entry.getKey()) == null) {
                        iterator.remove();
                    }
                }
                return !reclaimCandidates.isEmpty();
            }
        }
    }

    private boolean reclaimOne(Object manager, Object lock) throws ReflectiveOperationException {
        List<ReclaimTarget> targets = new ArrayList<>();
        reclaimed.entrySet().removeIf(entry -> entry.getValue() != host.reclaimSession(entry.getKey().uid));
        synchronized (manager) {
            synchronized (lock) {
                for (var entry : reclaimCandidates.entrySet()) {
                    ReclaimTarget target = reclaimTarget(entry.getKey(), entry.getValue());
                    if (target != null) targets.add(target);
                }
            }
        }
        for (ReclaimTarget target : targets) {
            // cgroup reads stay outside the AM locks. Recheck the native process and freezer state after I/O.
            if (BackgroundObserver.readFrozen(Path.of("/proc", Integer.toString(target.start.pid))) != 1) continue;
            synchronized (manager) {
                synchronized (lock) {
                    if (!target.equals(reclaimTarget(target.app, target.start))) continue;
                    kill.invoke(target.app, "NativeComplements low memory trim", "frozen background process rule",
                        ApplicationExitInfo.REASON_OTHER, 0, null, false, true);
                    reclaimed.put(target.key, target.session);
                    return true;
                }
            }
        }
        return false;
    }

    private ReclaimTarget reclaimTarget(Object app, ProcessStart start) throws ReflectiveOperationException {
        Decision current = decision(app);
        StrictFreezer freezer = strict;
        if (current == null || !current.reclaim || current.keepRunnable() || freezer == null
                || !start.equals(processStart(app)) || getThread.invoke(app) == null) return null;
        int adj = (Integer) getAdj.invoke(app);
        if (adj < 0 || adj > CACHED_MAX_ADJ || !(Boolean) freezer.isFrozen.invoke(freezer.optRecord.get(app))) return null;
        ApplicationInfo info = (ApplicationInfo) appInfo.get(app);
        ReclaimKey key = new ReclaimKey(current.uid, info.packageName, (String) name.get(app));
        long session = host.reclaimSession(current.uid);
        if (!host.canReclaim(current.uid) || reclaimed.getOrDefault(key, -1L) == session) return null;
        return new ReclaimTarget(app, start, key, session);
    }

    private void trim(Object app, ProcessStart start) {
        Object manager = host.manager();
        if (manager == null) return;
        try {
            synchronized (manager) {
                synchronized (processLock.get(manager)) {
                    if (!pendingTrim.remove(app, start) || !host.enabled()) return;
                    Decision current = decision(app);
                    if (current == null || current.keepRunnable() || current.action != Action.TRIM) return;
                    if (!processStart(app).equals(start) || getThread.invoke(app) == null) return;
                    int adj = (Integer) getAdj.invoke(app);
                    if (adj >= CACHED_MIN_ADJ && adj <= CACHED_MAX_ADJ) {
                        kill.invoke(app, "NativeComplements process trim", "background process rule",
                            ApplicationExitInfo.REASON_OTHER, 0, null, false, true);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException failure) { host.unavailable("TRIM", failure); }
    }
}
