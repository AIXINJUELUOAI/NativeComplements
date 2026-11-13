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

    public static void setTemplatesEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("templates_enabled", enabled).apply();
        changed(context);
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
        Bundle result = new Bundle();
        result.putBoolean("enabled", preferences(context).getBoolean("enabled", false));
        result.putInt("*", preferences(context).getInt("default_mode", ORIGINAL));
        String prefix = packageName + "/";
        preferences(context).getAll().forEach((key, value) -> {
            if (key.startsWith(prefix) && value instanceof Integer) result.putInt(key.substring(prefix.length()), (Integer) value);
        });
        return result;
    }
}
