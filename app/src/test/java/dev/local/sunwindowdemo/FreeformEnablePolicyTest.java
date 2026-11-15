package dev.local.sunwindowdemo;

import org.junit.Test;
import static org.junit.Assert.*;
import static dev.local.sunwindowdemo.FreeformEnablePolicy.*;

public class FreeformEnablePolicyTest {
    private final Capability ready = new Capability(true, true, true, true, false);
    @Test public void requiresSettingAndCompleteKnownRuntimeWithoutConflict() {
        assertEquals(Decision.ALLOWED, evaluate(1, ready));
        assertEquals(Decision.SETTING_UNKNOWN, evaluate(null, ready));
        assertEquals(Decision.SETTING_REQUIRED, evaluate(0, ready));
        assertEquals(Decision.SETTING_REQUIRED, evaluate(2, ready));
        assertEquals(Decision.BACKEND_UNKNOWN, evaluate(1, null));
        assertEquals(Decision.BACKEND_UNKNOWN, evaluate(1, new Capability(false, true, true, true, false)));
        assertEquals(Decision.RUNTIME_UNAVAILABLE, evaluate(1, new Capability(true, false, true, true, false)));
        assertEquals(Decision.RUNTIME_UNAVAILABLE, evaluate(1, new Capability(true, true, false, true, false)));
        assertEquals(Decision.PREFLIGHT_FAILED, evaluate(1, new Capability(true, true, true, false, false)));
        assertEquals(Decision.OWNERSHIP_CONFLICT, evaluate(1, new Capability(true, true, true, true, true)));
        // A losing backend can truthfully report ownership conflict without owning/probing native capability.
        assertEquals(Decision.OWNERSHIP_CONFLICT, evaluate(1, new Capability(false, false, false, false, true)));
    }
    @Test public void onlyFreshMatchingTrustedStatusCanCompleteOneRequest() {
        assertTrue(matchesStatus(1000, 16, "STATUS", "new", "new", 100, 2100, 2000));
        assertFalse(matchesStatus(1001, 16, "STATUS", "new", "new", 100, 101, 2000));
        assertFalse(matchesStatus(1000, 15, "STATUS", "new", "new", 100, 101, 2000));
        assertFalse(matchesStatus(1000, 16, "ACTIVE", "new", "new", 100, 101, 2000));
        assertFalse(matchesStatus(1000, 16, "STATUS", "new", "old", 100, 101, 2000));
        assertFalse(matchesStatus(1000, 16, "STATUS", "new", null, 100, 101, 2000));
        assertFalse(matchesStatus(1000, 16, "STATUS", null, "new", 100, 101, 2000));
        assertFalse(matchesStatus(1000, 16, "STATUS", "new", "new", 100, 99, 2000));
        assertFalse(matchesStatus(1000, 16, "STATUS", "new", "new", 100, 2101, 2000));
    }
}
