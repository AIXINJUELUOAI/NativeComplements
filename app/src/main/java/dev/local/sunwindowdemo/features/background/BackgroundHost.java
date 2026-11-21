package dev.local.sunwindowdemo.features.background;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import dev.local.sunwindowdemo.core.HostSnapshot;
import dev.local.sunwindowdemo.core.ModuleIdentity;
import dev.local.sunwindowdemo.features.background.BackgroundStore.WakeLockRule;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static dev.local.sunwindowdemo.features.background.BackgroundPolicy.*;

/** Owns immutable host snapshots. Package, audio and configuration queries stay off AM/WM locks. */
public final class BackgroundHost {
    private record Snapshot(Settings settings, Map<Integer, String> apps,
                            Map<Integer, WakeLockRule> wakeLocks) {}
    private static final Settings DISABLED = new Settings(false, Map.of());
    private static final Snapshot EMPTY = new Snapshot(DISABLED, Map.of(), Map.of());
    private static volatile BackgroundHost live;
    private volatile Snapshot state = EMPTY;
    private volatile Set<Integer> visibleWindows = Set.of();
    private volatile Set<Integer> playback = Set.of();
    private volatile Set<Integer> gps = Set.of();
    private final Map<Integer, Integer> uidStates = new ConcurrentHashMap<>();
    private final Map<Object, Integer> nativeVisible = new ConcurrentHashMap<>();
    private final Map<Integer, Long> foregroundChanges = new ConcurrentHashMap<>();
    private final Map<Integer, Long> reclaimSessions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> reportedProcesses = new ConcurrentHashMap<>();
    private final Consumer<String> log;
    final Handler worker;
    private volatile Object manager;
    private Method recompute;
    private Context context;
    private HostSnapshot config;
    private BackgroundFreezerAccess freezerAccess;
    private volatile BackgroundWakeLockAccess wakeLockAccess;
    private volatile BackgroundPlaybackAccess playbackAccess;
    private volatile boolean uidStateReady;
    private volatile boolean visibilityReady;
    private volatile boolean playbackReady;
    private volatile boolean gpsReady;
    private final Runnable recalculate = this::recalculate;
    private final Runnable reload = this::reload;

    private BackgroundHost(Consumer<String> log) {
        this.log = log;
        HandlerThread thread = new HandlerThread("NativeComplements-Background", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    public static void install(XposedModule module, ClassLoader loader, Consumer<String> log) {
        BackgroundHost host = new BackgroundHost(log);
        live = host;
        host.installUidState(module, loader);
        host.installVisibleActivities(module, loader);
        try { host.wakeLockAccess = BackgroundWakeLockAccess.install(module, loader, host); }
        catch (ReflectiveOperationException | RuntimeException failure) { host.unavailable("WAKE_LOCK", failure); }
        host.installConnection(module, loader);
        BackgroundProcessAccess.install(module, loader, host, log);
        BackgroundGpsAccess.install(module, loader, host, log);
    }

    public static void visibleWindows(int active, int pinned) {
        BackgroundHost host = live;
        if (host == null) return;
        Set<Integer> previous = host.visibleWindows;
        int count = (active > 0 ? 1 : 0) + (pinned > 0 && active != pinned ? 1 : 0);
        if (previous.size() == count && (active <= 0 || previous.contains(active)) && (pinned <= 0 || previous.contains(pinned))) return;
        host.visibleWindows = count == 0 ? Set.of() : count == 1 ? Set.of(Math.max(active, pinned)) : Set.of(active, pinned);
        for (int uid : previous) if (!host.visibleWindows.contains(uid)) host.recordForegroundChange(uid);
        for (int uid : host.visibleWindows) if (!previous.contains(uid)) host.recordForegroundChange(uid);
        if (host.enabled()) host.recomputeLater();
    }

    private void installConnection(XposedModule module, ClassLoader loader) {
        try {
            Class<?> am = target(loader, "am.ActivityManagerService");
            Method ready = method(am, "systemReady", void.class, Runnable.class, type(loader, "com.android.server.utils.TimingsTraceAndSlog"));
            Field ctx = field(am, "mContext", Context.class);
            recompute = method(am, "updateOomAdjLocked", void.class, int.class);
            hook(module, ready, chain -> {
                Object service = chain.getThisObject();
                Context system = (Context) ctx.get(service);
                worker.post(() -> connect(service, system));
                return chain.proceed();
            });
        } catch (ReflectiveOperationException | RuntimeException failure) { unavailable("CONFIG", failure); }
    }

    private void installUidState(XposedModule module, ClassLoader loader) {
        try {
            Class<?> mirror = target(loader, "wm.MirrorActiveUids");
            Method active = method(mirror, "onUidActive", void.class, int.class, int.class);
            Method change = method(mirror, "onUidProcStateChanged", void.class, int.class, int.class);
            Method inactive = method(mirror, "onUidInactive", void.class, int.class);
            XposedInterface.Hooker observe = chain -> {
                Object result = chain.proceed();
                int uid = (Integer) chain.getArg(0);
                if (uid >= 10000 && uid < 100000) {
                    int current = (Integer) chain.getArg(1);
                    Integer previous = uidStates.put(uid, current);
                    if ((previous != null && previous <= 2) != (current <= 2) && recordForegroundChange(uid)) {
                        recomputeLater();
                    }
                    if (previous == null) refreshWakeLocks(uid);
                }
                return result;
            };
            hook(module, active, observe);
            hook(module, change, observe);
            hook(module, inactive, chain -> {
                Object result = chain.proceed();
                int uid = (Integer) chain.getArg(0);
                Integer previous = uidStates.remove(uid);
                if (previous != null && previous <= 2 && recordForegroundChange(uid)) recomputeLater();
                refreshWakeLocks(uid);
                return result;
            });
            uidStateReady = true;
        } catch (ReflectiveOperationException | RuntimeException failure) { unavailable("UID_STATE", failure); }
    }

    private void installVisibleActivities(XposedModule module, ClassLoader loader) {
        try {
            Class<?> tracker = target(loader, "wm.VisibleActivityProcessTracker");
            Class<?> process = target(loader, "wm.WindowProcessController");
            Method visible = method(tracker, "onAnyActivityVisible", void.class, process);
            Method invisible = method(tracker, "onAllActivitiesInvisible", void.class, process);
            Method removed = method(tracker, "removeProcess", target(loader, "wm.VisibleActivityProcessTracker$CpuTimeRecord"), process);
            Field uid = field(process, "mUid", int.class);
            hook(module, visible, chain -> {
                Object result = chain.proceed();
                Object windowProcess = chain.getArg(0);
                int owner = uid.getInt(windowProcess);
                if (owner >= 10000 && owner < 100000 && nativeVisible.put(windowProcess, owner) == null
                        && recordForegroundChange(owner)) recomputeLater();
                return result;
            });
            XposedInterface.Hooker clear = chain -> {
                Object result = chain.proceed();
                Integer owner = nativeVisible.remove(chain.getArg(0));
                if (owner != null && recordForegroundChange(owner)) recomputeLater();
                return result;
            };
            hook(module, invisible, clear);
            hook(module, removed, clear);
            visibilityReady = true;
        } catch (ReflectiveOperationException | RuntimeException failure) { unavailable("VISIBILITY", failure); }
    }

    private void connect(Object service, Context system) {
        if (context != null) return;
        ContentObserver observer = new ContentObserver(worker) {
            @Override public void onChange(boolean selfChange) { reload(); }
        };
        try {
            config = HostSnapshot.open(system, "background_management");
            system.getContentResolver().registerContentObserver(BackgroundStore.URI, false, observer);
            IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            filter.addDataScheme("package");
            system.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) { reload(); }
            }, filter, null, worker, Context.RECEIVER_NOT_EXPORTED);
        } catch (RuntimeException failure) {
            try { system.getContentResolver().unregisterContentObserver(observer); } catch (RuntimeException ignored) {}
            unavailable("CONNECT", failure);
            worker.postDelayed(() -> connect(service, system), 5000);
            return;
        }
        manager = service;
        context = system;
        if (freezerAccess != null) freezerAccess.recoverLegacySignals(HostSnapshot.directory(system));
        BackgroundCpuQuotaRecovery.restore(this, system, HostSnapshot.directory(system));
        if (wakeLockAccess != null) wakeLockAccess.connect(system);
        try {
            playbackAccess = new BackgroundPlaybackAccess(this);
            playbackAccess.connect(system);
        } catch (ReflectiveOperationException | RuntimeException failure) { unavailable("PLAYBACK", failure); }
        reload();
    }

    private void reload() {
        worker.removeCallbacks(reload);
        try {
            HostSnapshot.Result result = config.read(() -> {
                Bundle extras = new Bundle();
                return context.getContentResolver().call(BackgroundStore.URI, "read", null, extras);
            });
            if (result.report()) log.accept("BACKGROUND_CONFIG " + result.summary());
            if (!result.live() && !moduleInstalled()) {
                publish(null);
                return;
            }
            publish(result.config());
            if (!result.live()) worker.postDelayed(reload, config.retryDelay(3000));
        } catch (RuntimeException failure) {
            state = EMPTY;
            refreshWakeLocks();
            foregroundChanges.clear();
            refreshPlayback();
            if (freezerAccess != null) freezerAccess.reconcile();
            recomputeLater();
            unavailable("CONFIG_READ", failure);
            worker.postDelayed(reload, 10000);
        }
    }

    private boolean moduleInstalled() {
        try {
            context.getPackageManager().getApplicationInfo(ModuleIdentity.CURRENT.packageName(), 0);
            return true;
        } catch (PackageManager.NameNotFoundException removed) { return false; }
    }

    private void publish(Bundle configuration) {
        Settings settings = BackgroundStore.settings(configuration);
        var wakeLockSettings = BackgroundStore.wakeLockSettings(configuration);
        Map<Integer, String> apps = new HashMap<>();
        Set<Integer> shared = new HashSet<>();
        if (settings.enabled() || wakeLockSettings.enabled()) {
            int flags = PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
            Set<Integer> seen = new HashSet<>();
            for (ApplicationInfo app : context.getPackageManager().getInstalledApplications(flags)) {
                if (!seen.add(app.uid)) shared.add(app.uid);
                if (!BackgroundStore.eligible(app)) continue;
                apps.put(app.uid, app.packageName);
            }
            shared.forEach(apps::remove);
        }
        boolean wasEnabled = enabled();
        Map<Integer, WakeLockRule> wakeLocks = new HashMap<>();
        if (wakeLockSettings.enabled()) {
            apps.forEach((uid, pkg) -> {
                WakeLockRule rule = wakeLockSettings.rule(pkg);
                if (rule != null) wakeLocks.put(uid, rule);
            });
        }
        state = new Snapshot(settings, Map.copyOf(apps), Map.copyOf(wakeLocks));
        refreshWakeLocks();
        foregroundChanges.keySet().retainAll(apps.keySet());
        refreshPlayback();
        reportedProcesses.keySet().retainAll(Set.copyOf(apps.values()));
        if (freezerAccess != null && wasEnabled) freezerAccess.reconcile();
        if (wasEnabled || settings.enabled()) recomputeLater();
    }

    void playback(Set<Integer> uids) {
        if (playback.equals(uids)) return;
        playback = uids;
        refreshWakeLocks();
        if (enabled()) recomputeLater();
    }

    void playbackReady(boolean ready) { playbackReady = ready; refreshWakeLocks(); }

    void gpsReady() { gpsReady = true; refreshWakeLocks(); }

    void gps(Set<Integer> uids) {
        if (gps.equals(uids)) return;
        gps = Set.copyOf(uids);
        refreshWakeLocks();
        if (enabled()) recomputeLater();
    }

    boolean canReviewPlayback(int uid) {
        Snapshot current = state;
        String pkg = current.apps.get(uid);
        if (pkg == null || !strictSignalsReady() || foreground(uid)) return false;
        if (current.wakeLocks.containsKey(uid)) return true;
        if (!current.settings.enabled()) return false;
        AppRule rule = current.settings.rule(pkg);
        return rule.actions().containsValue(Action.RECLAIM) || (rule.playbackProtected()
            && (rule.batch() == Action.FREEZE || rule.actions().containsValue(Action.FREEZE)
                || (rule.batch() == null && rule.mode() != Mode.SYSTEM)));
    }

    private boolean recordForegroundChange(int uid) {
        reclaimSessions.computeIfPresent(uid, (key, session) -> session + 1);
        if (!state.apps.containsKey(uid)) return false;
        foregroundChanges.put(uid, SystemClock.uptimeMillis());
        refreshWakeLocks(uid);
        refreshPlayback();
        return true;
    }

    private void refreshPlayback() {
        BackgroundPlaybackAccess access = playbackAccess;
        if (access != null) access.refreshLater();
    }

    long foregroundChange(int uid) { return foregroundChanges.getOrDefault(uid, 0L); }

    long reclaimSession(int uid) { return reclaimSessions.computeIfAbsent(uid, key -> 0L); }

    boolean strictSignalsReady() { return uidStateReady && visibilityReady; }

    boolean foreground(int uid) {
        return visibleWindows.contains(uid) || nativeVisible.containsValue(uid)
            || uidStates.getOrDefault(uid, Integer.MAX_VALUE) <= 2;
    }

    boolean visibleWindow(int uid) { return visibleWindows.contains(uid); }

    boolean working(int uid, AppRule rule) {
        return gps.contains(uid) || (rule.playbackProtected() && playback.contains(uid));
    }

    boolean canReclaim(int uid) {
        return strictSignalsReady() && playbackReady && gpsReady && !foreground(uid)
            && !gps.contains(uid) && !playback.contains(uid);
    }

    boolean enabled() { return state.settings.enabled(); }

    boolean wakeLocksConfigured() { return !state.wakeLocks.isEmpty(); }

    Set<Integer> activeUids() { return uidStates.keySet(); }

    WakeLockRule wakeLockRule(int uid, String pkg) {
        Snapshot current = state;
        WakeLockRule rule = current.wakeLocks.get(uid);
        return rule != null && (pkg == null || pkg.equals(current.apps.get(uid)))
            && uidStates.containsKey(uid) && canReclaim(uid) ? rule : null;
    }

    private void refreshWakeLocks(int uid) {
        if (state.wakeLocks.containsKey(uid)) refreshWakeLocks();
    }

    private void refreshWakeLocks() {
        BackgroundWakeLockAccess access = wakeLockAccess;
        if (access != null) access.requestRefresh();
    }

    void freezerAccess(BackgroundFreezerAccess access) { freezerAccess = access; }

    Object manager() { return manager; }

    AppRule rule(ApplicationInfo info) {
        Snapshot current = state;
        return managed(current, info) ? current.settings.rule(info.packageName) : null;
    }

    void observeProcess(String pkg, String process) {
        Set<String> names = reportedProcesses.computeIfAbsent(pkg, key -> ConcurrentHashMap.newKeySet());
        if (names.size() >= BackgroundStore.MAX_OBSERVED_PROCESSES || !names.add(process)) return;
        worker.post(() -> {
            Bundle report = new Bundle();
            report.putString("name", process);
            try { context.getContentResolver().call(BackgroundStore.URI, "process", pkg, report); }
            catch (RuntimeException failure) { names.remove(process); }
        });
    }

    private static boolean managed(Snapshot snapshot, ApplicationInfo info) {
        return snapshot.settings.enabled() && BackgroundStore.eligible(info) && info.packageName.equals(snapshot.apps.get(info.uid));
    }

    private void recomputeLater() {
        worker.removeCallbacks(recalculate);
        worker.postDelayed(recalculate, 350);
    }

    private void recalculate() {
        if (manager == null) return;
        try {
            synchronized (manager) { recompute.invoke(manager, 0); }
        } catch (ReflectiveOperationException | RuntimeException failure) { unavailable("RECALCULATE", failure); }
    }

    void unavailable(String feature, Throwable failure) {
        log.accept("BACKGROUND_" + feature + "_UNAVAILABLE " + failure.getClass().getSimpleName());
    }


    void freezerEvent(String event) { log.accept("BACKGROUND_FREEZER_" + event); }

    static Class<?> target(ClassLoader loader, String name) throws ClassNotFoundException {
        return type(loader, "com.android.server." + name);
    }

    static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    static Method method(Class<?> type, String name, Class<?> result, Class<?>... args) throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name, args);
        if (method.getReturnType() != result) throw new NoSuchMethodException(type.getName() + "." + name);
        method.setAccessible(true);
        return method;
    }

    static Field field(Class<?> owner, String name, Class<?> type) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        if (field.getType() != type) throw new NoSuchFieldException(owner.getName() + "." + name);
        field.setAccessible(true);
        return field;
    }

    static XposedInterface.HookHandle hook(XposedModule module, Method method, XposedInterface.Hooker hooker) {
        return module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(hooker);
    }
}
