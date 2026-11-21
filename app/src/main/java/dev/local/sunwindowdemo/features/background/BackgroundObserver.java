package dev.local.sunwindowdemo.features.background;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
/** Local proc readers used by freezer recovery. */
final class BackgroundObserver {
    private static final Path CGROUP = Path.of("/sys/fs/cgroup");
    record Memory(int uid, long ramKiB, long swapKiB) {}
    static Memory parseStatus(List<String> lines) {
        int uid = -1;
        long ram = -1, swap = -1;
        for (String line : lines) {
            if (line.startsWith("Uid:")) uid = (int) firstNumber(line.substring(4));
            else if (line.startsWith("VmRSS:")) ram = firstNumber(line.substring(6));
            else if (line.startsWith("VmSwap:")) swap = firstNumber(line.substring(7));
        }
        return new Memory(uid, ram, swap);
    }

    private static long firstNumber(String field) {
        try { return Long.parseLong(field.stripLeading().split("\\s+", 2)[0]); }
        catch (NumberFormatException invalid) { return -1; }
    }

    static int readFrozen(Path proc) {
        try {
            for (String group : Files.readAllLines(proc.resolve("cgroup"))) {
                if (!group.startsWith("0::/")) continue;
                Path events = CGROUP.resolve(group.substring(4)).normalize().resolve("cgroup.events");
                if (!events.startsWith(CGROUP)) return -1;
                for (String entry : Files.readAllLines(events)) {
                    if (entry.equals("frozen 1")) return 1;
                    if (entry.equals("frozen 0")) return 0;
                }
            }
        } catch (IOException | IllegalArgumentException | SecurityException unavailable) { return -1; }
        return -1;
    }

}
