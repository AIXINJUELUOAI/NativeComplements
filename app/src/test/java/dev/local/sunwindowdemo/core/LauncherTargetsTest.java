package dev.local.sunwindowdemo.core;

import android.content.Context;
import android.content.Intent;
import android.content.pm.*;
import java.util.List;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class LauncherTargetsTest {
    private final Context context = RuntimeEnvironment.getApplication();
    private final Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);

    @Before @After public void invalidate() { LauncherTargets.invalidate(); }

    private void home(String name, int uid, boolean system) {
        PackageInfo pkg = new PackageInfo(); pkg.packageName = name;
        pkg.applicationInfo = new ApplicationInfo(); pkg.applicationInfo.packageName = name;
        pkg.applicationInfo.uid = uid;
        pkg.applicationInfo.flags = system ? ApplicationInfo.FLAG_SYSTEM : 0;
        var pm = Shadows.shadowOf(context.getPackageManager());
        pm.installPackage(pkg);
        ResolveInfo resolved = new ResolveInfo(); resolved.activityInfo = new ActivityInfo();
        resolved.activityInfo.packageName = name; resolved.activityInfo.name = name + ".Home";
        resolved.activityInfo.applicationInfo = pkg.applicationInfo;
        pm.setResolveInfosForIntent(homeIntent, List.of(resolved));
    }

    @Test public void onlyActualAllowlistedSystemHomeUidIsTrustedWithoutRoutingCache() {
        home(LauncherTargets.PIXEL, 10042, true);
        assertEquals(List.of(LauncherTargets.PIXEL), LauncherTargets.targets(context));
        assertTrue(LauncherTargets.isLauncherUid(context, 10042));
        assertFalse(LauncherTargets.isLauncherUid(context, 110042));
        home(LauncherTargets.LINEAGE, 10043, true);
        assertFalse(LauncherTargets.isLauncherUid(context, 10042));
        assertTrue(LauncherTargets.isLauncherUid(context, 10043));
        home("com.example.home", 10044, true);
        assertNull(LauncherTargets.home(context));
        assertFalse(LauncherTargets.isLauncherUid(context, 10044));
        assertFalse(LauncherTargets.isLauncherUid(context, 10043));
        LauncherTargets.invalidate(); assertTrue(LauncherTargets.targets(context).isEmpty());
    }

    @Test public void installedPackageOrNameAloneNeverAuthorizes() {
        home(LauncherTargets.LINEAGE, 10043, false);
        assertNull(LauncherTargets.home(context));
        assertFalse(LauncherTargets.isLauncherUid(context, 10043));
        Shadows.shadowOf(context.getPackageManager()).setResolveInfosForIntent(homeIntent, List.of());
        assertTrue(LauncherTargets.targets(context).isEmpty());
        assertFalse(LauncherTargets.isLauncherProcess(LauncherTargets.PIXEL + ":isolated"));
        assertFalse(LauncherTargets.isLauncherProcess("com.example.home"));
        assertTrue(LauncherTargets.isLauncherProcess(LauncherTargets.LINEAGE));
    }
}
