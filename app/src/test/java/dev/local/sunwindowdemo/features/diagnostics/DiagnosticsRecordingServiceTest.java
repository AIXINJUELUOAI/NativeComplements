package dev.local.sunwindowdemo.features.diagnostics;

import android.content.Context;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class DiagnosticsRecordingServiceTest {
    private Context context;
    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        DiagnosticsStore.connect(context);
        await(() -> DiagnosticsStore.state().getBoolean("loaded"));
        DiagnosticsStore.clear();
    }
    @After public void cleanup() {
        DiagnosticsStore.finished("user_stop");
        DiagnosticsStore.clear();
    }
    private interface Condition { boolean get(); }
    private static void await(Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.get() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue("asynchronous state completed", condition.get());
    }
    private static void field(Class<?> owner, Object instance, String name, Object value) throws Exception {
        Field target = owner.getDeclaredField(name); target.setAccessible(true); target.set(instance, value);
    }
    @Test public void completedCaptureKeepsStartFencedUntilServiceDestroyed() throws Exception {
        ServiceController<DiagnosticsRecordingService> controller = Robolectric.buildService(DiagnosticsRecordingService.class).create();
        DiagnosticsRecordingService service = controller.get();
        // Emulate the worker's completed result, never run onStartCommand or invoke host su.
        field(DiagnosticsRecordingService.class, service, "started", true);
        field(DiagnosticsRecordingService.class, service, "terminalReason", "user_stop");
        DiagnosticsStore.start(context); DiagnosticsStore.prepare();
        DiagnosticsStore.stop("user_stop");
        DiagnosticsStore.start(context);
        assertTrue(DiagnosticsStore.state().getBoolean("stopping"));
        assertFalse(DiagnosticsStore.state().getBoolean("starting"));
        controller.destroy();
        await(() -> !DiagnosticsStore.state().getBoolean("stopping"));
        assertEquals("stopped", DiagnosticsStore.state().getString("status"));
        DiagnosticsStore.start(context);
        assertTrue(DiagnosticsStore.state().getBoolean("starting"));
    }
    @Test public void interruptedProcessIsNotRestoredAsSuccessfulRecording() throws Exception {
        DiagnosticsStore.start(context); DiagnosticsStore.prepare(); DiagnosticsStore.captureReady();
        Files.write(DiagnosticsStore.rawFile().toPath(), new byte[]{1, 2, 3});
        // Simulate loss of in-memory state; the persisted metadata still says recording.
        for (String name : new String[]{"loaded", "starting", "recording", "stopping"})
            field(DiagnosticsStore.class, null, name, false);
        field(DiagnosticsStore.class, null, "capture", null);
        DiagnosticsStore.connect(context);
        await(() -> DiagnosticsStore.state().getBoolean("loaded"));
        assertEquals("interrupted", DiagnosticsStore.state().getString("status"));
        assertEquals("process_interrupted", DiagnosticsStore.state().getString("reason"));
        assertFalse(DiagnosticsStore.state().getBoolean("recording"));
        assertEquals(3L, DiagnosticsStore.state().getLong("bytes"));
    }
    @Test public void foregroundFailureNeverRelabelsPreviousRawAsNewAttempt() throws Exception {
        DiagnosticsStore.start(context); DiagnosticsStore.prepare(); DiagnosticsStore.finished("user_stop");
        Files.write(DiagnosticsStore.rawFile().toPath(), "previous inert session".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ServiceController<DiagnosticsRecordingService> controller = Robolectric.buildService(DiagnosticsRecordingService.class).create();
        field(DiagnosticsRecordingService.class, controller.get(), "started", true);
        field(DiagnosticsRecordingService.class, controller.get(), "terminalReason", "start_failed");
        DiagnosticsStore.start(context);
        controller.destroy();
        await(() -> !DiagnosticsStore.state().getBoolean("starting") && !DiagnosticsStore.state().getBoolean("stopping"));
        assertEquals("start_failed", DiagnosticsStore.state().getString("reason"));
        assertEquals(0L, DiagnosticsStore.state().getLong("bytes"));
        assertFalse(DiagnosticsStore.rawFile().exists());
    }
    @Test public void exportStreamsUnfilteredRawAndSeparateErrorsIntoZip() throws Exception {
        DiagnosticsStore.start(context); DiagnosticsStore.prepare();
        byte[] raw = "inert tag Verbose\\nother tag Error\\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(DiagnosticsStore.rawFile().toPath(), raw);
        Files.write(DiagnosticsStore.errorFile().toPath(), new byte[]{'e'});
        DiagnosticsStore.captureBytes(raw.length); DiagnosticsStore.finished("size_limit");
        android.net.Uri destination = android.net.Uri.parse("content://test/export");
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        org.robolectric.Shadows.shadowOf(context.getContentResolver()).registerOutputStream(destination, output);
        DiagnosticsStore.export(context, destination);
        java.util.Map<String, byte[]> entries = new java.util.HashMap<>();
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(output.toByteArray()))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) entries.put(entry.getName(), zip.readAllBytes());
        }
        assertArrayEquals(raw, entries.get("system-logcat.txt"));
        assertArrayEquals(new byte[]{'e'}, entries.get("capture-errors.txt"));
        assertTrue(new String(entries.get("scope-and-state.txt"), java.nio.charset.StandardCharsets.UTF_8).contains("reason=size_limit"));
        assertTrue(entries.containsKey("module-events.tsv"));
    }
    @Test public void expiredDeletionTouchesOnlyFixedOwnedTargets() throws Exception {
        DiagnosticsStore.start(context); DiagnosticsStore.prepare(); DiagnosticsStore.finished("user_stop");
        Files.write(DiagnosticsStore.rawFile().toPath(), new byte[]{1, 2, 3});
        java.io.File unrelated = new java.io.File(context.getFilesDir(), "diagnostics-unrelated.txt");
        Files.write(unrelated.toPath(), new byte[]{4});
        field(DiagnosticsStore.class, null, "stoppedWall", System.currentTimeMillis() - DiagnosticsBuffer.RETENTION_MS - 1000);
        assertThrows(java.io.IOException.class, () -> DiagnosticsStore.export(context, android.net.Uri.parse("content://test/export")));
        assertEquals("expired", DiagnosticsStore.state().getString("status"));
        assertFalse(DiagnosticsStore.rawFile().exists());
        assertTrue(unrelated.exists());
        assertTrue(unrelated.delete());
    }
}
