package dev.local.sunwindowdemo.core;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.os.UserHandle;

import java.util.List;

/** Verified launcher identities; package/process selection alone never authorizes a bridge. */
public final class LauncherTargets {
    public static final String PIXEL = "com.google.android.apps.nexuslauncher";
    public static final String LINEAGE = "com.android.launcher3";
    private static final String[] ALL = {PIXEL, LINEAGE};
    private static final long HOME_CACHE_MS = 2000;
    private static volatile String cachedHome;
    private static volatile long cachedAt;
    private static volatile int cachedUid = -1;

    private LauncherTargets() {}

    public static String[] all() { return ALL.clone(); }

    public static boolean isLauncher(String packageName) {
        return PIXEL.equals(packageName) || LINEAGE.equals(packageName);
    }

    /** Only the main process owns the verified hooks, not similarly named child processes. */
    public static boolean isLauncherProcess(String processName) { return isLauncher(processName); }

    /** Broadcast routing only; authorization below always resolves HOME again without this cache. */
    public static synchronized List<String> targets(Context context) {
        long now = SystemClock.elapsedRealtime();
        int contextUid = context.getApplicationInfo().uid;
        if (cachedUid != contextUid || cachedHome == null || now - cachedAt > HOME_CACHE_MS) {
            cachedHome = home(context);
            cachedUid = contextUid;
            cachedAt = now;
        }
        return cachedHome == null ? List.of() : List.of(cachedHome);
    }

    public static synchronized void invalidate() {
        cachedHome = null;
        cachedAt = 0;
        cachedUid = -1;
    }

    /** No default/third-party HOME, resolver or query failure may fall back to an installed target. */
    public static String home(Context context) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo resolved = context.getPackageManager().resolveActivity(
                    home, PackageManager.MATCH_DEFAULT_ONLY);
            if (resolved == null || resolved.activityInfo == null) return null;
            String name = resolved.activityInfo.packageName;
            if (!isLauncher(name)) return null;
            ApplicationInfo app = context.getPackageManager().getApplicationInfo(name, 0);
            return (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                    ? name : null;
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return null;
        }
    }

    /** Same-user, allowlisted system HOME's real UID only; never caller-supplied package names. */
    public static boolean isLauncherUid(Context context, int uid) {
        if (uid < 0 || !UserHandle.getUserHandleForUid(uid).equals(android.os.Process.myUserHandle()))
            return false;
        String home = home(context);
        if (home == null) return false;
        try {
            return context.getPackageManager().getPackageUid(home, 0) == uid;
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return false;
        }
    }
}
