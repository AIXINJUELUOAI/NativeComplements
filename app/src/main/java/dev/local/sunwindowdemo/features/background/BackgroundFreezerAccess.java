package dev.local.sunwindowdemo.features.background;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.AtomicFile;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static dev.local.sunwindowdemo.features.background.BackgroundHost.*;

/** Substitute the process operation inside Android's existing freezer and Binder transaction. */
final class BackgroundFreezerAccess {
    interface Eligibility { boolean shouldFreeze(Object process) throws ReflectiveOperationException; }
    private record Target(Object optimizer, Object process, int pid, long sequence) {}
    record Identity(long start, boolean stopped) {}
    private record Owned(Target target, int uid, long start) {}

    private final BackgroundHost host;
    private final Eligibility eligibility;
    private final Field optimizerOwner;
    private final Field procLock;
    private final Method pid;
    private final Method sequence;
    private final Method processUid;
    private final Method uidForPid;
    private final Method thaw;
    private final Method getFreezer;
    private final Method binder;
    private final boolean binderUid;
    private final ThreadLocal<Target> target = new ThreadLocal<>();
    private final ConcurrentHashMap<Integer, Owned> owned = new ConcurrentHashMap<>();
    private final Set<String> logged = ConcurrentHashMap.newKeySet();

    private BackgroundFreezerAccess(ClassLoader loader, BackgroundHost host, Eligibility eligibility)
            throws ReflectiveOperationException {
        this.host = host;
        this.eligibility = eligibility;
        Class<?> am = target(loader, "am.ActivityManagerService");
        Class<?> process = target(loader, "am.ProcessRecord");
        Class<?> optimizer = target(loader, "am.CachedAppOptimizer");
        Class<?> freezer = target(loader, "am.Freezer");
        optimizerOwner = field(target(loader, "am.CachedAppOptimizer$FreezeHandler"), "this$0", optimizer);
        procLock = field(am, "mProcLock", target(loader, "am.ActivityManagerGlobalLock"));
        pid = method(process, "getPid", int.class);
        sequence = method(process, "getStartSeq", long.class);
        processUid = method(process, "getApplicationUid", int.class);
        uidForPid = method(android.os.Process.class, "getUidForPid", int.class, int.class);
        thaw = method(optimizer, "unfreezeAppLSP", void.class, process, int.class);
        getFreezer = method(am, "getFreezer", freezer);
        // LineageOS 24.0 keeps the AOSP three-argument Binder helper; some downstream
        // builds add the owner UID before the freeze flag.
        Method candidate;
        boolean uidArgument;
        try {
            candidate = method(freezer, "freezeBinder", int.class, int.class, boolean.class, int.class);
            uidArgument = true;
        } catch (NoSuchMethodException lineage) {
            candidate = method(freezer, "freezeBinder", int.class, boolean.class, int.class);
            uidArgument = false;
        }
        binder = candidate;
        binderUid = uidArgument;
    }

    static BackgroundFreezerAccess install(XposedModule module, ClassLoader loader, BackgroundHost host,
                                           Eligibility eligibility, Eligibility blockFreeze) throws ReflectiveOperationException {
        BackgroundFreezerAccess access = new BackgroundFreezerAccess(loader, host, eligibility);
        Class<?> process = target(loader, "am.ProcessRecord");
        Method freeze = method(target(loader, "am.CachedAppOptimizer$FreezeHandler"), "freezeProcess", void.class, process);
        Method apply = method(target(loader, "am.Freezer"), "setProcessFrozen", void.class, int.class, int.class, boolean.class);
        Method cleanup = method(target(loader, "am.CachedAppOptimizer"), "onCleanupApplicationRecordLocked", void.class, process);
        var context = hook(module, freeze, chain -> {
            Object app = chain.getArg(0);
            Object manager = host.manager();
            if (manager != null) {
                synchronized (access.procLock.get(manager)) {
                    if (blockFreeze.shouldFreeze(app)) {
                        access.thaw.invoke(access.optimizerOwner.get(chain.getThisObject()), app, 0);
                        return null;
                    }
                }
            }
            if (!host.enabled() || !eligibility.shouldFreeze(app)) return chain.proceed();
            Target previous = access.target.get();
            access.target.set(new Target(access.optimizerOwner.get(chain.getThisObject()), app,
                (Integer) access.pid.invoke(app), (Long) access.sequence.invoke(app)));
            try { return chain.proceed(); }
            finally { if (previous == null) access.target.remove(); else access.target.set(previous); }
        });
        XposedInterface.HookHandle applyHandle = null;
        try {
            // A thaw failure must reach Android's failure handling, which keeps the frozen record for recovery.
            applyHandle = module.hook(apply).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(access::apply);
            hook(module, cleanup, chain -> {
                Object result = chain.proceed();
                Object app = chain.getArg(0);
                for (Owned previous : access.owned.values()) {
                    if (previous.target.process == app) host.worker.postDelayed(() -> access.retire(previous), 500);
                }
                return result;
            });
        } catch (RuntimeException failure) {
            context.unhook();
            if (applyHandle != null) applyHandle.unhook();
            throw failure;
        }
        host.freezerEvent("V2_READY");
        return access;
    }

    private Object apply(XposedInterface.Chain chain) throws Throwable {
        int pid = (Integer) chain.getArg(0), uid = (Integer) chain.getArg(1);
        if (!(Boolean) chain.getArg(2)) return thawOwned(chain, pid);
        Target app = target.get();
        if (app == null || app.pid != pid || app.sequence <= 0 || !host.enabled()) return chain.proceed();
        try {
            if ((Long) sequence.invoke(app.process) != app.sequence || !eligibility.shouldFreeze(app.process)
                    || (Integer) processUid.invoke(app.process) != uid) return chain.proceed();
            Identity identity = identity(pid, uid);
            setV2(pid, uid, true);
            owned.put(pid, new Owned(app, uid, identity.start));
            logOnce("V2_APPLIED");
            return null;
        } catch (IOException | ErrnoException | ReflectiveOperationException | RuntimeException failure) {
            logOnce("V2_FALLBACK_API_" + failure.getClass().getSimpleName());
            return chain.proceed();
        }
    }

    private Object thawOwned(XposedInterface.Chain chain, int pid) throws Throwable {
        Owned previous = owned.get(pid);
        if (previous == null) return chain.proceed();
        if (!sameProcess(previous)) {
            forget(previous);
            return chain.proceed();
        }
        try { setV2(pid, previous.uid, false); }
        catch (IOException | ErrnoException failure) {
            logOnce("V2_THAW_FALLBACK_API");
            chain.proceed();
        }
        forget(previous);
        logOnce("V2_THAWED");
        return null;
    }

    private boolean sameProcess(Owned previous) throws IOException, ReflectiveOperationException {
        try { return identity(previous.target.pid, previous.uid).start == previous.start; }
        catch (java.nio.file.NoSuchFileException gone) { return false; }
    }

    private void forget(Owned previous) {
        owned.remove(previous.target.pid, previous);
    }

    private void retire(Owned previous) {
        try { if (!sameProcess(previous)) forget(previous); }
        catch (IOException | ReflectiveOperationException failure) { logOnce("CLEANUP_" + failure.getClass().getSimpleName()); }
    }

    /** Revert existing V2 operations that no longer match the rules. */
    void reconcile() {
        Object manager = host.manager();
        if (manager == null || owned.isEmpty()) return;
        try {
            synchronized (manager) {
                synchronized (procLock.get(manager)) {
                    for (Owned previous : owned.values()) {
                        Target original = previous.target;
                        if (!sameProcess(previous)) { forget(previous); continue; }
                        if ((Integer) pid.invoke(original.process) != original.pid
                                || (Long) sequence.invoke(original.process) != original.sequence) continue;
                        if (!eligibility.shouldFreeze(original.process)) {
                            thaw.invoke(original.optimizer, original.process, 0);
                        }
                    }
                }
            }
        } catch (IOException | ReflectiveOperationException | RuntimeException failure) {
            logOnce("RECONCILE_" + failure.getClass().getSimpleName());
        }
    }

    /** Older preview builds could leave stopped processes on a same-boot host restart. */
    void recoverLegacySignals(File root) {
        try {
            File directory = new File(root, "background-signals");
            if (!directory.isDirectory()) return;
            String boot = Files.readString(Path.of("/proc/sys/kernel/random/boot_id")).trim();
            if (boot.isEmpty()) throw new IOException("missing boot identity");
            File[] files = directory.listFiles((dir, name) -> name.matches("[1-9][0-9]{0,8}-[0-9]+"));
            if (files == null) throw new IOException("signal journal entries");
            Object freezer = files.length == 0 ? null : getFreezer.invoke(host.manager());
            boolean pending = false;
            for (File file : files) {
                try { recoverMarker(file, freezer, boot); }
                catch (IOException | ErrnoException | ReflectiveOperationException | RuntimeException failure) {
                    pending = true;
                    logOnce("KILL19_RECOVERY_" + failure.getClass().getSimpleName());
                }
            }
            if (pending) host.worker.postDelayed(() -> recoverLegacySignals(root), 5000);
        } catch (IOException | ReflectiveOperationException | RuntimeException failure) {
            logOnce("KILL19_JOURNAL_" + failure.getClass().getSimpleName());
        }
    }

    private void recoverMarker(File file, Object freezer, String boot) throws IOException, ErrnoException, ReflectiveOperationException {
        AtomicFile marker = new AtomicFile(file);
        String[] data = new String(marker.readFully(), StandardCharsets.UTF_8).split(" ");
        int pid = Integer.parseInt(file.getName().split("-", 2)[0]);
        if (data.length == 4 && boot.equals(data[0]) && pid == Integer.parseInt(data[1])) {
            int uid = Integer.parseInt(data[2]);
            try {
                Identity current = identity(pid, uid);
                if (current.start == Long.parseLong(data[3]) && current.stopped) {
                    int frozen = binderUid ? (Integer) binder.invoke(freezer, pid, uid, false, 0)
                        : (Integer) binder.invoke(freezer, pid, false, 0);
                    if (frozen != 0) throw new IOException("Binder recovery failed");
                    Os.kill(pid, OsConstants.SIGCONT);
                    host.freezerEvent("KILL19_RECOVERED");
                }
            } catch (java.nio.file.NoSuchFileException gone) { /* Process has already exited. */ }
            catch (ErrnoException failure) { if (failure.errno != OsConstants.ESRCH) throw failure; }
        }
        marker.delete();
    }

    private Identity identity(int pid, int uid) throws IOException, ReflectiveOperationException {
        if (pid <= 1 || uid < 10000 || uid >= 100000) throw new IOException("unmanaged process");
        String stat = Files.readString(Path.of("/proc", Integer.toString(pid), "stat"));
        if ((Integer) uidForPid.invoke(null, pid) != uid) throw new java.nio.file.NoSuchFileException("changed UID");
        return parseStat(stat);
    }

    static Identity parseStat(String stat) throws IOException {
        int end = stat.lastIndexOf(')');
        if (end < 0 || end + 2 >= stat.length()) throw new IOException("invalid process stat");
        String[] fields = stat.substring(end + 2).split(" ");
        if (fields.length < 20) throw new IOException("missing process start time");
        try {
            return new Identity(Long.parseLong(fields[19]), "T".equals(fields[0]) || "t".equals(fields[0]));
        } catch (NumberFormatException invalid) { throw new IOException("invalid process start time", invalid); }
    }

    private static void setV2(int pid, int uid, boolean freeze) throws IOException, ErrnoException {
        String group = "/uid_" + uid + "/pid_" + pid;
        Path proc = Path.of("/proc", Integer.toString(pid), "cgroup");
        var membership = Files.readAllLines(proc);
        if (membership.contains("0::/apps" + group)) group = "/apps" + group;
        else if (!membership.contains("0::" + group)) throw new IOException("per-process cgroup missing");
        Path directory = Path.of("/sys/fs/cgroup" + group);
        if (!Files.readString(directory.resolve("cgroup.procs")).trim().equals(Integer.toString(pid))) {
            throw new IOException("cgroup membership is not exclusive");
        }
        FileDescriptor descriptor = Os.open(directory.resolve("cgroup.freeze").toString(),
            OsConstants.O_WRONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
        try {
            byte[] payload = {(byte) (freeze ? '1' : '0')};
            if (Os.write(descriptor, payload, 0, payload.length) != payload.length) throw new IOException("cgroup write incomplete");
        } finally { Os.close(descriptor); }
    }

    private void logOnce(String key) { if (logged.add(key)) host.freezerEvent(key); }
}
