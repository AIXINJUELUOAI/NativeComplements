package dev.local.sunwindowdemo;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import dev.local.sunwindowdemo.features.darkaod.DarkAodProvider;
import dev.local.sunwindowdemo.features.darkaod.DarkAodStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class DarkAodStoreTest {
    private final Context context = RuntimeEnvironment.getApplication();

    @Before public void resetPreferences() {
        DarkAodStore.preferences(context).edit().clear().commit();
        ShadowBinder.setCallingUid(context.getApplicationInfo().uid);
    }

    @After public void cleanup() {
        DarkAodStore.preferences(context).edit().clear().commit();
        ShadowBinder.setCallingUid(android.os.Process.myUid());
    }

    @Test public void defaultsAreOffWithFloatThresholdsAndEnablingIsIndependent() {
        Bundle snapshot = DarkAodStore.systemSnapshot(context);
        assertEquals(java.util.Set.of("enabled", "dark_lux", "bright_lux"), snapshot.keySet());
        assertFalse(snapshot.getBoolean("enabled"));
        assertThresholds(1f, 5f);
        DarkAodStore.setEnabled(context, true);
        assertTrue(DarkAodStore.systemSnapshot(context).getBoolean("enabled"));
        assertThresholds(1f, 5f);
        DarkAodStore.setEnabled(context, false);
        assertFalse(DarkAodStore.systemSnapshot(context).getBoolean("enabled"));
    }

    @Test public void setterClampsAndPreservesFractionalLuxWithBrightHysteresis() {
        float[][] cases = {{-1f, 0f, 5f}, {1.5f, 1.5f, 5f}, {2.5f, 2.5f, 5.5f},
                {5f, 5f, 8f}, {100f, 5f, 8f}, {Float.NaN, 1f, 5f},
                {Float.POSITIVE_INFINITY, 1f, 5f}, {Float.NEGATIVE_INFINITY, 1f, 5f}};
        for (float[] values : cases) {
            DarkAodStore.setDarkLux(context, values[0]);
            assertThresholds(values[1], values[2]);
            assertEquals(values[1], DarkAodStore.preferences(context).getFloat("dark_lux", -1f), 0f);
            assertFalse(DarkAodStore.systemSnapshot(context).getBoolean("enabled"));
        }
    }

    @Test public void snapshotAlsoClampsStoredValues() {
        DarkAodStore.preferences(context).edit().putFloat("dark_lux", -10f).commit();
        assertThresholds(0f, 5f);
        DarkAodStore.preferences(context).edit().putFloat("dark_lux", 10f).commit();
        assertThresholds(5f, 8f);
        DarkAodStore.preferences(context).edit().putFloat("dark_lux", Float.NaN).commit();
        assertThresholds(1f, 5f);
    }

    @Test public void providerRejectsForeignUidAndRemoteWrites() {
        DarkAodProvider provider = provider();
        assertFalse(provider.call("system", null, null).getBoolean("enabled"));
        assertThrows(IllegalArgumentException.class, () -> provider.call("write", null, new Bundle()));
        for (int uid : new int[]{23456, 1000, 123456}) {
            ShadowBinder.setCallingUid(uid);
            assertThrows(SecurityException.class, () -> provider.call("system", "com.android.systemui", null));
        }
    }

    @Test public void onlySameUserRealSystemUiUidWithSystemFlagCanRead() {
        DarkAodProvider provider = provider();
        installSystemUi(23456, 0);
        ShadowBinder.setCallingUid(23456);
        assertThrows(SecurityException.class, () -> provider.call("system", null, null));
        installSystemUi(23456, ApplicationInfo.FLAG_SYSTEM);
        assertFalse(provider.call("system", null, null).getBoolean("enabled"));
        installSystemUi(123456, ApplicationInfo.FLAG_SYSTEM);
        ShadowBinder.setCallingUid(123456);
        assertThrows(SecurityException.class, () -> provider.call("system", null, null));
    }

    private void assertThresholds(float dark, float bright) {
        Bundle snapshot = DarkAodStore.systemSnapshot(context);
        assertEquals(dark, snapshot.getFloat("dark_lux"), 0f);
        assertEquals(bright, snapshot.getFloat("bright_lux"), 0f);
    }

    private DarkAodProvider provider() {
        DarkAodProvider provider = new DarkAodProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = DarkAodStore.AUTHORITY;
        provider.attachInfo(context, info);
        return provider;
    }

    private void installSystemUi(int uid, int flags) {
        PackageInfo pkg = new PackageInfo();
        pkg.packageName = DarkAodStore.SYSTEM_UI;
        pkg.applicationInfo = new ApplicationInfo();
        pkg.applicationInfo.packageName = pkg.packageName;
        pkg.applicationInfo.uid = uid;
        pkg.applicationInfo.flags = flags;
        Shadows.shadowOf(context.getPackageManager()).installPackage(pkg);
    }
}
