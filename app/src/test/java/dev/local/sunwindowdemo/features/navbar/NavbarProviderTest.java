package dev.local.sunwindowdemo.features.navbar;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class NavbarProviderTest {
    private final Context context = RuntimeEnvironment.getApplication();

    @After public void cleanup() {
        ShadowBinder.setCallingUid(android.os.Process.myUid());
        NavbarStore.preferences(context).edit().clear().commit();
        context.createDeviceProtectedStorageContext().getSharedPreferences("navbar", 0).edit().clear().commit();
    }

    private NavbarProvider provider() {
        NavbarProvider provider = new NavbarProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = NavbarStore.AUTHORITY;
        provider.attachInfo(context, info);
        return provider;
    }

    @Test public void oldRulesAndOldEnableFlagDoNotEnableImmersive() {
        var legacy = context.createDeviceProtectedStorageContext().getSharedPreferences("navbar", 0);
        legacy.edit().putBoolean("enabled", true).putBoolean("label_mode", true)
                .putInt("sample/*", 2).commit();
        assertFalse(NavbarStore.systemSnapshot(context).getBoolean("enabled"));
        NavbarStore.setEnabled(context, true);
        Bundle snapshot = NavbarStore.systemSnapshot(context);
        assertEquals(java.util.Set.of("enabled"), snapshot.keySet());
        assertTrue(snapshot.getBoolean("enabled"));
        NavbarStore.setEnabled(context, false);
        assertFalse(NavbarStore.systemSnapshot(context).getBoolean("enabled"));
        assertEquals(2, legacy.getInt("sample/*", -1));
    }

    @Test public void managerAndSystemUiReadOnlyOneBoolean() {
        NavbarStore.setEnabled(context, true);
        ShadowBinder.setCallingUid(context.getApplicationInfo().uid);
        assertTrue(provider().call("system", null, null).getBoolean("enabled"));
        PackageInfo pkg = new PackageInfo();
        pkg.packageName = "com.android.systemui";
        pkg.applicationInfo = new ApplicationInfo();
        pkg.applicationInfo.packageName = pkg.packageName;
        pkg.applicationInfo.uid = 12345;
        org.robolectric.Shadows.shadowOf(context.getPackageManager()).installPackage(pkg);
        ShadowBinder.setCallingUid(12345);
        Bundle extras = new Bundle();
        extras.putBoolean("enabled", false);
        Bundle reply = provider().call("system", "other.package", extras);
        assertEquals(java.util.Set.of("enabled"), reply.keySet());
        assertTrue(reply.getBoolean("enabled"));
    }

    @Test public void actualLauncherUidReadsOnlyEnabledEvenWithoutSystemUiInstalled() {
        PackageInfo pkg = new PackageInfo();
        pkg.packageName = "com.google.android.apps.nexuslauncher";
        pkg.applicationInfo = new ApplicationInfo();
        pkg.applicationInfo.packageName = pkg.packageName;
        pkg.applicationInfo.uid = 54321;
        pkg.applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM;
        var pm = org.robolectric.Shadows.shadowOf(context.getPackageManager());
        pm.installPackage(pkg);
        var home = new android.content.pm.ResolveInfo();
        home.activityInfo = new android.content.pm.ActivityInfo();
        home.activityInfo.packageName = pkg.packageName;
        home.activityInfo.name = pkg.packageName + ".Launcher";
        home.activityInfo.applicationInfo = pkg.applicationInfo;
        pm.setResolveInfosForIntent(new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME), java.util.List.of(home));
        NavbarStore.setEnabled(context, true);
        ShadowBinder.setCallingUid(54321);
        Bundle extras = new Bundle();
        extras.putBoolean("enabled", false);
        Bundle result = provider().call("system", "com.android.systemui", extras);
        assertEquals(java.util.Set.of("enabled"), result.keySet());
        assertTrue(result.getBoolean("enabled"));
        pm.setResolveInfosForIntent(new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME), java.util.List.of());
        assertThrows(SecurityException.class, () -> provider().call("system", null, null));
        ShadowBinder.setCallingUid(54322);
        assertThrows(SecurityException.class, () -> provider().call("system", pkg.packageName, extras));
    }

    @Test public void ordinaryUidAndUnneededSystemServerCannotRead() {
        for (int uid : new int[]{99999, 1000}) {
            ShadowBinder.setCallingUid(uid);
            assertThrows(SecurityException.class, () -> provider().call("system", null, null));
        }
    }

    @Test public void allHistoricalEndpointsAndCrudWritesAreRejected() {
        ShadowBinder.setCallingUid(context.getApplicationInfo().uid);
        var provider = provider();
        for (String method : new String[]{"read", "write", "label", "status", "learn"})
            assertThrows(IllegalArgumentException.class, () -> provider.call(method, "sample", new Bundle()));
        assertThrows(UnsupportedOperationException.class, () -> provider.insert(NavbarProvider.URI, new android.content.ContentValues()));
        assertThrows(UnsupportedOperationException.class, () -> provider.update(NavbarProvider.URI, new android.content.ContentValues(), null, null));
        assertThrows(UnsupportedOperationException.class, () -> provider.delete(NavbarProvider.URI, null, null));
    }
}
