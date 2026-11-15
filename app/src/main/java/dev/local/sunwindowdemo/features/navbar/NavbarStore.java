package dev.local.sunwindowdemo.features.navbar;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

/** One opt-in switch; historical navbar rules and their enable flag are intentionally ignored. */
public final class NavbarStore {
    public static final String AUTHORITY = "dev.nativecomplements.module.navbar";
    private NavbarStore() {}

    public static SharedPreferences preferences(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("navbar_immersive", Context.MODE_PRIVATE);
    }

    public static Bundle systemSnapshot(Context context) {
        Bundle result = new Bundle();
        result.putBoolean("enabled", preferences(context).getBoolean("enabled", false));
        return result;
    }

    public static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("enabled", enabled).apply();
        context.getContentResolver().notifyChange(NavbarProvider.URI, null);
    }
}
