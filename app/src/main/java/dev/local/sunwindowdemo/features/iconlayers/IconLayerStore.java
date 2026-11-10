package dev.local.sunwindowdemo.features.iconlayers;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

/** User-selected icon assets and a narrowly scoped launcher bridge. */
public final class IconLayerStore {
    public static final String TARGET = "dev.local.nativecomplements.lab";
    public static final String LAUNCHER = "com.google.android.apps.nexuslauncher";
    public static final String REFRESH = "dev.local.sunwindowdemo.ICON_LAYERS_REFRESH";
    public static final String CONTROL = "dev.local.sunwindowdemo.CONTROL";
    public static final Uri URI = Uri.parse("content://dev.local.sunwindowdemo.iconlayers/config");

    public static SharedPreferences prefs(Context c) {
        return c.createDeviceProtectedStorageContext().getSharedPreferences("icon_layers", 0);
    }

    public static Bundle read(Context c) {
        SharedPreferences p = prefs(c);
        Bundle b = new Bundle();
        b.putInt("mode", p.getInt("mode", 0));
        b.putBoolean("enabled", p.getBoolean("enabled", false));
        b.putString("generation", p.getString("generation", ""));
        b.putString("title", p.getString("title", ""));
        b.putInt("count", p.getInt("count", 0));
        b.putStringArrayList("originals", new java.util.ArrayList<>(p.getStringSet("originals", java.util.Collections.emptySet())));
        b.putStringArrayList("restore", new java.util.ArrayList<>(p.getStringSet("restore", java.util.Collections.emptySet())));
        b.putString("version", p.getString("version", ""));
        b.putString("status", p.getString("status", "等待桌面连接"));
        b.putString("error", p.getString("error", ""));
        b.putInt("replacements", p.getInt("replacements", 0));
        b.putInt("animations", p.getInt("animations", 0));
        b.putInt("loaded", p.getInt("loaded", 0));
        b.putInt("loadFailures", p.getInt("loadFailures", 0));
        b.putLong("seen", p.getLong("seen", 0));
        return b;
    }

    public static void save(Context c, int mode) {
        if (mode < 0 || mode > 2) throw new IllegalArgumentException("icon mode");
        prefs(c).edit().putInt("mode", mode).apply();
        refresh(c);
    }

    public static void refresh(Context c) {
        c.sendBroadcast(new Intent(REFRESH).setPackage(LAUNCHER).addFlags(Intent.FLAG_RECEIVER_FOREGROUND));
    }
    public static void refreshAll(Context c) {
        c.sendBroadcast(new Intent(REFRESH).setPackage(LAUNCHER).putExtra("all", true).addFlags(Intent.FLAG_RECEIVER_FOREGROUND));
    }

    public static void enabled(Context c, boolean enabled) {
        prefs(c).edit().putBoolean("enabled", enabled).apply(); refresh(c);
    }
    public static void original(Context c, String pkg, boolean original) {
        if (!IconArchive.validPackage(pkg)) throw new IllegalArgumentException("package");
        var set = new java.util.HashSet<>(prefs(c).getStringSet("originals", java.util.Collections.emptySet()));
        if (original) set.add(pkg); else set.remove(pkg);
        prefs(c).edit().putStringSet("originals", set).apply(); refresh(c);
    }

    static void report(Context c, Bundle b) {
        prefs(c).edit().putString("version", b.getString("version", ""))
            .putString("status", limit(b.getString("status", "")))
            .putString("error", limit(b.getString("error", "")))
            .putInt("replacements", b.getInt("replacements"))
            .putInt("animations", b.getInt("animations"))
            .putInt("loaded", b.getInt("loaded"))
            .putInt("loadFailures", b.getInt("loadFailures"))
            .putLong("seen", SystemClock.elapsedRealtime()).apply();
    }

    private static String limit(String s) { return s.substring(0, Math.min(s.length(), 240)); }
}
