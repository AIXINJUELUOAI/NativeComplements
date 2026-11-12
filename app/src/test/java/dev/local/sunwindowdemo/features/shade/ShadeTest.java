package dev.local.sunwindowdemo.features.shade;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.content.res.Configuration;
import android.os.Process;
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
@Config(sdk = 35)
public class ShadeTest {
    private final Context context = RuntimeEnvironment.getApplication();
    @Before public void reset() { ShadeStore.preferences(context).edit().clear().commit(); }
    @After public void restoreUid() { ShadowBinder.setCallingUid(Process.myUid()); }

    @Test public void onlyEnabledLandscapeAtLeast600dpOverridesNative() {
        Configuration c = new Configuration();
        c.orientation = Configuration.ORIENTATION_LANDSCAPE;
        c.screenWidthDp = 600;
        assertTrue(LandscapeShade.forceSplit(true, c));
        assertFalse(LandscapeShade.forceSplit(false, c));
        c.screenWidthDp = 599;
        assertFalse(LandscapeShade.forceSplit(true, c));
        c.screenWidthDp = 900;
        c.orientation = Configuration.ORIENTATION_PORTRAIT;
        assertFalse(LandscapeShade.forceSplit(true, c));
        c.orientation = Configuration.ORIENTATION_UNDEFINED;
        assertFalse(LandscapeShade.forceSplit(true, c));
    }

    @Test public void settingsDefaultOffAndUseDeviceProtectedStorage() {
        assertFalse(ShadeStore.read(context).getBoolean("enabled"));
        ShadeStore.setEnabled(context, true);
        assertTrue(ShadeStore.read(context.createDeviceProtectedStorageContext()).getBoolean("enabled"));
        assertFalse(context.getSharedPreferences("landscape_shade", Context.MODE_PRIVATE).getBoolean("enabled", false));
        ShadeStore.setEnabled(context, false);
        assertFalse(ShadeStore.read(context).getBoolean("enabled"));
        var intent = Shadows.shadowOf((android.app.Application) context).getBroadcastIntents().get(0);
        assertEquals(ShadeStore.CHANGED, intent.getAction());
        assertEquals(ShadeStore.SYSTEM_UI, intent.getPackage());
    }

    public static class Panel {
        int updates;
        boolean fail;
        public void updateResources() {
            assertEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper());
            updates++;
            if (fail) throw new IllegalStateException("native reconfiguration failed");
        }
    }

    @Test public void hotRefreshUsesExistingPanelAndFailureDisablesOverride() throws Exception {
        var type = LandscapeShade.class;
        var method = type.getDeclaredField("updateResources"); method.setAccessible(true);
        var ready = type.getDeclaredField("ready"); ready.setAccessible(true);
        var enabled = type.getDeclaredField("enabled"); enabled.setAccessible(true);
        var refs = type.getDeclaredField("panels"); refs.setAccessible(true);
        var main = type.getDeclaredField("main"); main.setAccessible(true);
        var remember = type.getDeclaredMethod("remember", Object.class); remember.setAccessible(true);
        var refresh = type.getDeclaredMethod("refresh"); refresh.setAccessible(true);
        Panel panel = new Panel();
        try {
            method.set(null, Panel.class.getDeclaredMethod("updateResources"));
            main.set(null, new android.os.Handler(android.os.Looper.getMainLooper()));
            ready.setBoolean(null, true); enabled.setBoolean(null, true);
            remember.invoke(null, panel); remember.invoke(null, panel);
            refresh.invoke(null);
            assertEquals(1, panel.updates); // weak identity tracking must not duplicate calls
            Panel broken = new Panel();
            broken.fail = true;
            remember.invoke(null, broken);
            refresh.invoke(null);
            assertEquals(3, panel.updates); // success followed by native restore after another panel fails
            assertEquals(2, broken.updates); // one attempt, one best-effort native restore
            assertFalse(ready.getBoolean(null));
            assertFalse(enabled.getBoolean(null));
        } finally {
            ((java.util.List<?>) refs.get(null)).clear();
            method.set(null, null); main.set(null, null);
            ready.setBoolean(null, false); enabled.setBoolean(null, false);
        }
    }

    @Test public void providerAllowsOnlyOwnAndActualSystemUiUidAndNeverWrites() {
        PackageInfo systemUi = new PackageInfo();
        systemUi.packageName = ShadeStore.SYSTEM_UI;
        systemUi.applicationInfo = new ApplicationInfo();
        systemUi.applicationInfo.packageName = ShadeStore.SYSTEM_UI;
        systemUi.applicationInfo.uid = 10042;
        Shadows.shadowOf(context.getPackageManager()).installPackage(systemUi);
        ShadeProvider provider = new ShadeProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = ShadeStore.AUTHORITY;
        provider.attachInfo(context, info);
        ShadowBinder.setCallingUid(context.getApplicationInfo().uid);
        assertFalse(provider.call("read", null, null).getBoolean("enabled"));
        ShadowBinder.setCallingUid(10042);
        assertFalse(provider.call("read", null, null).getBoolean("enabled"));
        assertThrows(IllegalArgumentException.class, () -> provider.call("write", null, null));
        ShadowBinder.setCallingUid(12345);
        assertThrows(SecurityException.class, () -> provider.call("read", null, null));
        assertThrows(UnsupportedOperationException.class, () -> provider.update(null, null, null, null));
    }
}
