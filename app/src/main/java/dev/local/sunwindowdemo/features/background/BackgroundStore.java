package dev.local.sunwindowdemo.features.background;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Bundle;
import dev.local.sunwindowdemo.core.ModuleIdentity;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONException;
import org.json.JSONObject;

import static dev.local.sunwindowdemo.features.background.BackgroundPolicy.*;

public final class BackgroundStore {
    public static final Uri URI = Uri.parse("content://" + ModuleIdentity.CURRENT.authority("background") + "/config");
    private static final String PREFS = "background_management";
    private static final String RULES = "rules";
    private static final String ENABLED = "enabled";
    private static final String PROTECT_PLAYBACK = "protect_playback";
    private static final String KEEP_ACTIVE = "keep_active";
    private static final String PROTECT_BACKGROUND = "protect_background";
    private static final String WAKE_LOCKS = "background_wake_locks";
    private static final String WAKE_LOCKS_ENABLED = "background_wake_locks_enabled";
    public static final WakeLockRule DEFAULT_WAKE_LOCK_RULE = new WakeLockRule(30, 15, 60);
    static final String OBSERVED = "observed:";
    static final int MAX_OBSERVED_PROCESSES = 64;

    private BackgroundStore() {}

    public static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean eligible(ApplicationInfo info) {
        return userInstalled(info) && !ModuleIdentity.FULL_PACKAGE.equals(info.packageName)
            && !ModuleIdentity.WINDOW_PACKAGE.equals(info.packageName);
    }

    static boolean userInstalled(ApplicationInfo info) {
        int excluded = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP | ApplicationInfo.FLAG_PERSISTENT;
        return info != null && info.uid >= 10000 && info.uid < 100000 && (info.flags & excluded) == 0;
    }

    static Bundle read(Context context) {
        SharedPreferences prefs = prefs(context);
        Bundle bundle = new Bundle();
        bundle.putBoolean(ENABLED, prefs.getBoolean(ENABLED, false));
        bundle.putString(RULES, prefs.getString(RULES, "{}"));
        bundle.putString(WAKE_LOCKS, prefs.getString(WAKE_LOCKS, "{}"));
        bundle.putBoolean(WAKE_LOCKS_ENABLED, prefs.getBoolean(WAKE_LOCKS_ENABLED, true));
        return bundle;
    }

    public record WakeLockRule(int delaySeconds, int budgetSeconds, int periodSeconds) {
        public WakeLockRule {
            if (delaySeconds < 0 || delaySeconds > 120 || periodSeconds < 30 || periodSeconds > 300
                    || budgetSeconds < 1 || budgetSeconds > 60 || budgetSeconds >= periodSeconds)
                throw new IllegalArgumentException("wake lock budget");
        }
    }

    public record WakeLockSettings(boolean enabled, Map<String, WakeLockRule> overrides, Set<String> exempt) {
        public WakeLockRule rule(String pkg) {
            return exempt.contains(pkg) ? null : overrides.getOrDefault(pkg, DEFAULT_WAKE_LOCK_RULE);
        }
    }

    public static WakeLockSettings wakeLockSettings(Context context) { return wakeLockSettings(read(context)); }

    static WakeLockSettings wakeLockSettings(Bundle bundle) {
        if (bundle == null) return new WakeLockSettings(false, Map.of(), Set.of());
        Map<String, WakeLockRule> rules = new HashMap<>();
        Set<String> exempt = new TreeSet<>();
        JSONObject saved = object(bundle.getString(WAKE_LOCKS));
        for (Iterator<String> keys = saved.keys(); keys.hasNext();) {
            String pkg = keys.next();
            if (Boolean.FALSE.equals(saved.opt(pkg))) {
                exempt.add(pkg);
                continue;
            }
            JSONObject entry = saved.optJSONObject(pkg);
            if (entry == null) continue;
            try {
                rules.put(pkg, new WakeLockRule(entry.optInt("delay", -1), entry.optInt("budget", -1),
                    entry.optInt("period", -1)));
            } catch (IllegalArgumentException invalid) { /* Ignore invalid entries. */ }
        }
        return new WakeLockSettings(bundle.getBoolean(WAKE_LOCKS_ENABLED, true), Map.copyOf(rules), Set.copyOf(exempt));
    }

    public static void wakeLocksEnabled(Context context, boolean enabled) {
        save(context, prefs(context).edit().putBoolean(WAKE_LOCKS_ENABLED, enabled));
    }

    public static void wakeLockRule(Context context, String pkg, WakeLockRule rule) {
        Object entry = Boolean.FALSE;
        if (DEFAULT_WAKE_LOCK_RULE.equals(rule)) entry = null;
        else if (rule != null) {
            try { entry = new JSONObject().put("delay", rule.delaySeconds()).put("budget", rule.budgetSeconds())
                .put("period", rule.periodSeconds()); }
            catch (JSONException invalid) { throw new IllegalArgumentException("wake lock budget", invalid); }
        }
        setAppOption(context, WAKE_LOCKS, pkg, entry);
    }

    private static synchronized void setAppOption(Context context, String key, String pkg, Object value) {
        JSONObject options = object(prefs(context).getString(key, "{}"));
        try { options.put(pkg, value); }
        catch (JSONException malformed) { throw new IllegalArgumentException("background option", malformed); }
        save(context, prefs(context).edit().putString(key, options.toString()));
    }

    public static Settings settings(Context context) { return settings(read(context)); }

    static Settings settings(Bundle bundle) {
        if (bundle == null) return new Settings(false, Map.of());
        Map<String, AppRule> rules = new HashMap<>();
        JSONObject json = object(bundle.getString(RULES));
        for (Iterator<String> i = json.keys(); i.hasNext();) {
            String pkg = i.next();
            JSONObject row = json.optJSONObject(pkg);
            if (row == null) continue;
            Mode mode = value(Mode.class, row.optString("mode"), Mode.STRICT);
            Action batch = value(Action.class, row.optString("batch"), null);
            if (!canBatch(batch)) batch = null;
            Map<String, Action> actions = new HashMap<>();
            JSONObject processes = row.optJSONObject("processes");
            if (processes != null) for (Iterator<String> names = processes.keys(); names.hasNext();) {
                String name = names.next();
                Action action = value(Action.class, processes.optString(name), null);
                if (action != null) actions.put(name, action);
            }
            boolean protectPlayback = !supportsPlaybackOverride(pkg) || row.optBoolean(PROTECT_PLAYBACK, defaultPlaybackProtection(pkg));
            rules.put(pkg, new AppRule(mode, batch, actions, protectPlayback, row.optBoolean(KEEP_ACTIVE), row.optBoolean(PROTECT_BACKGROUND)));
        }
        return new Settings(bundle.getBoolean(ENABLED), rules);
    }

    public static void enabled(Context context, boolean value) {
        save(context, prefs(context).edit().putBoolean(ENABLED, value));
    }

    public static synchronized void mode(Context context, String pkg, Mode mode) {
        change(context, pkg, row -> row.put("mode", mode.name()));
    }

    public static synchronized void protectPlayback(Context context, String pkg, boolean enabled) {
        if (!supportsPlaybackOverride(pkg)) throw new IllegalArgumentException("playback override");
        change(context, pkg, row -> row.put(PROTECT_PLAYBACK, enabled));
    }

    public static synchronized void keepActive(Context context, String pkg, boolean enabled) {
        change(context, pkg, row -> row.put(KEEP_ACTIVE, enabled));
    }

    public static synchronized void protectBackground(Context context, String pkg, boolean enabled) {
        change(context, pkg, row -> row.put(PROTECT_BACKGROUND, enabled));
    }

    public static synchronized void batch(Context context, String pkg, Action action) {
        if (!canBatch(action)) throw new IllegalArgumentException("batch action");
        change(context, pkg, row -> {
            if (action == null) row.remove("batch"); else row.put("batch", action.name());
            row.remove("processes");
        });
    }

    public static synchronized void process(Context context, String pkg, String name, Action action) {
        change(context, pkg, row -> {
            JSONObject processes = row.optJSONObject("processes");
            if (processes == null) processes = new JSONObject();
            if (action == null) processes.remove(name); else processes.put(name, action.name());
            row.put("processes", processes);
        });
    }

    public static Set<String> observedProcesses(Context context, String pkg) {
        return Set.copyOf(prefs(context).getStringSet(OBSERVED + pkg, Set.of()));
    }

    static synchronized void recordProcess(Context context, String pkg, String name) {
        if (pkg == null || name == null || pkg.length() > 255 || name.length() > 255
                || !pkg.matches("[a-zA-Z0-9_.]+") || !name.matches("[a-zA-Z0-9_.:]+")) return;
        Set<String> names = new TreeSet<>(observedProcesses(context, pkg));
        if (names.size() < MAX_OBSERVED_PROCESSES && names.add(name)) {
            prefs(context).edit().putStringSet(OBSERVED + pkg, names).apply();
        }
    }

    private interface Edit { void update(JSONObject row) throws JSONException; }

    private static void change(Context context, String pkg, Edit edit) {
        JSONObject rules = object(prefs(context).getString(RULES, "{}"));
        JSONObject row = rules.optJSONObject(pkg);
        if (row == null) row = new JSONObject();
        try {
            edit.update(row);
            rules.put(pkg, row);
        } catch (JSONException malformed) {
            throw new IllegalArgumentException("background rule", malformed);
        }
        save(context, prefs(context).edit().putString(RULES, rules.toString()));
    }

    private static void save(Context context, SharedPreferences.Editor edit) {
        edit.apply();
        context.getContentResolver().notifyChange(URI, null);
    }

    private static JSONObject object(String serialized) {
        try { return new JSONObject(serialized == null ? "{}" : serialized); }
        catch (JSONException malformed) { return new JSONObject(); }
    }

    private static <T extends Enum<T>> T value(Class<T> type, String key, T fallback) {
        try { return Enum.valueOf(type, key); }
        catch (IllegalArgumentException invalid) { return fallback; }
    }
}
