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

    /** value "version,extend,floating,original" votes; a new app version restarts the count. */
    static void learn(Context context, String component, long version, int mode) {
        if (component == null || component.length() > 400 || (mode != COLOR && mode != FLOATING && mode != ORIGINAL)) return;
        SharedPreferences preferences = learnedPreferences(context);
        long[] votes = votes(preferences.getString(component, ""), version);
        if (votes == null) {
            if (!preferences.contains(component) && preferences.getAll().size() >= LEARN_LIMIT) return;
            votes = new long[3];
        }
        votes[mode == COLOR ? 0 : mode == FLOATING ? 1 : 2]++;
        preferences.edit().putString(component, version + "," + votes[0] + "," + votes[1] + "," + votes[2]).apply();
    }

    /** Votes of a stored value for this version, null when absent, unreadable or of another version. */
    static long[] votes(String value, long version) {
        String[] parts = value.split(",");
        if ((parts.length != 3 && parts.length != 4) || (version != Long.MIN_VALUE && !parts[0].equals(Long.toString(version)))) return null;
        try {
            return new long[]{Long.parseLong(parts[1]), Long.parseLong(parts[2]), parts.length == 4 ? Long.parseLong(parts[3]) : 0};
        } catch (NumberFormatException invalid) { return null; }
    }

    /** -2 not enough votes; -1 votes disagree (several pages share one Activity). */
    static int learnedMode(long extend, long floating, long original) {
        long total = extend + floating + original;
        if (total < LEARN_MIN) return -2;
        if (extend >= total * LEARN_AGREEMENT) return COLOR;
        if (floating >= total * LEARN_AGREEMENT) return FLOATING;
        if (original >= total * LEARN_AGREEMENT) return ORIGINAL;
        return -1;
    }

    private static org.json.JSONObject learnedModes(Context context) {
        org.json.JSONObject out = new org.json.JSONObject();
        learnedPreferences(context).getAll().forEach((key, value) -> {
            long[] votes = votes(String.valueOf(value), Long.MIN_VALUE);
            if (votes == null) return;
            int mode = learnedMode(votes[0], votes[1], votes[2]);
            try { if (mode >= 0) out.put(key, mode); } catch (org.json.JSONException ignored) {}
        });
        return out;
    }

    /** Shareable file in the bundled-template format; mixed pages are listed separately, never as rules. */
    public static String exportLearned(Context context) throws org.json.JSONException {
        org.json.JSONObject packages = new org.json.JSONObject();
        org.json.JSONArray mixed = new org.json.JSONArray();
        java.util.TreeMap<String, Object> sorted = new java.util.TreeMap<>(learnedPreferences(context).getAll());
        for (var entry : sorted.entrySet()) {
            String value = String.valueOf(entry.getValue());
            long[] votes = votes(value, Long.MIN_VALUE);
            int slash = entry.getKey().indexOf('/');
            if (votes == null || slash <= 0) continue;
            long version;
            try { version = Long.parseLong(value.split(",")[0]); } catch (NumberFormatException invalid) { continue; }
            String pkg = entry.getKey().substring(0, slash), activity = entry.getKey().substring(slash + 1);
            long extend = votes[0], floating = votes[1], original = votes[2], total = extend + floating + original;
            int mode = learnedMode(extend, floating, original);
            if (mode == -2) continue;
            if (mode == -1) {
                mixed.put(new org.json.JSONObject().put("package", pkg).put("activity", activity)
                        .put("versionCode", version).put("extend", extend).put("floating", floating).put("original", original));
                continue;
            }
            org.json.JSONObject app = packages.optJSONObject(pkg);
            if (app == null) packages.put(pkg, app = new org.json.JSONObject().put("enabled", true).put("rules", new org.json.JSONObject()));
            app.getJSONObject("rules").put(activity, new org.json.JSONObject()
                    .put("mode", mode).put("source", "学习规则").put("base_only", true).put("learned", true)
                    .put("versionCode", version).put("samples", total)
                    .put("agreement", Math.round(Math.max(extend, Math.max(floating, original)) * 1000.0 / total) / 1000.0));
        }
        // Rules the user set or confirmed by hand travel as manually verified rules and win over learning.
        SharedPreferences labels = labelPreferences(context);
        for (var entry : new java.util.TreeMap<>(preferences(context).getAll()).entrySet()) {
            String key = entry.getKey();
            int slash = key.indexOf('/');
            if (!(entry.getValue() instanceof Integer mode) || slash <= 0 || key.endsWith("/*") || key.startsWith("preview:")
                    || mode < ORIGINAL || mode > FLOATING) continue;
            String pkg = key.substring(0, slash), activity = key.substring(slash + 1);
            org.json.JSONObject app = packages.optJSONObject(pkg);
            if (app == null) packages.put(pkg, app = new org.json.JSONObject().put("enabled", true).put("rules", new org.json.JSONObject()));
            app.getJSONObject("rules").put(activity, new org.json.JSONObject()
                    .put("mode", mode).put("source", "人工确认").put("base_only", true).put("manual", true));
        }
        for (String key : new java.util.TreeSet<>(labels.getAll().keySet())) {
            if (!key.startsWith("multi:")) continue;
            String page = key.substring(6);
            int slash = page.indexOf('/');
            if (slash > 0) mixed.put(new org.json.JSONObject().put("package", page.substring(0, slash))
                    .put("activity", page.substring(slash + 1)).put("manual", "multi-page"));
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

    // ---------------- labelling mode (chip in SystemUI) ----------------

    static SharedPreferences labelPreferences(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("navbar_labels", Context.MODE_PRIVATE);
    }

    public static void setLabelMode(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("label_mode", enabled).apply();
        changed(context);
    }

    /** Temporary mode shown on the page while the user decides; -1 withdraws it. Only one at a time. */
    static void preview(Context context, String packageName, String activity, int mode) {
        SharedPreferences.Editor edit = preferences(context).edit();
        for (String key : preferences(context).getAll().keySet()) if (key.startsWith("preview:")) edit.remove(key);
        if (mode >= ORIGINAL && mode <= FLOATING) edit.putInt("preview:" + packageName + "/" + activity, mode);
        edit.apply();
        changed(context);
    }

    /**
     * ✓ in the chip: the mode becomes the Activity rule; the latest confirmation wins. An Activity confirmed
     * with different modes hosts several pages, so its rule is removed and it stays AUTO ("multi").
     */
    static String confirm(Context context, String packageName, String activity, int mode) {
        if (packageName == null || activity == null || mode < ORIGINAL || mode > FLOATING) return "error";
        String page = packageName + "/" + activity;
        SharedPreferences labels = labelPreferences(context);
        SharedPreferences.Editor rules = preferences(context).edit();
        for (String key : preferences(context).getAll().keySet()) if (key.startsWith("preview:")) rules.remove(key);
        String result;
        if (labels.getBoolean("multi:" + page, false)) result = "multi";
        else {
            int before = labels.getInt("confirmed:" + page, -1);
            long at = labels.getLong("confirmedAt:" + page, 0);
            // Different modes confirmed for one Activity within a session of use: several pages share it.
            if (before >= 0 && before != mode && System.currentTimeMillis() - at < 6L * 3600_000) {
                labels.edit().putBoolean("multi:" + page, true).remove("confirmed:" + page).apply();
                rules.remove(page);
                result = "multi";
            } else {
                labels.edit().putInt("confirmed:" + page, mode).putLong("confirmedAt:" + page, System.currentTimeMillis()).apply();
                rules.putInt(page, mode);
                result = "ok";
            }
        }
        rules.apply();
        changed(context);
        return result;
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
