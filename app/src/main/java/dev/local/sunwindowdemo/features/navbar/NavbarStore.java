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
        return result;
    }

    // ---------------- labelling mode (chip in SystemUI) ----------------

    public static void setLabelMode(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("label_mode", enabled).apply();
        changed(context);
    }

    /**
     * A tap in the label panel: the mode (AUTO included) becomes this Activity's rule at once, -1 removes it.
     * The latest tap wins. Returns the previous rule (-1 = none) so the panel can undo the tap; -3 only reads it.
     */
    static int label(Context context, String packageName, String activity, int mode) {
        if (packageName == null || activity == null || mode < -3 || mode > AUTO) return -2;
        String page = packageName + "/" + activity;
        SharedPreferences rules = preferences(context);
        int previous = rules.getInt(page, -1);
        if (mode == -3) return previous;   // query only
        if (mode == -2) return -2;
        SharedPreferences.Editor edit = rules.edit();
        for (String key : rules.getAll().keySet()) if (key.startsWith("preview:")) edit.remove(key);
        if (mode < 0) edit.remove(page); else edit.putInt(page, mode);
        edit.apply();
        changed(context);
        return previous;
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
        result.putBoolean("label_mode", snapshot.getBoolean("label_mode", false));
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
