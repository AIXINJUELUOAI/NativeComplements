package dev.local.sunwindowdemo.features.appvolume;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.VolumeShaper;
import android.os.Handler;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Local media player state and attenuation, owned by the SystemUI main thread. */
final class MediaPlayers {
    private static final String TAG = "PixelOSMediaVolume";
    private static final int SHAPER_ID = 0x504f5356;
    private final PackageManager packages;
    private final SharedPreferences preferences;
    private final Method clientUid, playerId, playerState, playerType, playerProxy, applyShaper, setShaperId;
    private final int aaudio;
    private final int paused;
    private final int started;
    private final VolumeShaper.Operation replace;
    private final Map<Integer, Integer> applied = new HashMap<>();
    private final Set<Integer> failed = new HashSet<>();
    private final Set<Runnable> observers = new HashSet<>();
    private final Map<String, App> knownApps = new HashMap<>();
    private List<Player> players = List.of();
    private List<App> apps = List.of();
    private boolean enabled;
    private boolean showPercentage = true;

    MediaPlayers(Context context, Handler handler, boolean enabled) throws ReflectiveOperationException {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) throw new IllegalStateException("AudioManager unavailable");
        packages = context.getPackageManager();
        this.enabled = enabled;
        preferences = context.getSharedPreferences("nativecomplements_app_volume", Context.MODE_PRIVATE);
        Class<?> config = AudioPlaybackConfiguration.class;
        clientUid = config.getMethod("getClientUid");
        playerId = config.getMethod("getPlayerInterfaceId");
        playerState = config.getMethod("getPlayerState");
        playerType = config.getMethod("getPlayerType");
        aaudio = config.getField("PLAYER_TYPE_AAUDIO").getInt(null);
        started = config.getField("PLAYER_STATE_STARTED").getInt(null);
        paused = config.getField("PLAYER_STATE_PAUSED").getInt(null);
        playerProxy = config.getMethod("getPlayerProxy");
        applyShaper = playerProxy.getReturnType().getMethod("applyVolumeShaper",
                VolumeShaper.Configuration.class, VolumeShaper.Operation.class);
        setShaperId = VolumeShaper.Configuration.Builder.class.getMethod("setId", int.class);
        Class<?> operations = Class.forName("android.media.VolumeShaper$Operation$Builder");
        Object builder = operations.getConstructor(VolumeShaper.Operation.class)
                .newInstance(VolumeShaper.Operation.PLAY);
        operations.getMethod("replace", int.class, boolean.class).invoke(builder, SHAPER_ID, false);
        replace = (VolumeShaper.Operation) operations.getMethod("build").invoke(builder);

        update(audio.getActivePlaybackConfigurations());
        audio.registerAudioPlaybackCallback(new AudioManager.AudioPlaybackCallback() {
            @Override
            public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configurations) {
                update(configurations);
            }
        }, handler);
    }

    boolean showPercentage() { return showPercentage; }
    boolean enabled() { return enabled; }
    void setShowPercentage(boolean show) {
        if (showPercentage == show) return;
        showPercentage = show;
        notifyObservers();
    }

    void observe(Runnable observer) { observers.add(observer); }
    void unobserve(Runnable observer) { observers.remove(observer); }
    List<App> apps() { return enabled ? apps : List.of(); }
    int gain(App app) { return Math.max(0, Math.min(100, preferences.getInt(app.key(), 100))); }

    void preview(App app, int gain) {
        if (!enabled) return;
        for (Player player : players) {
            if (player.app().equals(app)) apply(player, gain);
        }
    }

    void save(App app, int gain) {
        if (!enabled) return;
        if (gain(app) != gain) preferences.edit().putInt(app.key(), gain).apply();
        preview(app, gain);
    }

    void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        for (Player player : players) {
            if (applied.containsKey(player.id()) || preferences.contains(player.app().key())) {
                apply(player, enabled ? gain(player.app()) : 100);
            }
        }
        notifyObservers();
    }

    private void update(List<AudioPlaybackConfiguration> snapshot) {
        List<Player> next = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        Map<String, App> visible = new LinkedHashMap<>();
        for (AudioPlaybackConfiguration config : snapshot) {
            int usage = config.getAudioAttributes().getUsage();
            if (usage != AudioAttributes.USAGE_MEDIA && usage != AudioAttributes.USAGE_GAME) continue;
            try {
                if ((Integer) playerType.invoke(config) == aaudio) continue;
                int uid = (Integer) clientUid.invoke(config);
                if (uid < 10_000 || uid == android.os.Process.myUid()
                        || uid / 100_000 != android.os.Process.myUid() / 100_000) continue;
                String[] names = packages.getPackagesForUid(uid);
                if (names == null || names.length != 1) continue;
                Object proxy = playerProxy.invoke(config);
                if (proxy == null) continue;
                int id = (Integer) playerId.invoke(config);
                int state = (Integer) playerState.invoke(config);
                String key = uid + ":" + names[0];
                App app = knownApps.computeIfAbsent(key, unused -> loadApp(key, names[0]));
                ids.add(id);
                next.add(new Player(id, app, proxy));
                if (state == started || state == paused) visible.putIfAbsent(key, app);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Released players and ambiguous shared UIDs are omitted.
            }
        }
        players = next;
        applied.keySet().retainAll(ids);
        failed.retainAll(ids);
        knownApps.keySet().retainAll(next.stream().map(player -> player.app().key()).toList());
        for (Player player : players) {
            if (preferences.contains(player.app().key()) && !applied.containsKey(player.id())) {
                apply(player, enabled ? gain(player.app()) : 100);
            }
        }
        List<App> activeApps = new ArrayList<>(visible.values());
        activeApps.sort(java.util.Comparator.comparing(App::name).thenComparing(App::key));
        if (!activeApps.equals(apps)) {
            apps = List.copyOf(activeApps);
            if (enabled) notifyObservers();
        }
    }

    private void notifyObservers() {
        for (Runnable observer : List.copyOf(observers)) observer.run();
    }

    private App loadApp(String key, String packageName) {
        try {
            var info = packages.getApplicationInfo(packageName, 0);
            return new App(key, info.loadLabel(packages).toString(), info.loadIcon(packages));
        } catch (PackageManager.NameNotFoundException error) {
            return new App(key, packageName, packages.getDefaultActivityIcon());
        }
    }

    private void apply(Player player, int gain) {
        Integer old = applied.get(player.id());
        if (old != null && old == gain) return;
        try {
            float level = gain / 100f;
            var shape = new VolumeShaper.Configuration.Builder().setDuration(1)
                    .setInterpolatorType(VolumeShaper.Configuration.INTERPOLATOR_TYPE_LINEAR)
                    .setCurve(new float[] {0f, 1f}, new float[] {level, level});
            setShaperId.invoke(shape, SHAPER_ID);
            applyShaper.invoke(player.proxy(), shape.build(), old == null ? VolumeShaper.Operation.PLAY : replace);
            applied.put(player.id(), gain);
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (failed.add(player.id())) Log.w(TAG, "Could not adjust local audio player", error);
        }
    }

    record App(String key, String name, Drawable icon) {}
    private record Player(int id, App app, Object proxy) {}
}
