package dev.local.sunwindowdemo.features.navbar;

import android.content.pm.ProviderInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE)
public class NavbarProviderTest {
    private NavbarProvider provider() {
        NavbarProvider provider = new NavbarProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = NavbarStore.AUTHORITY;
        provider.attachInfo(RuntimeEnvironment.getApplication(), info);
        return provider;
    }
    @Test(expected = SecurityException.class) public void ordinaryUidCannotReadSystemSnapshot() {
        ShadowBinder.setCallingUid(99999);
        provider().call("system", null, null);
    }
    @Test public void systemSnapshotDoesNotMigrateOrOverwriteSavedSettings() {
        var context = RuntimeEnvironment.getApplication();
        var preferences = NavbarStore.preferences(context);
        preferences.edit().clear().putBoolean("enabled", true).putInt("default_mode", 0)
                .putInt("com.tencent.mm/*", 2)
                .putInt("com.tencent.mm/com.tencent.mm.framework.app.UIPageFragmentActivity", 0).commit();
        var before = new java.util.HashMap<>(preferences.getAll());
        try {
            ShadowBinder.setCallingUid(1000);
            var snapshot = provider().call("system", null, null);
            assertEquals(0, snapshot.getInt("default_mode"));
            assertEquals(2, snapshot.getInt("com.tencent.mm/*"));
            assertEquals(0, snapshot.getInt("com.tencent.mm/com.tencent.mm.framework.app.UIPageFragmentActivity"));
            assertEquals(before, preferences.getAll());
        } finally { preferences.edit().clear().commit(); }
    }
    @Test public void systemUidCanReadTemplateSnapshot() {
        ShadowBinder.setCallingUid(1000);
        var snapshot = provider().call("system", null, null);
        assertTrue(snapshot.getString("templates").contains("类原生补全计划适配名单"));
        assertTrue(snapshot.getString("templates").contains("ColorOS"));
    }
}
