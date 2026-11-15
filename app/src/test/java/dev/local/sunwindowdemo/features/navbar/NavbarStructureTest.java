package dev.local.sunwindowdemo.features.navbar;

import android.os.Binder;
import android.os.Bundle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE)
public class NavbarStructureTest {
    private Bundle report(Binder token) {
        Bundle value = new Bundle();
        value.putBinder("window", token); value.putLong("sampledAt", 1000);
        value.putString("container", "滚动内容区"); value.putString("node", "GridView");
        value.putString("reason", "底边滚动容器"); value.putString("bounds", "[0,1200][1000,1920]");
        value.putInt("suggested", NavbarStore.FLOATING); value.putInt("nodes", 12); value.putLong("nanos", 1000000);
        return value;
    }
    @Test public void reportBelongsToExactWindowAndExpiresWithoutLearning() {
        Binder token = new Binder();
        NavbarStructure value = NavbarStructure.read(report(token), 1000);
        assertNotNull(value);
        assertTrue(value.matches(token, 2500));
        assertFalse(value.matches(token, 2501));
        assertFalse(value.matches(new Binder(), 1000));
        assertFalse(value.matches(token, 999));
        assertTrue(value.summary().contains("1.00ms"));
        assertEquals(NavbarStore.FLOATING, value.mode);
    }
    @Test public void malformedMissingOrFutureReportsFailClosed() {
        Binder token = new Binder(); Bundle value = report(token);
        value.putString("node", null); assertNull(NavbarStructure.read(value, 1000));
        value = report(token); value.putInt("nodes", 1000); assertNull(NavbarStructure.read(value, 1000));
        value = report(token); value.putInt("suggested", NavbarStore.AUTO); assertNull(NavbarStructure.read(value, 1000));
        value = report(token); value.putString("container", "unknown enum"); assertNull(NavbarStructure.read(value, 1000));
        assertNull(NavbarStructure.read(report(token), 999));
        assertNull(NavbarStructure.read(report(token), 2501));
        assertNull(NavbarStructure.read(Bundle.EMPTY, 1000));
    }
}
