package dev.local.sunwindowdemo.features.background;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Tracks one round of optional reclaim alongside the platform's normal memory management. */
final class BackgroundMemoryPressure {
    private static final long START_KIB = 1024L * 1024;
    private static final long TARGET_KIB = 3 * START_KIB;
    private static final long DEBOUNCE_MS = 3000;
    private static final int MAX_TRIMS = 4;
    private static final Path MEMINFO = Path.of("/proc/meminfo");

    private long lowSince = -1;
    private long beforeLastTrim;
    private int trims;
    private boolean paused;

    boolean shouldTrim(long now, long available) {
        if (available >= TARGET_KIB || (paused && available >= START_KIB)) reset();
        if (paused || available < 0) {
            finishRound();
            return false;
        }
        if (trims > 0) {
            if (available <= beforeLastTrim || trims >= MAX_TRIMS) finishRound();
            return !paused;
        }
        if (available >= START_KIB) {
            lowSince = -1;
            return false;
        }
        if (lowSince < 0) lowSince = now;
        return now - lowSince >= DEBOUNCE_MS;
    }

    void trimmed(long available) {
        beforeLastTrim = available;
        trims++;
    }

    void finishRound() {
        if (trims > 0) paused = true;
        else lowSince = -1;
    }

    void reset() {
        lowSince = -1;
        trims = 0;
        paused = false;
    }

    static long availableKiB() {
        try (var reader = Files.newBufferedReader(MEMINFO)) {
            for (String line; (line = reader.readLine()) != null;) {
                if (!line.startsWith("MemAvailable:")) continue;
                String[] fields = line.substring("MemAvailable:".length()).trim().split("\\s+");
                return fields.length == 2 && "kB".equals(fields[1]) ? Long.parseLong(fields[0]) : -1;
            }
        } catch (IOException | NumberFormatException | SecurityException unavailable) { return -1; }
        return -1;
    }
}
