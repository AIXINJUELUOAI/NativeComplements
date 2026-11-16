package dev.local.sunwindowdemo.core;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * The two launcher identities this module has verified against their native ABI:
 * Pixel Launcher (PixelOS/Google builds) and AOSP Launcher3 (LineageOS 24.0).
 *
 * <p>The launcher package is an execution target, never a business rule. Feature adapters
 * still probe the actual class/member shape before installing anything.</p>
 */
public final class LauncherTargets {
    public static final String PIXEL = "com.google.android.apps.nexuslauncher";
    public static final String LINEAGE = "com.android.launcher3";
    private static final String[] ALL = {PIXEL, LINEAGE};
    private static final long HOME_CACHE_MS = 2000;
    private static volatile String cachedHome;
    private static volatile long cachedAt;

    private LauncherTargets() {}

    public static String[] all() {
        return ALL.clone();
    }

    public static boolean isLauncher(String packageName) {
        if (packageName == null) return false;
        for (String candidate : ALL) if (candidate.equals(packageName)) return true;
        return false;
    }

    /** True for the launcher main process and its isolated child processes. */
    public static boolean isLauncherProcess(String processName) {
        if (processName == null) return false;
        for (String candidate : ALL)
            if (processName.equals(candidate) || processName.startsWith(candidate + ":")) return true;
        return false;
    }

    /** Installed products in deterministic order; Pixel Launcher wins only when both exist. */
    public static List<String> installed(Context context) {
        List<String> result = new ArrayList<>(2);
        for (String candidate : ALL) {
            try {
                context.getPackageManager().getPackageInfo(candidate, 0);
                result.add(candidate);
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            }
        }
        return result;
    }

    /** The best installed launcher target, or the Pixel identity when none is queryable. */
    public static String primary(Context context) {
        List<String> installed = installed(context);
        return installed.isEmpty() ? PIXEL : installed.get(0);
    }

    /** One active HOME target; a clean test host without launcher packages keeps the historical Pixel target. */
    public static List<String> targets(Context context) {
        // Status publishing can run repeatedly during a drag; avoid a PackageManager query per call.
        String home = cachedHome;
        long now = SystemClock.elapsedRealtime();
        if (home == null || now - cachedAt > HOME_CACHE_MS) {
            home = home(context);
            cachedHome = home;
            cachedAt = now;
        }
        if (home != null && isLauncher(home)) return List.of(home);
        List<String> installed = installed(context);
        return installed.isEmpty() ? List.of(primary(context)) : installed;
    }

    /** Called after a HOME/package change when an immediate re-resolution is required. */
    public static void invalidate() {
        cachedHome = null;
        cachedAt = 0;
    }

    /** Resolve the current HOME implementation when the package manager exposes it. */
    public static String home(Context context) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo resolved = context.getPackageManager().resolveActivity(
                    home, PackageManager.MATCH_DEFAULT_ONLY);
            if (resolved != null && resolved.activityInfo != null
                    && isLauncher(resolved.activityInfo.packageName))
                return resolved.activityInfo.packageName;
        } catch (RuntimeException ignored) {
        }
        return primary(context);
    }

    /** UID authorization for a launcher-owned bridge; no caller-supplied package names. */
    public static boolean isLauncherUid(Context context, int uid) {
        for (String candidate : ALL) {
            try {
                if (context.getPackageManager().getPackageUid(candidate, 0) == uid) return true;
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            }
        }
        return false;
    }
}
