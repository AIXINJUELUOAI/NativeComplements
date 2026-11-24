package dev.local.sunwindowdemo.features.diagnostics;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** One owned su shell. Raw output is copied in bounded chunks, never accumulated in memory. */
final class DiagnosticsCapture {
    static final long MAX_BYTES = 64L * 1024 * 1024;
    static final long MAX_DURATION_MS = 10 * 60_000L;
    static final int MAX_ERROR_BYTES = 64 * 1024;
    private static final String READY = "NC_LOGCAT_READY";
    private final AtomicReference<String> stopReason = new AtomicReference<>();
    private volatile Process process;
    private volatile boolean ready;
    private volatile long bytes;
    private final Process suppliedProcess;
    private final long maxBytes, durationMs, rootTimeoutMs;

    DiagnosticsCapture() { this(null, MAX_BYTES, MAX_DURATION_MS, 65_000); }
    // Package-private fake-process seam: tests never invoke host su or read device logs.
    DiagnosticsCapture(Process process, long maxBytes, long durationMs, long rootTimeoutMs) {
        this.suppliedProcess = process; this.maxBytes = maxBytes;
        this.durationMs = durationMs; this.rootTimeoutMs = rootTimeoutMs;
    }

    // The only variable is a Java-formatted timestamp, single-quoted after strict validation.
    static String script(String since) {
        if (since == null || !since.matches("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}"))
            throw new IllegalArgumentException("timestamp");
        return "[ \"$(id -u)\" = 0 ] || exit 77\n"
                + "capture=; owner=$$\n"
                + "owned_stop() { [ -n \"$capture\" ] || return; "
                + "[ -r \"/proc/$capture/status\" ] || return; "
                + "while read -r key value rest; do "
                + "if [ \"$key\" = PPid: ] && [ \"$value\" = \"$owner\" ]; then kill \"$capture\" 2>/dev/null; return; fi; "
                + "done <\"/proc/$capture/status\"; }\n"
                + "cleanup() { trap - EXIT HUP INT TERM; "
                + "if [ -n \"$capture\" ]; then owned_stop; wait \"$capture\" 2>/dev/null; fi; }\n"
                + "trap cleanup EXIT\ntrap 'exit 143' HUP INT TERM\n"
                + "printf '" + READY + "\\n'\n"
                + "logcat -b all -v threadtime -v year -v zone -T '" + since + "' '*:V' </dev/null &\n"
                + "capture=$!\n"
                // Non-interactive shells may otherwise replace background stdin with /dev/null.
                + "exec 3<&0\n( IFS= read -r stop <&3; owned_stop ) >/dev/null 2>&1 &\n"
                + "exec 3<&-\nwait \"$capture\"\nresult=$?\ncapture=\nexit \"$result\"\n";
    }

    void stop(String reason) {
        stopReason.compareAndSet(null, reason);
        Process current = process;
        if (current != null) {
            try { current.getOutputStream().close(); } catch (IOException ignored) { }
        }
    }

    private static boolean readReady(InputStream input) throws IOException {
        for (byte value : (READY + "\n").getBytes(StandardCharsets.US_ASCII)) {
            if (input.read() != (value & 255)) return false;
        }
        return true;
    }

    String run(File raw, File errors, long startedWall) {
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "diagnostics-capture-timeout"); t.setDaemon(true); return t;
        });
        Thread errorReader = null;
        try (FileOutputStream output = new FileOutputStream(raw)) {
            try (FileOutputStream empty = new FileOutputStream(errors)) { /* truncate fixed target */ }
            if (stopReason.get() != null) return stopReason.get();
            String since = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date(startedWall));
            process = suppliedProcess != null ? suppliedProcess : new ProcessBuilder("su", "-c", script(since)).start();
            Process owned = process;
            errorReader = new Thread(() -> {
                try (InputStream input = owned.getErrorStream(); FileOutputStream errorOutput = new FileOutputStream(errors)) {
                    byte[] block = new byte[4096]; int count, retained = 0;
                    while ((count = input.read(block)) != -1) {
                        int keep = Math.min(count, MAX_ERROR_BYTES - retained);
                        if (keep > 0) { errorOutput.write(block, 0, keep); retained += keep; }
                    }
                } catch (IOException ignored) { }
            }, "diagnostics-stderr");
            errorReader.setDaemon(true); errorReader.start();
            // Includes a stalled su prompt. Closing stdin handles a shell that appears after cancellation.
            timer.schedule(() -> { if (!ready) stop("root_denied_or_unavailable"); }, rootTimeoutMs, TimeUnit.MILLISECONDS);
            timer.schedule(() -> stop("duration_limit"), durationMs, TimeUnit.MILLISECONDS);
            timer.scheduleWithFixedDelay(() -> {
                if (stopReason.get() != null) {
                    try { owned.getOutputStream().close(); } catch (IOException ignored) { }
                    // EOF is the normal stop. Only the owned su transport is forcibly released if wedged.
                    try { if (!owned.waitFor(2, TimeUnit.SECONDS)) owned.destroy(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    try { if (!owned.waitFor(1, TimeUnit.SECONDS)) owned.destroyForcibly(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    try { owned.getInputStream().close(); } catch (IOException ignored) { }
                }
            }, 1, 1, TimeUnit.SECONDS);
            if (stopReason.get() != null) stop(stopReason.get());
            try (InputStream input = owned.getInputStream()) {
                if (!readReady(input)) return stopReason.get() == null ? "root_denied_or_unavailable" : stopReason.get();
                if (stopReason.get() == null) {
                    ready = true;
                    if (suppliedProcess == null) DiagnosticsStore.captureReady();
                }
                byte[] block = new byte[16 * 1024]; int count;
                while (stopReason.get() == null && (count = input.read(block)) != -1) {
                    // Do not retain bytes read after the user requested stop.
                    if (stopReason.get() != null) break;
                    int keep = (int) Math.min(count, maxBytes - bytes);
                    output.write(block, 0, keep); bytes += keep;
                    if (suppliedProcess == null) DiagnosticsStore.captureBytes(bytes);
                    if (bytes == maxBytes) stop("size_limit");
                }
            }
            if (stopReason.get() == null) stop("capture_failed");
            return stopReason.get();
        } catch (IOException | RuntimeException failure) {
            return stopReason.get() == null ? "capture_failed" : stopReason.get();
        } finally {
            stop(stopReason.get() == null ? "capture_failed" : stopReason.get());
            Process owned = process;
            if (owned != null) {
                try { if (!owned.waitFor(2, TimeUnit.SECONDS)) owned.destroy(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                try { if (!owned.waitFor(1, TimeUnit.SECONDS)) owned.destroyForcibly(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            if (errorReader != null) {
                try { errorReader.join(1000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                if (owned != null) try { owned.getErrorStream().close(); } catch (IOException ignored) { }
                try { errorReader.join(1000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            timer.shutdownNow(); process = null;
        }
    }
}
