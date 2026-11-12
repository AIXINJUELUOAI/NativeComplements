package dev.local.sunwindowdemo.features.shade;

import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

/** Device-protected settings, independent of the floating-window switch. */
public final class ShadeStore {
    public static final String AUTHORITY = "dev.nativecomplements.module.shade";
    public static final String CHANGED = "dev.nativecomplements.module.SHADE_CHANGED";
    public static final String SYSTEM_UI = "com.android.systemui";
    private ShadeStore() {}

    public static SharedPreferences preferences(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("landscape_shade", Context.MODE_PRIVATE);
    }

    public static Bundle read(Context context) {
        Bundle result = new Bundle();
        result.putBoolean("enabled", preferences(context).getBoolean("enabled", false));
        return result;
    }

    public static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("enabled", enabled).apply();
        context.sendBroadcast(new Intent(CHANGED).setPackage(SYSTEM_UI), null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }
}
