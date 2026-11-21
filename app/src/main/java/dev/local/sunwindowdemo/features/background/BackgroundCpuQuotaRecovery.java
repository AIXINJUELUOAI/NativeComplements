package dev.local.sunwindowdemo.features.background;

import android.content.Context;
import android.provider.Settings;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.AtomicFile;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Releases quotas left by older builds when system_server restarts within the same boot. */
final class BackgroundCpuQuotaRecovery {
    private static final String JOURNAL = "background_cpu_uid_";
    private static final String LEGACY_GROUP = "nc_background_cpu_uid_";
    private static final Path CPU = Path.of("/dev/cpuctl");

    static void restore(BackgroundHost host, Context context, File directory) {
        File[] records = directory.listFiles((dir, name) -> name.matches(JOURNAL + "[1-9][0-9]{4}"));
        if (records != null && records.length > 0) {
            try {
                int boot = Settings.Global.getInt(context.getContentResolver(), "boot_count", -1);
                if (boot >= 0) {
                    for (File record : records) {
                        try { restoreRecord(record, boot); }
                        catch (IOException | ErrnoException | RuntimeException failure) {
                            host.unavailable("CPU_RESTORE", failure);
                        }
                    }
                }
            } catch (RuntimeException failure) { host.unavailable("CPU_RESTORE", failure); }
        }
        for (String parent : List.of("", "background", "foreground", "system-background")) {
            Path group = CPU.resolve(parent);
            if (!Files.isDirectory(group)) continue;
            try (var children = Files.newDirectoryStream(group, LEGACY_GROUP + "*")) {
                for (Path child : children) {
                    String name = child.getFileName().toString();
                    if (!name.matches(LEGACY_GROUP + "[1-9][0-9]{4}")) continue;
                    try { restoreGroup(group, child, Integer.parseInt(name.substring(LEGACY_GROUP.length()))); }
                    catch (IOException | ErrnoException | RuntimeException failure) {
                        host.unavailable("CPU_LEGACY_RESTORE", failure);
                    }
                }
            } catch (IOException | RuntimeException failure) { host.unavailable("CPU_LEGACY_RESTORE", failure); }
        }
    }

    private static void restoreRecord(File file, int boot) throws IOException, ErrnoException {
        var record = new AtomicFile(file);
        String[] value = new String(record.readFully(), StandardCharsets.US_ASCII).split("\n");
        if (value.length != 5 || !Set.of("root", "apps").contains(value[0])
                || !value[3].matches("max [0-9]+") || !value[4].matches("[0-9]+ 100000")) {
            throw new IOException("invalid quota recovery record");
        }
        if (Integer.parseInt(value[2]) == boot) {
            String parent = "apps".equals(value[0]) ? "/sys/fs/cgroup/apps" : "/sys/fs/cgroup";
            Path quota = Path.of(parent, "uid_" + file.getName().substring(JOURNAL.length()), "cpu.max");
            try {
                long inode = Long.parseLong(value[1]);
                if (Os.stat(quota.toString()).st_ino == inode && Files.readString(quota).trim().equals(value[4])) {
                    write(quota, value[3], inode);
                }
            } catch (NoSuchFileException removed) {
                // The app group no longer exists.
            } catch (ErrnoException failure) {
                if (failure.errno != OsConstants.ENOENT) throw failure;
            }
        }
        record.delete();
    }

    private static void restoreGroup(Path parent, Path group, int uid) throws IOException, ErrnoException {
        Path quota = group.resolve("cpu.cfs_quota_us");
        if (!Files.readString(quota).trim().equals("-1")) write(quota, "-1", null);
        String membership = "/" + CPU.relativize(group);
        for (String line : Files.readAllLines(group.resolve("tasks"))) {
            String tid = line.trim();
            if (!tid.matches("[1-9][0-9]*")) continue;
            Path proc = Path.of("/proc", tid);
            try {
                boolean owner = Files.readAllLines(proc.resolve("status")).stream().anyMatch(
                    state -> state.matches("Uid:\\s+" + uid + "\\s+" + uid + "\\s+" + uid + "\\s+" + uid + "\\s*"));
                if (owner && Files.readAllLines(proc.resolve("cgroup")).stream().anyMatch(entry -> {
                    String[] field = entry.split(":", 3);
                    return field.length == 3 && membership.equals(field[2]) && List.of(field[1].split(",")).contains("cpu");
                })) {
                    write(parent.resolve("tasks"), tid, null);
                }
            } catch (NoSuchFileException removed) {
                // Thread exited before it could be moved.
            } catch (ErrnoException failure) {
                if (failure.errno != OsConstants.ENOENT && failure.errno != OsConstants.ESRCH) throw failure;
            }
        }
        try { Files.deleteIfExists(group); }
        catch (java.nio.file.DirectoryNotEmptyException remaining) { /* The group's quota is already removed. */ }
    }

    private static void write(Path path, String value, Long inode) throws IOException, ErrnoException {
        var fd = Os.open(path.toString(), OsConstants.O_WRONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
        try {
            if (inode != null && Os.fstat(fd).st_ino != inode) throw new IOException("quota cgroup changed");
            byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
            if (Os.write(fd, bytes, 0, bytes.length) != bytes.length) throw new IOException("cgroup write incomplete");
        } finally { Os.close(fd); }
    }

    private BackgroundCpuQuotaRecovery() {}
}
