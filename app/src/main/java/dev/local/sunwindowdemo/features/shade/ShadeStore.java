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
        result.putInt("statusBarOffset", offset(context));
        result.putInt("notificationIconScale", iconScale(context, true));
        result.putInt("systemIconScale", iconScale(context, false));
        result.putBoolean("signalDisplay", preferences(context).getBoolean("signal_display", false));
        result.putBoolean("drawerMonet", drawerMonet(context));
        return result;
    }

    public static void setSignalDisplay(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("signal_display", enabled).apply();
        context.sendBroadcast(new Intent(CHANGED).setPackage(SYSTEM_UI), null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }

    /** Retired surfaces: old saved preferences are deliberately ignored. Drawer Monet is independent. */
    public static boolean coverMonet(Context context) { return false; }
    public static boolean windowMonet(Context context) { return false; }
    public static void setCoverMonet(Context context, boolean enabled) {}
    public static void setWindowMonet(Context context, boolean enabled) {}

    public static boolean drawerMonet(Context context) {
        return preferences(context).getBoolean("drawer_monet", false);
    }

    public static void setDrawerMonet(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("drawer_monet", enabled).apply();
        context.sendBroadcast(new Intent(CHANGED).setPackage(
                dev.local.sunwindowdemo.features.iconlayers.IconLayerStore.LAUNCHER), null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }

    public static int iconScale(Context context, boolean notification) {
        return ShadePolicy.iconScale(preferences(context).getInt(
                notification ? "notification_icon_scale" : "system_icon_scale", 100));
    }

    public static void setIconScale(Context context, boolean notification, int percent) {
        preferences(context).edit().putInt(notification ? "notification_icon_scale" : "system_icon_scale",
                ShadePolicy.iconScale(percent)).apply();
        context.sendBroadcast(new Intent(CHANGED).setPackage(SYSTEM_UI), null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }

    public static int offset(Context context) {
        return ShadePolicy.offset(preferences(context).getInt("status_bar_offset", 0));
    }

    public static void setOffset(Context context, int dp) {
        preferences(context).edit().putInt("status_bar_offset", ShadePolicy.offset(dp)).apply();
        context.sendBroadcast(new Intent(CHANGED).setPackage(SYSTEM_UI), null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }

    public static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("enabled", enabled).apply();
        context.sendBroadcast(new Intent(CHANGED).setPackage(SYSTEM_UI), null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }
}
