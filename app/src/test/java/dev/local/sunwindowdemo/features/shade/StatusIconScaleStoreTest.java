package dev.local.sunwindowdemo.features.shade;

import android.app.Application;
import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class StatusIconScaleStoreTest {
    private final Context context = RuntimeEnvironment.getApplication();

    @Before public void resetPreferences() {
        ShadeStore.preferences(context).edit().clear().commit();
    }

    @Test public void bothSidesDefaultToNativeOneHundredPercent() {
        assertEquals(100, ShadeStore.iconScale(context, true));
        assertEquals(100, ShadeStore.iconScale(context, false));
        assertEquals(100, ShadeStore.read(context).getInt("notificationIconScale"));
        assertEquals(100, ShadeStore.read(context).getInt("systemIconScale"));
    }

    @Test public void settersClampBothSidesAndReadersClampStoredValues() {
        for (boolean notification : new boolean[]{true, false}) {
            String key = notification ? "notification_icon_scale" : "system_icon_scale";
            ShadeStore.setIconScale(context, notification, Integer.MIN_VALUE);
            assertEquals(80, ShadeStore.iconScale(context, notification));
            assertEquals(80, ShadeStore.preferences(context).getInt(key, 0));
            ShadeStore.setIconScale(context, notification, Integer.MAX_VALUE);
            assertEquals(120, ShadeStore.iconScale(context, notification));
            assertEquals(120, ShadeStore.preferences(context).getInt(key, 0));
            ShadeStore.setIconScale(context, notification, 80);
            assertEquals(80, ShadeStore.iconScale(context, notification));
            ShadeStore.setIconScale(context, notification, 120);
            assertEquals(120, ShadeStore.iconScale(context, notification));
        }
        ShadeStore.preferences(context).edit().putInt("notification_icon_scale", 0)
                .putInt("system_icon_scale", 200).commit();
        assertEquals(80, ShadeStore.read(context).getInt("notificationIconScale"));
        assertEquals(120, ShadeStore.read(context).getInt("systemIconScale"));
    }

    @Test public void settingsPersistIndependentlyInDeviceProtectedStorageAndExport() {
        ShadeStore.setIconScale(context, true, 87);
        assertEquals(100, ShadeStore.iconScale(context, false));
        ShadeStore.setIconScale(context, false, 113);
        Context deviceProtected = context.createDeviceProtectedStorageContext();
        assertEquals(87, ShadeStore.iconScale(deviceProtected, true));
        assertEquals(113, ShadeStore.iconScale(deviceProtected, false));
        assertEquals(87, ShadeStore.read(deviceProtected).getInt("notificationIconScale"));
        assertEquals(113, ShadeStore.read(deviceProtected).getInt("systemIconScale"));
        assertFalse(context.getSharedPreferences("landscape_shade", Context.MODE_PRIVATE)
                .contains("notification_icon_scale"));
        assertFalse(context.getSharedPreferences("landscape_shade", Context.MODE_PRIVATE)
                .contains("system_icon_scale"));
        assertFalse(ShadeStore.read(context).getBoolean("enabled"));
    }

    @Test public void resetRestoresOnlyBothIconSizes() {
        ShadeStore.setEnabled(context, true);
        ShadeStore.setSignalDisplay(context, true);
        ShadeStore.setOffset(context, 9);
        ShadeStore.setIconScale(context, true, 80);
        ShadeStore.setIconScale(context, false, 120);
        ShadeStore.setIconScale(context, true, 100);
        ShadeStore.setIconScale(context, false, 100);
        assertEquals(100, ShadeStore.iconScale(context, true));
        assertEquals(100, ShadeStore.iconScale(context, false));
        assertEquals(9, ShadeStore.offset(context));
        assertTrue(ShadeStore.read(context).getBoolean("enabled"));
        assertTrue(ShadeStore.read(context).getBoolean("signalDisplay"));
    }

    @Test public void eachSetterNotifiesOnlySystemUiWithExistingChangedAction() {
        var broadcasts = Shadows.shadowOf((Application) context).getBroadcastIntents();
        int start = broadcasts.size();
        ShadeStore.setIconScale(context, true, 90);
        ShadeStore.setIconScale(context, false, 110);
        broadcasts = Shadows.shadowOf((Application) context).getBroadcastIntents();
        assertEquals(start + 2, broadcasts.size());
        for (int i = start; i < broadcasts.size(); i++) {
            assertEquals(ShadeStore.CHANGED, broadcasts.get(i).getAction());
            assertEquals(ShadeStore.SYSTEM_UI, broadcasts.get(i).getPackage());
        }
    }
}
