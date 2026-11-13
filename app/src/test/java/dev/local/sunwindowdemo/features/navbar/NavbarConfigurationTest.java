package dev.local.sunwindowdemo.features.navbar;

import android.content.Context;
import android.os.Bundle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE)
public class NavbarConfigurationTest {
    @Test public void snapshotsKeepOnlyRequestedPackageAndClearOverrides() {
        Context context = RuntimeEnvironment.getApplication();
        NavbarStore.preferences(context).edit().clear().commit();
        NavbarStore.setDefaultMode(context, NavbarStore.COLOR);
        NavbarStore.setMode(context, "sample.one", "sample.one.Page", NavbarStore.FLOATING);
        NavbarStore.setMode(context, "sample.two", "sample.two.Page", NavbarStore.ORIGINAL);
        Bundle snapshot = NavbarStore.read(context, "sample.one");
        assertFalse(snapshot.getBoolean("enabled"));
        assertEquals(NavbarStore.COLOR, snapshot.getInt("*"));
        assertEquals(NavbarStore.FLOATING, snapshot.getInt("sample.one.Page"));
        assertFalse(snapshot.containsKey("sample.two.Page"));
        NavbarStore.clearMode(context, "sample.one", "sample.one.Page");
        assertFalse(NavbarStore.read(context, "sample.one").containsKey("sample.one.Page"));
        NavbarStore.setEnabled(context, true);
        assertTrue(NavbarStore.read(context, "sample.one").getBoolean("enabled"));
    }

}
