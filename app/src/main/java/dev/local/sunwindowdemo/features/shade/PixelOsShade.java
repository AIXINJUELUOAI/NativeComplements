package dev.local.sunwindowdemo.features.shade;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

public final class PixelOsShade {
    private PixelOsShade() {}

    @SuppressLint("BlockedPrivateApi")
    public static boolean supports(Context context) {
        if (Build.VERSION.SDK_INT != 37) return false;
        try {
            var get = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
            var version = (String) get.invoke(null, "ro.custom.version");
            var branch = (String) get.invoke(null, "net.pixelos.version");
            if (version != null && version.startsWith("PixelOS_") && branch != null && !branch.isBlank()) return true;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            // Some PixelOS builds hide these properties from ordinary apps.
        }
        try {
            var manager = context.getPackageManager();
            var updater = manager.getApplicationInfo("net.pixelos.ota", 0);
            return (updater.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && manager.checkSignatures("android", updater.packageName) == PackageManager.SIGNATURE_MATCH;
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return false;
        }
    }
}
