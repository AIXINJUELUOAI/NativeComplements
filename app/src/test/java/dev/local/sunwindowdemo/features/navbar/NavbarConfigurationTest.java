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
        NavbarStore.setMode(context, "sample.one", "sample.one.Page", NavbarStore.FLOATING);
        NavbarStore.setMode(context, "sample.two", "sample.two.Page", NavbarStore.ORIGINAL);
        Bundle snapshot = NavbarStore.read(context, "sample.one");
        assertFalse(snapshot.getBoolean("enabled"));
        assertEquals(NavbarStore.AUTO, snapshot.getInt("*"));
        assertEquals(NavbarStore.FLOATING, snapshot.getInt("sample.one.Page"));
        assertFalse(snapshot.containsKey("sample.two.Page"));
        NavbarStore.clearMode(context, "sample.one", "sample.one.Page");
        assertFalse(NavbarStore.read(context, "sample.one").containsKey("sample.one.Page"));
        NavbarStore.setEnabled(context, true);
        assertTrue(NavbarStore.read(context, "sample.one").getBoolean("enabled"));
    }
    @Test public void snapshotsIgnoreButPreserveRetiredLearningAndLabelData() {
        Context context = RuntimeEnvironment.getApplication();
        var learned = context.createDeviceProtectedStorageContext().getSharedPreferences("navbar_learned", Context.MODE_PRIVATE);
        var labels = context.createDeviceProtectedStorageContext().getSharedPreferences("navbar_labels", Context.MODE_PRIVATE);
        learned.edit().putString("sample/sample.Page", "7,9,0,0").commit();
        labels.edit().putBoolean("multi:sample/sample.Page", true).commit();
        var learnedBefore = new java.util.HashMap<>(learned.getAll());
        var labelsBefore = new java.util.HashMap<>(labels.getAll());
        try {
            NavbarStore.preferences(context).edit().clear().commit();
            NavbarStore.setEnabled(context, true);
            assertFalse(NavbarStore.systemSnapshot(context).containsKey("learned"));
            assertFalse(NavbarStore.read(context, "sample").containsKey("learned"));
            assertEquals(NavbarStore.AUTO, new NavbarRules(NavbarStore.systemSnapshot(context))
                    .resolve("sample", "sample.Page", 7).mode());
            NavbarStore.label(context, "sample", "sample.Page", NavbarStore.FLOATING);
            assertEquals(learnedBefore, learned.getAll());
            assertEquals(labelsBefore, labels.getAll());
        } finally {
            learned.edit().clear().commit();
            labels.edit().clear().commit();
            NavbarStore.preferences(context).edit().clear().commit();
        }
    }

    @Test public void ownSnapshotExposesLabelModeWithoutOtherRuntimeSettings() {
        Context context = RuntimeEnvironment.getApplication();
        NavbarStore.preferences(context).edit().clear().commit();
        assertFalse(NavbarStore.read(context, "sample").getBoolean("label_mode"));
        NavbarStore.setLabelMode(context, true);
        NavbarStore.setLandscapeClear(context, false);
        Bundle snapshot = NavbarStore.read(context, "sample");
        assertTrue(snapshot.getBoolean("label_mode"));
        assertFalse(snapshot.containsKey("landscape_clear"));
        NavbarStore.setLabelMode(context, false);
        assertFalse(NavbarStore.read(context, "sample").getBoolean("label_mode"));
        NavbarStore.preferences(context).edit().clear().commit();
    }
}
