package dev.local.sunwindowdemo.features.minusone;

import android.content.Context;
import android.os.UserManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class MinusOneSettingsTest {
    private final Context context = RuntimeEnvironment.getApplication();

    @Before public void reset() {
        context.getSharedPreferences("minus_one", 0).edit().clear().commit();
        context.createDeviceProtectedStorageContext().getSharedPreferences("minus_one_control", 0).edit().clear().commit();
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(true);
    }

    @Test public void migratesEnabledFlagAndKeepsNewValueAvailableBeforeUnlock() {
        context.getSharedPreferences("minus_one", 0).edit().putBoolean("enabled", true).commit();
        assertTrue(MinusOneSettings.enabled(context));
        assertTrue(context.createDeviceProtectedStorageContext().getSharedPreferences("minus_one_control", 0).contains("enabled"));
        MinusOneSettings.setEnabled(context, false);
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(false);
        assertFalse(MinusOneSettings.enabled(context));
        MinusOneSettings.setEnabled(context, true);
        assertTrue(MinusOneSettings.enabled(context));
    }

    @Test public void waitsForUnlockBeforeMigratingOldCredentialProtectedSettings() {
        context.getSharedPreferences("minus_one", 0).edit().putBoolean("enabled", true).commit();
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(false);
        assertFalse(MinusOneSettings.enabled(context));
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(true);
        assertTrue(MinusOneSettings.enabled(context));
    }
}
