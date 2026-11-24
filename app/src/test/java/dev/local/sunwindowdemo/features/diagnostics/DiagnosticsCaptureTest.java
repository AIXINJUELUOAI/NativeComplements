package dev.local.sunwindowdemo.features.diagnostics;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Inert host fixtures only: no su, Android device, logcat or real log data. */
public class DiagnosticsCaptureTest {
    private static final byte[] READY = "NC_LOGCAT_READY\n".getBytes(StandardCharsets.US_ASCII);
    private static final class FakeProcess extends Process {
        final InputStream stdout, stderr;
        final OutputStream feed;
        volatile boolean stopped;
        final OutputStream stdin = new ByteArrayOutputStream() {
            @Override public void close() {
                stopped = true;
                try { if (feed != null) feed.close(); } catch (Exception ignored) { }
            }
        };
        FakeProcess(byte[] output, byte[] errors) {
            stdout = new ByteArrayInputStream(output); stderr = new ByteArrayInputStream(errors); feed = null;
        }
        FakeProcess(boolean ready) throws Exception {
            PipedInputStream input = new PipedInputStream();
            feed = new PipedOutputStream(input); stdout = input; stderr = new ByteArrayInputStream(new byte[0]);
            if (ready) feed.write(READY);
        }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return stderr; }
        @Override public OutputStream getOutputStream() { return stdin; }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return true; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { try { stdin.close(); } catch (Exception ignored) { } }
    }
    private String run(DiagnosticsCapture capture, File directory) {
        return capture.run(new File(directory, "raw"), new File(directory, "errors"), 1_790_000_000_000L);
    }
    @Test public void fixedScriptCannotInjectCommandsAndDoesNotWriteRootFiles() {
        String script = DiagnosticsCapture.script("2026-11-24 15:12:13.456");
        assertTrue(script.startsWith("[ \"$(id -u)\" = 0 ] || exit 77"));
        assertTrue(script.contains("logcat -b all -v threadtime -v year -v zone -T '2026-11-24 15:12:13.456' '*:V'"));
        assertTrue(script.contains("IFS= read -r stop <&3; owned_stop"));
        assertTrue(script.contains("PPid:"));
        assertTrue(script.contains("result=$?\ncapture=\nexit"));
        assertFalse(script.contains("killall")); assertFalse(script.contains("/data/"));
        assertThrows(IllegalArgumentException.class, () -> DiagnosticsCapture.script("2026-11-24'; id; #"));
    }
    @Test public void refusalRecordsNoRawAndStderrIsSeparatelyBounded() throws Exception {
        File directory = Files.createTempDirectory("capture-refusal").toFile();
        byte[] errors = new byte[DiagnosticsCapture.MAX_ERROR_BYTES + 4096];
        FakeProcess process = new FakeProcess("not-root\n".getBytes(StandardCharsets.UTF_8), errors);
        assertEquals("root_denied_or_unavailable", run(new DiagnosticsCapture(process, 64, 10_000, 1000), directory));
        assertEquals(0, new File(directory, "raw").length());
        assertEquals(DiagnosticsCapture.MAX_ERROR_BYTES, new File(directory, "errors").length());
        assertTrue(process.stopped);
    }
    @Test public void rawCapExcludesReadyAndUnexpectedEofIsNotSuccess() throws Exception {
        File directory = Files.createTempDirectory("capture-size").toFile();
        byte[] output = new byte[READY.length + 100];
        System.arraycopy(READY, 0, output, 0, READY.length);
        FakeProcess process = new FakeProcess(output, new byte[0]);
        assertEquals("size_limit", run(new DiagnosticsCapture(process, 32, 10_000, 1000), directory));
        assertEquals(32, new File(directory, "raw").length());
        process = new FakeProcess(READY, new byte[0]);
        assertEquals("capture_failed", run(new DiagnosticsCapture(process, 32, 10_000, 1000), directory));
        assertEquals(0, new File(directory, "raw").length());
    }
    @Test public void quietStreamsCanStopAndStalledRootTimesOut() throws Exception {
        File directory = Files.createTempDirectory("capture-quiet").toFile();
        FakeProcess process = new FakeProcess(true);
        DiagnosticsCapture capture = new DiagnosticsCapture(process, 64, 10_000, 1000);
        CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> run(capture, directory));
        capture.stop("user_stop");
        assertEquals("user_stop", result.get(4, TimeUnit.SECONDS));
        process = new FakeProcess(false);
        assertEquals("root_denied_or_unavailable", run(new DiagnosticsCapture(process, 64, 10_000, 20), directory));
        assertTrue(process.stopped);
    }
    @Test public void quietRecordingHasDurationLimit() throws Exception {
        File directory = Files.createTempDirectory("capture-duration").toFile();
        FakeProcess process = new FakeProcess(true);
        assertEquals("duration_limit", run(new DiagnosticsCapture(process, 64, 20, 1000), directory));
        assertTrue(process.stopped);
    }
}
