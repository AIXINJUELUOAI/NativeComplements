package dev.local.sunwindowdemo.features.navbar;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

/** Activity rules are private to the manager and readable only by their owning UID. */
public final class NavbarStore {
    public static final String AUTHORITY = "dev.nativecomplements.module.navbar";
    public static final int ORIGINAL = 0, COLOR = 1, FLOATING = 2;
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

    public static void setLandscapeClear(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("landscape_clear", enabled).apply();
        changed(context);
    }

    public static SharedPreferences preferences(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("navbar", Context.MODE_PRIVATE);
    }

    public static void setMode(Context context, String packageName, String activity, int mode) {
        if (mode < ORIGINAL || mode > FLOATING) throw new IllegalArgumentException("mode");
        preferences(context).edit().putInt(packageName + "/" + activity, mode).apply();
        changed(context);
    }

    public static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("enabled", enabled).apply();
        changed(context);
    }

    public static void setDefaultMode(Context context, int mode) {
        if (mode < ORIGINAL || mode > FLOATING) throw new IllegalArgumentException("mode");
        preferences(context).edit().putInt("default_mode", mode).apply();
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
        result.putInt("*", snapshot.getInt("default_mode", COLOR));
        String prefix = packageName + "/";
        for (String key : snapshot.keySet()) {
            Object value = snapshot.get(key);
            if (key.startsWith(prefix) && value instanceof Integer) {
                result.putInt(key.substring(prefix.length()), (Integer) value);
                result.putInt(key, (Integer) value);
            }
        }
        result.putBoolean("enabled", snapshot.getBoolean("enabled", false));
        result.putInt("default_mode", snapshot.getInt("default_mode", COLOR));
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
