package dev.local.sunwindowdemo.features.navbar;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

/** Activity rules are private to the manager and readable only by their owning UID. */
public final class NavbarStore {
    public static final String AUTHORITY = "dev.nativecomplements.module.navbar";
    /** AUTO: the single-frame layout classifier picks COLOR (extend) or FLOATING per window. */
    public static final int ORIGINAL = 0, COLOR = 1, FLOATING = 2, AUTO = 3;
    private NavbarStore() {}
    private static String templates;

    public static synchronized Bundle systemSnapshot(Context context) {
        Bundle result = new Bundle();
        preferences(context).getAll().forEach((key, value) -> {
            if (value instanceof Integer) result.putInt(key, (Integer) value);
            if (value instanceof Boolean) result.putBoolean(key, (Boolean) value);
        });
        if (templates == null) {
            try (var input = context.getAssets().open("navbar/templates.json")) {
                templates = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException error) { templates = "{}"; }
        }
        result.putString("templates", templates);
        result.putString("learned", learnedModes(context).toString());
        return result;
    }

    /** Votes needed before a page counts as learned, and the agreement that makes it a fixed mode. */
    static final int LEARN_MIN = 3;
    static final double LEARN_AGREEMENT = 0.9;
    private static final int LEARN_LIMIT = 3000;

    static SharedPreferences learnedPreferences(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("navbar_learned", Context.MODE_PRIVATE);
    }

    /** value "version,extendVotes,floatingVotes"; a new app version restarts the count. */
    static void learn(Context context, String component, long version, int mode) {
        if (component == null || component.length() > 400 || (mode != COLOR && mode != FLOATING)) return;
        SharedPreferences preferences = learnedPreferences(context);
        String[] old = preferences.getString(component, "").split(",");
        long extend = 0, floating = 0;
        if (old.length == 3 && old[0].equals(Long.toString(version))) {
            try { extend = Long.parseLong(old[1]); floating = Long.parseLong(old[2]); }
            catch (NumberFormatException ignored) { extend = 0; floating = 0; }
        } else if (!preferences.contains(component) && preferences.getAll().size() >= LEARN_LIMIT) return;
        if (mode == COLOR) extend++; else floating++;
        preferences.edit().putString(component, version + "," + extend + "," + floating).apply();
    }

    /** -1 for a page whose votes disagree (several pages share one Activity). */
    static int learnedMode(long extend, long floating) {
        long total = extend + floating;
        if (total < LEARN_MIN) return -2;
        if (extend >= total * LEARN_AGREEMENT) return COLOR;
        if (floating >= total * LEARN_AGREEMENT) return FLOATING;
        return -1;
    }

    private static org.json.JSONObject learnedModes(Context context) {
        org.json.JSONObject out = new org.json.JSONObject();
        learnedPreferences(context).getAll().forEach((key, value) -> {
            String[] parts = String.valueOf(value).split(",");
            if (parts.length != 3) return;
            try {
                int mode = learnedMode(Long.parseLong(parts[1]), Long.parseLong(parts[2]));
                if (mode == COLOR || mode == FLOATING) out.put(key, mode);
            } catch (NumberFormatException | org.json.JSONException ignored) {}
        });
        return out;
    }

    /** Shareable file in the bundled-template format; mixed pages are listed separately, never as rules. */
    public static String exportLearned(Context context) throws org.json.JSONException {
        org.json.JSONObject packages = new org.json.JSONObject();
        org.json.JSONArray mixed = new org.json.JSONArray();
        java.util.TreeMap<String, Object> sorted = new java.util.TreeMap<>(learnedPreferences(context).getAll());
        for (var entry : sorted.entrySet()) {
            String[] parts = String.valueOf(entry.getValue()).split(",");
            int slash = entry.getKey().indexOf('/');
            if (parts.length != 3 || slash <= 0) continue;
            long version, extend, floating;
            try { version = Long.parseLong(parts[0]); extend = Long.parseLong(parts[1]); floating = Long.parseLong(parts[2]); }
            catch (NumberFormatException invalid) { continue; }
            String pkg = entry.getKey().substring(0, slash), activity = entry.getKey().substring(slash + 1);
            int mode = learnedMode(extend, floating);
            long total = extend + floating;
            if (mode == -2) continue;
            if (mode == -1) {
                mixed.put(new org.json.JSONObject().put("package", pkg).put("activity", activity)
                        .put("versionCode", version).put("extend", extend).put("floating", floating));
                continue;
            }
            org.json.JSONObject app = packages.optJSONObject(pkg);
            if (app == null) packages.put(pkg, app = new org.json.JSONObject().put("enabled", true).put("rules", new org.json.JSONObject()));
            app.getJSONObject("rules").put(activity, new org.json.JSONObject()
                    .put("mode", mode).put("source", "学习规则").put("base_only", true).put("learned", true)
                    .put("versionCode", version).put("samples", total)
                    .put("agreement", Math.round(Math.max(extend, floating) * 1000.0 / total) / 1000.0));
        }
        return new org.json.JSONObject()
                .put("name", "类原生补全计划导航条学习结果")
                .put("format", 1)
                .put("device", android.os.Build.MODEL)
                .put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("exportedAt", System.currentTimeMillis())
                .put("packages", packages)
                .put("mixed", mixed).toString(2);
    }

    public static void clearLearned(Context context) {
        learnedPreferences(context).edit().clear().apply();
    }

    public static void setLandscapeClear(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("landscape_clear", enabled).apply();
        changed(context);
    }

    public static SharedPreferences preferences(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("navbar", Context.MODE_PRIVATE);
    }

    public static void setMode(Context context, String packageName, String activity, int mode) {
        if (mode < ORIGINAL || mode > AUTO) throw new IllegalArgumentException("mode");
        preferences(context).edit().putInt(packageName + "/" + activity, mode).apply();
        changed(context);
    }

    public static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("enabled", enabled).apply();
        changed(context);
    }

    public static void clearMode(Context context, String packageName, String activity) {
        preferences(context).edit().remove(packageName + "/" + activity).apply();
        changed(context);
    }

    private static void changed(Context context) {
        context.getContentResolver().notifyChange(android.net.Uri.parse("content://" + AUTHORITY), null);
    }

    public static Bundle read(Context context, String packageName) {
        Bundle snapshot = systemSnapshot(context);
        Bundle result = new Bundle();
        result.putInt("*", AUTO);
        String prefix = packageName + "/";
        for (String key : snapshot.keySet()) {
            Object value = snapshot.get(key);
            if (key.startsWith(prefix) && value instanceof Integer) {
                result.putInt(key.substring(prefix.length()), (Integer) value);
                result.putInt(key, (Integer) value);
            }
        }
        result.putBoolean("enabled", snapshot.getBoolean("enabled", false));
        org.json.JSONObject packages = new org.json.JSONObject();
        try {
            org.json.JSONObject all = new org.json.JSONObject(snapshot.getString("templates", "{}"))
                    .optJSONObject("packages");
            if (all != null && all.optJSONObject(packageName) != null)
                packages.put(packageName, all.getJSONObject(packageName));
            result.putString("templates", new org.json.JSONObject().put("packages", packages).toString());
        } catch (org.json.JSONException invalid) { result.putString("templates", "{\"packages\":{}}"); }
        return result;
    }
}
