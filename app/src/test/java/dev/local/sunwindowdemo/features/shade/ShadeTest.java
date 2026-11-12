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

    @Test public void deoptimizationIncludesIndependentQsLayoutAndOtherNativeConsumers() {
        var callers = java.util.Arrays.stream(LandscapeShade.CALLERS)
                .map(value -> value[0] + "#" + value[1]).collect(java.util.stream.Collectors.toSet());
        assertEquals(11, callers.size());
        assertTrue(callers.contains("shade.QuickSettingsControllerImpl#updateResources"));
        assertTrue(callers.contains("shade.NotificationsQSContainerController#updateResources"));
        assertTrue(callers.contains("statusbar.notification.stack.NotificationStackScrollLayout#updateSplitNotificationShade"));
        assertTrue(callers.stream().anyMatch(value -> value.endsWith("#emit")));
        assertEquals(5, LandscapeShade.CONSTRUCTORS.length);
    }

    @Test public void composeResourceOverrideIsScopedAndRestoresNativeOutsideForcedLandscape() {
        assertEquals(true, ComposeShadeLayout.overrideBoolean(false, 1, true, true, 1, 2));
        assertEquals(false, ComposeShadeLayout.overrideBoolean(true, 2, true, true, 1, 2));
        assertEquals(false, ComposeShadeLayout.overrideBoolean(false, 1, false, true, 1, 2));
        assertEquals(true, ComposeShadeLayout.overrideBoolean(true, 2, true, false, 1, 2));
        assertEquals(false, ComposeShadeLayout.overrideBoolean(false, 3, true, true, 1, 2));
    }

    @Test public void composeIdsComeFromHostResourcesWithoutRuntimeRClass() throws Exception {
        var split = ComposeShadeLayout.class.getDeclaredField("splitId"); split.setAccessible(true);
        var full = ComposeShadeLayout.class.getDeclaredField("fullWidthId"); full.setAccessible(true);
        var base = context.getResources();
        var host = new android.content.res.Resources(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration()) {
            @Override public int getIdentifier(String name, String kind, String pkg) {
                assertEquals("bool", kind); assertEquals("com.android.systemui", pkg);
                return switch (name) {
                    case "config_use_split_notification_shade" -> 101;
                    case "config_isFullWidthShade" -> 102;
                    default -> 0;
                };
            }
        };
        try {
            split.setInt(null, 0); full.setInt(null, 0);
            ComposeShadeLayout.resolveResourceIds(host);
            assertEquals(101, split.getInt(null)); assertEquals(102, full.getInt(null));
            split.setInt(null, 0); full.setInt(null, 0);
            assertThrows(android.content.res.Resources.NotFoundException.class,
                    () -> ComposeShadeLayout.resolveResourceIds(base));
            assertEquals(0, split.getInt(null)); assertEquals(0, full.getInt(null));
        } finally { split.setInt(null, 0); full.setInt(null, 0); }
    }

    public static class ConfigCallback {
        Configuration last;
        int calls;
        public void onConfigChanged(Configuration value) { last = value; calls++; }
    }
    @Test public void composeRefreshUsesNativeRepositoryCallbackAndUnmodifiedConfiguration() throws Exception {
        var type = ComposeShadeLayout.class;
        var refs = type.getDeclaredField("callbacks"); refs.setAccessible(true);
        var method = type.getDeclaredField("onConfigChanged"); method.setAccessible(true);
        var list = (java.util.List<java.lang.ref.WeakReference<Object>>) refs.get(null);
        ConfigCallback callback = new ConfigCallback();
        Configuration real = new Configuration();
        real.orientation = Configuration.ORIENTATION_LANDSCAPE; real.screenWidthDp = 874;
        real.densityDpi = 489;
        try {
            method.set(null, ConfigCallback.class.getDeclaredMethod("onConfigChanged", Configuration.class));
            list.add(new java.lang.ref.WeakReference<>(callback));
            ComposeShadeLayout.refresh(real);
            assertEquals(1, callback.calls);
            assertNotSame(real, callback.last);
            assertEquals(real, callback.last);
        } finally { list.clear(); method.set(null, null); }
    }

    @Test public void statusBarOffsetClampsToThirtyTwoAndStaysIndependentFromSplitShade() {
        ShadeStore.setOffset(context, 40);
        assertEquals(32, ShadeStore.offset(context));
        assertFalse("offset must not enable split shade", ShadeStore.read(context).getBoolean("enabled"));
        assertEquals(32, ShadeStore.read(context).getInt("statusBarOffset"));
        ShadeStore.setOffset(context, -40);
        assertEquals(-32, ShadeStore.offset(context));
        ShadeStore.setOffset(context, 0);
        assertEquals(0, ShadeStore.read(context).getInt("statusBarOffset", -1));
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
