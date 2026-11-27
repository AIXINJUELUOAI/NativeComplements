package dev.local.sunwindowdemo.features.callrefresh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class FloatingCallRefreshPolicyTest {
    @Test public void targetsOnlyTheTwoCallApplications() {
        assertTrue(FloatingCallRefreshPolicy.isCallApp("com.tencent.mobileqq"));
        assertTrue(FloatingCallRefreshPolicy.isCallApp("com.tencent.mm"));
        assertFalse(FloatingCallRefreshPolicy.isCallApp("com.tencent.mobileqq.other"));
        assertFalse(FloatingCallRefreshPolicy.isCallApp(null));
    }

    @Test public void clearsBothBoundsOfTheMatchedPackageRange() {
        assertEquals(0f, FloatingCallRefreshPolicy.adjust(60f, true, 60f, 60.000004f), 0f);
        assertEquals(0f, FloatingCallRefreshPolicy.adjust(60.000004f, false, 60f, 60.000004f), 0f);
        assertEquals(0f, FloatingCallRefreshPolicy.adjust(30f, true, 30f, 60f), 0f);
    }

    @Test public void preservesOtherOrInvalidVotes() {
        assertEquals(60f, FloatingCallRefreshPolicy.adjust(60f, true, 30f, 60f), 0f);
        assertEquals(48f, FloatingCallRefreshPolicy.adjust(48f, false, 48f, 48f), 0f);
        assertEquals(60f, FloatingCallRefreshPolicy.adjust(60f, false, 60f, Float.NaN), 0f);
        assertEquals(120f, FloatingCallRefreshPolicy.adjust(120f, false, 60f, 60f), 0f);
    }
}
