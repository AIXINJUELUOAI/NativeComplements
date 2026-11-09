package dev.local.sunwindowdemo.features.edgetouch;

import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

/** Own settings; deliberately independent of the floating-window enabled switch. */
public final class EdgeTouchStore {
    public static final String AUTHORITY = "dev.local.sunwindowdemo.edgetouch";
    public static final String CHANGED = "dev.local.sunwindowdemo.EDGE_TOUCH_CHANGED";
    public static final String SYSTEM_UI = "com.android.systemui";
    private EdgeTouchStore() {}

    public static SharedPreferences preferences(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("edge_touch", Context.MODE_PRIVATE);
    }

    public static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    public static Bundle read(Context context) {
        SharedPreferences p = preferences(context);
        Bundle b = new Bundle();
        b.putBoolean("enabled", p.getBoolean("enabled", false));
        b.putBoolean("left_enabled", p.getBoolean("left_enabled", true));
        b.putBoolean("right_enabled", p.getBoolean("right_enabled", true));
        b.putInt("left_width_dp", clamp(p.getInt("left_width_dp", 8), 1, 48));
        b.putInt("right_width_dp", clamp(p.getInt("right_width_dp", 8), 1, 48));
        b.putInt("height_percent", clamp(p.getInt("height_percent", 80), 20, 100));
        b.putInt("position_percent", clamp(p.getInt("position_percent", 50), 0, 100));
        return b;
    }

    public static void setBoolean(Context context, String key, boolean value) {
        if (!key.equals("enabled") && !key.equals("left_enabled") && !key.equals("right_enabled"))
            throw new IllegalArgumentException("edge touch preference");
        preferences(context).edit().putBoolean(key, value).apply();
        changed(context);
    }

    public static void setNumber(Context context, String key, int value) {
        int min, max;
        switch (key) {
            case "left_width_dp": case "right_width_dp": min = 1; max = 48; break;
            case "height_percent": min = 20; max = 100; break;
            case "position_percent": min = 0; max = 100; break;
            default: throw new IllegalArgumentException("edge touch preference");
        }
        preferences(context).edit().putInt(key, clamp(value, min, max)).apply();
        changed(context);
    }

    public static void changed(Context context) {
        context.sendBroadcast(new Intent(CHANGED).setPackage(SYSTEM_UI), null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }
}
