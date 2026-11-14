package dev.local.sunwindowdemo.features.navbar;

import android.graphics.Insets;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE)
public class NavbarAppPolicyTest {
    @Test public void layoutCopyDoesNotPersistFlagsOrChangeStatusPolicy() {
        WindowManager.LayoutParams original = new WindowManager.LayoutParams();
        original.flags = WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        original.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        original.type = WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
        WindowManager.LayoutParams copy = NavbarApp.layoutAttributes(original);
        assertNotSame(original, copy);
        assertEquals(WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS, original.flags);
        assertEquals(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR, original.systemUiVisibility);
        assertEquals(original.flags | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION, copy.flags);
        assertEquals(original.systemUiVisibility | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION, copy.systemUiVisibility);
        assertEquals(original.type, copy.type);
    }

    @Test public void consumptionOnlyClearsNavigationAndKeepsRawInsets() {
        WindowInsets raw = new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, 30, 0, 0))
                .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 60))
                .setInsetsIgnoringVisibility(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 60))
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 400)).build();
        WindowInsets consumed = NavbarApp.consumeNavigation(raw);
        assertEquals(Insets.NONE, consumed.getInsets(WindowInsets.Type.navigationBars()));
        assertEquals(Insets.NONE, consumed.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()));
        assertEquals(raw.getInsets(WindowInsets.Type.statusBars()), consumed.getInsets(WindowInsets.Type.statusBars()));
        assertEquals(raw.getInsets(WindowInsets.Type.ime()), consumed.getInsets(WindowInsets.Type.ime()));
        assertEquals(60, raw.getInsets(WindowInsets.Type.navigationBars()).bottom);
    }

    @Test public void policyFourDoesNotConsumeSixAndExplicitFloatingDo() {
        assertFalse(new NavbarRules.Choice(NavbarStore.FLOATING, null, "template", 4).consumesNavigationInsets());
        assertTrue(new NavbarRules.Choice(NavbarStore.FLOATING, null, "template", 6).consumesNavigationInsets());
        assertTrue(new NavbarRules.Choice(NavbarStore.FLOATING, null, "user", 0).consumesNavigationInsets());
        Bundle disabled = new Bundle();
        disabled.putInt("example.app/*", NavbarStore.FLOATING);
        assertEquals(NavbarStore.ORIGINAL, new NavbarRules(disabled).resolve("example.app", "example.app.Page", 1).mode());
    }

    @Test public void safetyConditionsFailClosed() {
        assertTrue(NavbarApp.eligible(true, true, true, false, false, 0, 1));
        assertFalse(NavbarApp.eligible(false, true, true, false, false, 0, 1));
        assertFalse(NavbarApp.eligible(true, false, true, false, false, 0, 1));
        assertFalse(NavbarApp.eligible(true, true, false, false, false, 0, 1));
        assertFalse(NavbarApp.eligible(true, true, true, true, false, 0, 1));
        assertFalse(NavbarApp.eligible(true, true, true, false, true, 0, 1));
        assertFalse(NavbarApp.eligible(true, true, true, false, false, 1, 1));
        assertFalse(NavbarApp.eligible(true, true, true, false, false, 0, 2));
    }
}
