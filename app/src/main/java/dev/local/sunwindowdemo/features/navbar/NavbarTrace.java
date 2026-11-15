package dev.local.sunwindowdemo.features.navbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * system_server navigation diagnostics: logcat plus an on-device ring file (/data/system/nc_navbar.log and
 * .log.1, about 2 x 768 KiB) so a problem seen during normal use can be read later without adb attached.
 * Writes happen on one low-priority thread; a full queue drops lines instead of blocking the caller.
 */
final class NavbarTrace {
    private static final String TAG = "NativeNavbar";
    private static final File FILE = new File("/data/system/nc_navbar.log"), OLD = new File("/data/system/nc_navbar.log.1");
    private static final long LIMIT = 768 * 1024;
    private static volatile boolean ring;
    private static final java.util.concurrent.ThreadPoolExecutor IO = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0, java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(512),
            runnable -> { Thread t = new Thread(runnable, "NcNavbarTrace"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); return t; },
            new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
    private static OutputStream out;
    private static long size;
    private static final java.text.SimpleDateFormat TIME = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT);

    private NavbarTrace() {}

    /** Only the system_server backend may write /data/system. */
    static void enableRing() { ring = true; i("TRACE ring " + FILE); }

    static void i(String message) {
        android.util.Log.i(TAG, message);
        write("I", message);
    }

    static void w(String message) {
        android.util.Log.w(TAG, message);
        write("W", message);
    }

    static void w(String message, Throwable failure) {
        android.util.Log.w(TAG, message, failure);
        write("W", message + " : " + failure);
    }

    private static void write(String level, String message) {
        if (!ring) return;
        long now = System.currentTimeMillis();
        IO.execute(() -> append(now, level, message));
    }

    private static void append(long now, String level, String message) {
        try {
            if (out == null) { out = new FileOutputStream(FILE, true); size = FILE.length(); }
            byte[] line;
            synchronized (TIME) { line = (TIME.format(new java.util.Date(now)) + " " + level + " " + message + "\n").getBytes(StandardCharsets.UTF_8); }
            out.write(line);
            size += line.length;
            if (size > LIMIT) {
                out.close(); out = null;
                if (OLD.exists() && !OLD.delete()) return;
                if (!FILE.renameTo(OLD)) FILE.delete();
            }
        } catch (IOException | RuntimeException failure) {
            ring = false;   // never retry a broken file from the hot path
            android.util.Log.w(TAG, "trace ring disabled", failure);
        }
    }
}
