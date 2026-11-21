package dev.local.sunwindowdemo.features.background;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/** Uses playback callbacks for protection and briefly verifies stale background players off the host worker. */
final class BackgroundPlaybackAccess {
    private static final long FIRST_SAMPLE_MS = 750;
    private static final long CONFIRM_MS = 2000;
    private static final long RECHECK_MS = 20000;
    private static final int MAX_DUMP_BYTES = 256 * 1024;
    private static final int DUMP_TIMEOUT_MS = 900;
    private static final Pattern TRACK_ROW = Pattern.compile(
        "^\\s*(?:[A-Za-z][\\w+-]*\\s+)?(\\d+)\\s+(yes|no)\\s+(\\d+)/\\s*(\\d+)\\s+(\\d+)\\s+(\\d+)"
        + "\\s+.*?\\s+(?:true|false)\\s+([0-9A-Fa-f]{8})\\s+\\d+\\s+\\d+\\s+.*$");

    record Owner(int uid, int pid, int session) {}
    record Track(Owner owner, int id, int port) {}
    record Sample(boolean active, boolean mixer, String frames) {}
    private record Player(Owner owner, int interfaceId) {}
    private record Group(Set<Player> players, long foregroundChange) {}
    private record Review(Map<Track, String> idleFrames, boolean confirmed) {}

    private final BackgroundHost host;
    private final Method serviceLookup;
    private final Executor probeWorker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            task.run();
        }, "NativeComplements-AudioProbe");
        thread.setDaemon(true);
        return thread;
    });
    private final Runnable refresh = this::refresh;
    private final Runnable probe = this::probe;
    private Map<Integer, Set<Player>> playing = Map.of();
    private Map<Integer, Group> candidates = Map.of();
    private final Map<Integer, Review> reviews = new HashMap<>();
    private boolean inFlight;
    private boolean probeFailureReported;
    private boolean stalePlaybackReported;

    BackgroundPlaybackAccess(BackgroundHost host) {
        this.host = host;
        Method lookup = null;
        try {
            lookup = BackgroundHost.method(Class.forName("android.os.ServiceManager"),
                "checkService", IBinder.class, String.class);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            host.unavailable("PLAYBACK_PROBE", failure);
        }
        serviceLookup = lookup;
    }

    void connect(Context context) throws ReflectiveOperationException {
        AudioManager audio = context.getSystemService(AudioManager.class);
        Class<AudioPlaybackConfiguration> type = AudioPlaybackConfiguration.class;
        Method uid = BackgroundHost.method(type, "getClientUid", int.class);
        Method active = BackgroundHost.method(type, "isActive", boolean.class);
        Method[] metadata = playerMetadata(type);
        AudioManager.AudioPlaybackCallback callback = new AudioManager.AudioPlaybackCallback() {
            @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configurations) {
                Map<Integer, Set<Player>> current = new HashMap<>();
                try {
                    for (var player : configurations) {
                        if (!(Boolean) active.invoke(player) || player.getAudioAttributes().getUsage() != AudioAttributes.USAGE_MEDIA) continue;
                        int owner = (Integer) uid.invoke(player);
                        if (owner < 10000 || owner >= 100000) continue;
                        Player identity = metadata == null ? new Player(new Owner(owner, -1, -1), -1)
                            : new Player(new Owner(owner, (Integer) metadata[0].invoke(player),
                                (Integer) metadata[1].invoke(player)), (Integer) metadata[2].invoke(player));
                        current.computeIfAbsent(owner, unused -> new HashSet<>()).add(identity);
                    }
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    host.playbackReady(false);
                    host.unavailable("PLAYBACK", failure);
                    return;
                }
                playing = current;
                refresh();
                host.playbackReady(true);
            }
        };
        audio.registerAudioPlaybackCallback(callback, host.worker);
        callback.onPlaybackConfigChanged(audio.getActivePlaybackConfigurations());
    }

    private Method[] playerMetadata(Class<AudioPlaybackConfiguration> type) {
        try {
            return new Method[] {BackgroundHost.method(type, "getClientPid", int.class),
                BackgroundHost.method(type, "getSessionId", int.class),
                BackgroundHost.method(type, "getPlayerInterfaceId", int.class)};
        } catch (ReflectiveOperationException | RuntimeException failure) {
            host.unavailable("PLAYBACK_METADATA", failure);
            return null;
        }
    }

    void refreshLater() {
        host.worker.removeCallbacks(refresh);
        host.worker.post(refresh);
    }

    private void refresh() {
        Map<Integer, Group> next = new HashMap<>();
        if (serviceLookup != null) playing.forEach((uid, players) -> {
            if (host.canReviewPlayback(uid) && players.stream().allMatch(p -> p.owner.pid > 1 && p.owner.session > 0)) {
                next.put(uid, new Group(Set.copyOf(players), host.foregroundChange(uid)));
            }
        });
        if (!candidates.equals(next)) {
            reviews.keySet().removeIf(uid -> !next.containsKey(uid) || !next.get(uid).equals(candidates.get(uid)));
            candidates = next;
            schedule(FIRST_SAMPLE_MS);
        }
        publish();
    }

    private void publish() {
        Set<Integer> protectedUids = new HashSet<>(playing.keySet());
        reviews.forEach((uid, review) -> { if (review.confirmed) protectedUids.remove(uid); });
        host.playback(Set.copyOf(protectedUids));
    }

    private Map<Integer, Group> pending() {
        Map<Integer, Group> pending = new HashMap<>(candidates);
        reviews.forEach((uid, review) -> { if (review.confirmed) pending.remove(uid); });
        return pending;
    }

    private void schedule(long delay) {
        host.worker.removeCallbacks(probe);
        if (!inFlight && !pending().isEmpty()) host.worker.postDelayed(probe, delay);
    }

    private void probe() {
        Map<Integer, Group> pending = pending();
        if (inFlight || pending.isEmpty()) return;
        inFlight = true;
        probeWorker.execute(() -> {
            Map<Track, Sample> tracks;
            try { tracks = readTracks(); }
            catch (Exception failure) {
                host.worker.post(() -> {
                    if (!probeFailureReported) host.unavailable("PLAYBACK_PROBE", failure);
                    probeFailureReported = true;
                    finish(pending, Map.of());
                });
                return;
            }
            host.worker.post(() -> finish(pending, tracks));
        });
    }

    private void finish(Map<Integer, Group> pending, Map<Track, Sample> tracks) {
        inFlight = false;
        boolean confirm = false;
        for (var entry : pending.entrySet()) {
            int uid = entry.getKey();
            Group group = entry.getValue();
            if (!group.equals(candidates.get(uid)) || !host.canReviewPlayback(uid)
                    || group.foregroundChange != host.foregroundChange(uid)) continue;
            Set<Owner> owners = new HashSet<>();
            group.players.forEach(player -> owners.add(player.owner));
            Map<Track, String> idle = inactiveFrames(owners, tracks);
            if (idle.isEmpty()) { reviews.remove(uid); continue; }
            Review previous = reviews.get(uid);
            boolean confirmed = previous != null && previous.idleFrames.equals(idle);
            reviews.put(uid, new Review(idle, confirmed));
            if (confirmed && !stalePlaybackReported) {
                host.freezerEvent("STALE_PLAYBACK_CONFIRMED");
                stalePlaybackReported = true;
            }
            confirm |= !confirmed;
        }
        publish();
        boolean newCandidate = pending().entrySet().stream().anyMatch(e -> !e.getValue().equals(pending.get(e.getKey())));
        schedule(newCandidate ? FIRST_SAMPLE_MS : confirm ? CONFIRM_MS : RECHECK_MS);
    }

    private Map<Track, Sample> readTracks() throws Exception {
        IBinder service = (IBinder) serviceLookup.invoke(null, "media.audio_flinger");
        if (service == null) throw new IOException("audio mixer unavailable");
        // Android allows audioserver to write to system_server Unix sockets.
        ParcelFileDescriptor[] sockets = ParcelFileDescriptor.createSocketPair();
        try (ParcelFileDescriptor reader = sockets[0]) {
            try (ParcelFileDescriptor writer = sockets[1]) {
                service.dumpAsync(writer.getFileDescriptor(), null);
            }
            StructPollfd event = new StructPollfd();
            event.fd = reader.getFileDescriptor();
            event.events = (short) OsConstants.POLLIN;
            StructPollfd[] events = {event};
            byte[] buffer = new byte[8192];
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            long deadline = SystemClock.elapsedRealtime() + DUMP_TIMEOUT_MS;
            while (output.size() < MAX_DUMP_BYTES) {
                int left = (int) (deadline - SystemClock.elapsedRealtime());
                if (left <= 0 || Os.poll(events, left) == 0) throw new IOException("audio mixer timeout");
                int count = Os.read(event.fd, buffer, 0, Math.min(buffer.length, MAX_DUMP_BYTES - output.size()));
                if (count <= 0) return parseTracks(output.toString(StandardCharsets.UTF_8.name()));
                output.write(buffer, 0, count);
            }
            throw new IOException("audio mixer snapshot too large");
        }
    }

    static Map<Track, Sample> parseTracks(String dump) {
        Map<Track, Sample> tracks = new HashMap<>();
        boolean inThreads = false, inTable = false, output = false, mixer = false;
        for (String line : dump.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("## BEGIN")) {
                if (inThreads) break;
                inThreads = trimmed.equals("## BEGIN thread dump");
                continue;
            }
            if (!inThreads) continue;
            if (trimmed.startsWith("Output thread ") || trimmed.startsWith("Input thread ")
                    || trimmed.startsWith("Record thread ") || trimmed.startsWith("Mmap thread ")) {
                output = trimmed.startsWith("Output thread ");
                mixer = output && trimmed.endsWith("(MIXER):");
                inTable = false;
            }
            if (output && line.startsWith("    Type ") && trimmed.contains("Client(pid/uid)")
                    && trimmed.contains("Server FrmCnt")) {
                inTable = true;
                continue;
            }
            if (!inTable) continue;
            var row = TRACK_ROW.matcher(line);
            if (!row.matches()) { inTable = false; continue; }
            Owner owner = new Owner(Integer.parseInt(row.group(4)), Integer.parseInt(row.group(3)), Integer.parseInt(row.group(5)));
            Track track = new Track(owner, Integer.parseInt(row.group(1)), Integer.parseInt(row.group(6)));
            Sample sample = new Sample(row.group(2).equals("yes"), mixer, row.group(7));
            Sample previous = tracks.put(track, sample);
            if (previous != null && !previous.equals(sample)) tracks.put(track, new Sample(true, false, ""));
        }
        return tracks;
    }

    static Map<Track, String> inactiveFrames(Set<Owner> players, Map<Track, Sample> tracks) {
        Set<Owner> matched = new HashSet<>();
        Map<Track, String> idle = new HashMap<>();
        for (var entry : tracks.entrySet()) {
            Track track = entry.getKey();
            if (!players.contains(track.owner)) continue;
            Sample sample = entry.getValue();
            if (sample.active || !sample.mixer) return Map.of();
            matched.add(track.owner);
            idle.put(track, sample.frames);
        }
        return matched.equals(players) ? idle : Map.of();
    }
}
