package dev.local.sunwindowdemo.features.navbar;

import android.provider.Settings;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE)
public class NavbarNavigationModeTest {
    @Test public void systemSettingTakesPriorityOverEarlyContextResources() {
        var context = RuntimeEnvironment.getApplication();
        Settings.Secure.putInt(context.getContentResolver(), "navigation_mode", 2);
        assertEquals(2, NavbarSystem.navigationMode(context));
        Settings.Secure.putInt(context.getContentResolver(), "navigation_mode", 0);
        assertEquals(0, NavbarSystem.navigationMode(context));
    }
    @Test public void missingSettingFallsBackToPlatformResource() {
        var context = RuntimeEnvironment.getApplication();
        Settings.Secure.putString(context.getContentResolver(), "navigation_mode", null);
        int id = context.getResources().getIdentifier("config_navBarInteractionMode", "integer", "android");
        assertEquals(id == 0 ? -1 : context.getResources().getInteger(id), NavbarSystem.navigationMode(context));
    }
}
