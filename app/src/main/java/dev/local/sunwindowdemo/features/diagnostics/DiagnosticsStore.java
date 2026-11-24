package dev.local.sunwindowdemo.features.diagnostics;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.AtomicFile;
import dev.local.sunwindowdemo.BuildConfig;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** App-only recording management; the exported Provider still accepts only whitelisted events. */
public final class DiagnosticsStore {
    private static final DiagnosticsBuffer BUFFER = new DiagnosticsBuffer();
    private static final TreeSet<String> SEEN = new TreeSet<>();
    private static final Map<String, Boolean> SETTINGS = new LinkedHashMap<>();
    private static final ScheduledExecutorService MAINTENANCE = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "diagnostics-retention"); t.setDaemon(true); return t;
    });
    private static volatile Context application;
    private static boolean loaded, starting, recording, stopping, testEvents, exporting;
    private static String pendingStopReason;
    private static String status = "idle", reason = "";
    private static long bytes, startedWall, startedElapsed, stoppedWall, elapsedMs;
    private static DiagnosticsCapture capture;
    static {
        MAINTENANCE.scheduleWithFixedDelay(() -> {
            synchronized (DiagnosticsStore.class) {
                if (application != null) {
                    try { load(); expire(); } catch (IOException ignored) { }
                }
            }
        }, 1, 60, TimeUnit.SECONDS);
    }
    private DiagnosticsStore() { }
    private static long now() { return SystemClock.elapsedRealtime(); }
    static void connect(Context context) {
        Context app = context.getApplicationContext();
        application = app == null ? context : app;
        MAINTENANCE.execute(() -> {
            synchronized (DiagnosticsStore.class) {
                try { load(); expire(); }
                catch (IOException unavailable) { loaded = true; status = "error"; reason = "storage_failed"; }
            }
            changed();
        });
    }
    private static void changed() {
        Context context = application;
        if (context != null) context.getContentResolver().notifyChange(Diagnostics.URI, null);
    }
    private static File file(String name) { return new File(application.getFilesDir(), "diagnostics-" + name); }
    private static AtomicFile metadata() { return new AtomicFile(file("recording.properties")); }
    private static boolean busy() { return starting || recording || stopping; }
    private static void load() throws IOException {
        if (loaded || application == null) return;
        if (!metadata().getBaseFile().exists() && !file("recording.properties.bak").exists()) { loaded = true; return; }
        Properties values = new Properties();
        try (FileInputStream input = metadata().openRead()) { values.load(input); }
        try {
            startedWall = Long.parseLong(values.getProperty("startedWall", "0"));
            stoppedWall = Long.parseLong(values.getProperty("stoppedWall", "0"));
            elapsedMs = Long.parseLong(values.getProperty("elapsedMs", "0"));
        } catch (NumberFormatException invalid) { startedWall = stoppedWall = elapsedMs = 0; }
        loaded = true;
        bytes = file("logcat.txt").length();
        if (!busy()) {
            status = values.getProperty("status", "interrupted");
            reason = values.getProperty("reason", "process_interrupted");
            if ("starting".equals(status) || "recording".equals(status) || "stopping".equals(status)) {
                status = "interrupted"; reason = "process_interrupted";
                // Conservative retention after death: never grant a fresh 24 hours on every app launch.
                stoppedWall = startedWall;
                save();
            }
        }
    }
    private static void save() throws IOException {
        Properties values = new Properties();
        values.setProperty("startedWall", Long.toString(startedWall));
        values.setProperty("stoppedWall", Long.toString(stoppedWall));
        values.setProperty("elapsedMs", Long.toString(elapsedMs));
        values.setProperty("status", status); values.setProperty("reason", reason);
        AtomicFile target = metadata(); FileOutputStream output = target.startWrite();
        try { values.store(output, "Local manual logcat recording"); target.finishWrite(output); }
        catch (IOException failure) { target.failWrite(output); throw failure; }
    }
    private static void deleteFiles() throws IOException {
        // Fixed app-owned targets only. Never traverse directories or accept a caller-supplied path.
        for (String name : new String[]{"logcat.txt", "errors.txt", "recording.properties",
                "recording.properties.bak", "recording.properties.new"}) {
            File target = file(name);
            if (target.exists() && !target.delete()) throw new IOException("Cannot remove local recording");
        }
    }
    private static void expire() throws IOException {
        if (!busy() && !exporting && stoppedWall > 0 && System.currentTimeMillis() - stoppedWall >= DiagnosticsBuffer.RETENTION_MS) {
            deleteFiles(); BUFFER.clear(); SEEN.clear(); bytes = 0;
            startedWall = stoppedWall = startedElapsed = elapsedMs = 0;
            status = "expired"; reason = "local_expired"; changed();
        }
    }
    static synchronized void seen(String source) { SEEN.add(source); }
    static void add(String source, String event, String exception) { BUFFER.add(now(), source, event, exception); }
    public static synchronized Bundle state() {
        Bundle out = new Bundle();
        out.putBoolean("recording", testEvents ? BUFFER.active(now()) : recording);
        out.putBoolean("loaded", loaded);
        out.putBoolean("starting", starting); out.putBoolean("stopping", stopping);
        out.putLong("bytes", bytes); out.putInt("eventBytes", BUFFER.size(now()));
        out.putString("seen", String.join(", ", SEEN));
        out.putString("status", status); out.putString("reason", reason);
        out.putLong("elapsedMs", busy() && startedElapsed > 0 ? Math.max(0, now() - startedElapsed) : elapsedMs);
        out.putBoolean("canExport", loaded && !busy() && !exporting && startedWall > 0);
        return out;
    }
    /** UI first requests root authorization. No filesystem or process wait on this entry point. */
    public static void start(Context context) {
        connect(context);
        synchronized (DiagnosticsStore.class) {
            if (busy() || exporting) return;
            starting = true; testEvents = false; pendingStopReason = null; status = "starting"; reason = "";
        }
        changed();
        try { context.startForegroundService(new Intent(context, DiagnosticsRecordingService.class)); }
        catch (RuntimeException denied) {
            MAINTENANCE.execute(() -> {
                String failed = "start_failed";
                try { prepare(); } catch (IOException unavailable) { failed = "storage_failed"; }
                finished(failed);
            });
            throw denied;
        }
    }
    /** Test-only legacy whitelisted event buffer. Production UI must use start(Context). */
    static synchronized void start() {
        testEvents = true; SEEN.clear(); BUFFER.start(now());
    }
    /** Called by the foreground Service's worker, never by a Provider method. */
    static synchronized DiagnosticsCapture prepare() throws IOException {
        load();
        if (capture != null) return null;
        deleteFiles(); BUFFER.clear(); SEEN.clear(); bytes = 0;
        startedWall = System.currentTimeMillis(); startedElapsed = now(); stoppedWall = elapsedMs = 0;
        starting = true; recording = false; testEvents = false;
        status = stopping ? "stopping" : "starting"; reason = stopping ? pendingStopReason : "";
        save(); capture = new DiagnosticsCapture();
        if (stopping) capture.stop(pendingStopReason);
        return capture;
    }
    static synchronized long startedWall() { return startedWall; }
    static synchronized File rawFile() { return file("logcat.txt"); }
    static synchronized File errorFile() { return file("errors.txt"); }
    static synchronized void captureReady() {
        if (!stopping) {
            starting = false; recording = true; status = "recording";
            BUFFER.start(now());
            try { save(); } catch (IOException failure) { capture.stop("storage_failed"); }
            changed();
        }
    }
    static synchronized void captureBytes(long count) { bytes = count; }
    static synchronized void finished(String stopReason) {
        starting = recording = stopping = false; capture = null; pendingStopReason = null;
        BUFFER.stop(now()); stoppedWall = System.currentTimeMillis(); elapsedMs = Math.max(0, now() - startedElapsed);
        reason = stopReason;
        status = ("user_stop".equals(reason) || "duration_limit".equals(reason) || "size_limit".equals(reason))
                ? "stopped" : ("service_destroyed".equals(reason) ? "interrupted" : "error");
        try { save(); } catch (IOException failure) { status = "error"; reason = "storage_failed"; }
        changed();
    }
    public static void stop() { stop("user_stop"); }
    static synchronized void stop(String stopReason) {
        if (testEvents) { BUFFER.stop(now()); changed(); return; }
        if (!busy()) return;
        if (pendingStopReason == null) pendingStopReason = stopReason;
        stopping = true; recording = starting = false; status = "stopping";
        BUFFER.stop(now());
        if (capture != null) capture.stop(stopReason);
        changed();
    }
    /** IO only; refuse to race the active capture or an export. */
    public static synchronized void clear() {
        if (busy() || exporting) throw new IllegalStateException("Stop recording/export before deleting");
        try { load(); if (application != null) deleteFiles(); }
        catch (IOException failure) { throw new IllegalStateException("Cannot delete local recording", failure); }
        BUFFER.clear(); SEEN.clear(); testEvents = false; bytes = 0;
        startedWall = startedElapsed = stoppedWall = elapsedMs = 0;
        status = "idle"; reason = ""; changed();
    }
    /** Parent explicitly supplies individual booleans, never a preferences object or arbitrary map. */
    public static synchronized void setting(String name, boolean enabled) {
        if (name == null || !DiagnosticsBuffer.SETTINGS.contains(name)) throw new IllegalArgumentException("setting");
        SETTINGS.put(name, enabled);
    }
    private static String safeSystemValue(String value) {
        String safe = value == null ? "unknown" : value.replaceAll("[^a-zA-Z0-9 ._+-]", "_");
        return safe.substring(0, Math.min(safe.length(), 80));
    }
    /** IO only; only a user-selected SAF URI, no upload. Lock prevents concurrent delete/start. */
    public static void export(Context context, Uri destination) throws IOException {
        if (!"content".equals(destination.getScheme())) throw new IOException("SAF document required");
        final String info, events;
        final File raw, errors;
        synchronized (DiagnosticsStore.class) {
            if (exporting) throw new IOException("Export already in progress");
            info = exportInfo(); events = BUFFER.snapshot(now());
            raw = file("logcat.txt"); errors = file("errors.txt"); exporting = true;
        }
        try {
            OutputStream output = context.getContentResolver().openOutputStream(destination, "wt");
            if (output == null) throw new IOException("Cannot open document");
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                entry(zip, "scope-and-state.txt", info);
                fileEntry(zip, "system-logcat.txt", raw);
                fileEntry(zip, "capture-errors.txt", errors);
                entry(zip, "module-events.tsv", "elapsed_ms\tsource\tevent\texception\n" + events);
            }
        } finally { synchronized (DiagnosticsStore.class) { exporting = false; } }
    }
    private static String exportInfo() throws IOException {
        load(); expire();
        if (busy()) throw new IOException("Stop recording before export");
        if (startedWall <= 0) throw new IOException("No retained recording");
        StringBuilder info = new StringBuilder("Manual root logcat, all buffers readable by this device's logcat. No tag/priority filtering.\n")
                .append("Since the requested start timestamp only; not all history, kernel or LSPosed logs.\n")
                .append("ROM/SELinux/logd access, buffer availability and dropped messages limit actual coverage.\n")
                .append("Raw logs and errors may contain sensitive information. Inspect before sharing. No automatic upload.\n")
                .append("Module event appendix is separately whitelisted and bounded; absent events mean unknown.\n")
                .append("event_time=provider_receive_elapsed_since_capture_ready; batching may delay events\n")
                .append("version=").append(BuildConfig.VERSION_NAME).append('\n')
                .append("versionCode=").append(BuildConfig.VERSION_CODE).append('\n')
                .append("sdk=").append(Build.VERSION.SDK_INT).append('\n')
                .append("model=").append(safeSystemValue(Build.MODEL)).append('\n')
                .append("manufacturer=").append(safeSystemValue(Build.MANUFACTURER)).append('\n')
                .append("recording_attempt_started_wall_ms=").append(startedWall).append('\n')
                .append("recording_stopped_wall_ms=").append(stoppedWall).append('\n')
                .append("elapsed_ms=").append(elapsedMs).append('\n')
                .append("raw_bytes=").append(bytes).append('\n')
                .append("status=").append(status).append('\n').append("reason=").append(reason).append('\n')
                .append("observed_module_processes=").append(String.join(",", SEEN)).append('\n');
        SETTINGS.forEach((key, value) -> info.append(key).append('=').append(value).append('\n'));
        return info.toString();
    }
    private static void fileEntry(ZipOutputStream zip, String name, File source) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        if (source.exists()) {
            try (FileInputStream input = new FileInputStream(source)) {
                byte[] block = new byte[16 * 1024]; int count;
                while ((count = input.read(block)) != -1) zip.write(block, 0, count);
            }
        }
        zip.closeEntry();
    }
    private static void entry(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(value.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
}
