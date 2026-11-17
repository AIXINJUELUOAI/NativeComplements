package dev.local.sunwindowdemo.features.darkaod;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

/** Device-protected experimental policy; never changes the system AOD setting. */
public final class DarkAodStore {
    public static final String AUTHORITY = "dev.nativecomplements.module.darkaod";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);
    public static final String SYSTEM_UI = "com.android.systemui";

    private DarkAodStore() {}

    public static SharedPreferences preferences(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences("dark_aod", Context.MODE_PRIVATE);
    }

    public static Bundle systemSnapshot(Context context) {
        SharedPreferences prefs = preferences(context);
        float dark = clampDarkLux(prefs.getFloat("dark_lux", 1f));
        Bundle result = new Bundle();
        result.putBoolean("enabled", prefs.getBoolean("enabled", false));
        result.putFloat("dark_lux", dark);
        result.putFloat("bright_lux", Math.max(5f, dark + 3f));
        return result;
    }

    public static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("enabled", enabled).apply();
        notifyChanged(context);
    }

    public static void setDarkLux(Context context, float lux) {
        preferences(context).edit().putFloat("dark_lux", clampDarkLux(lux)).apply();
        notifyChanged(context);
    }

    private static float clampDarkLux(float lux) {
        return Float.isFinite(lux) ? Math.max(0f, Math.min(5f, lux)) : 1f;
    }

    private static void notifyChanged(Context context) {
        context.getContentResolver().notifyChange(URI, null);
    }
}
