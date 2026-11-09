package dev.local.sunwindowdemo;

import dev.local.sunwindowdemo.features.callblocking.CallBlockingRules;
import org.junit.Test;
import static org.junit.Assert.*;

public class CallBlockingRulesTest {
    @Test public void simSettingsAndNumberPatternsStayIndependent() {
        String[] first = {"952**"};
        String[] second = {"13800138000"};
        assertEquals("952*", CallBlockingRules.normalizePattern(" 952** "));
        assertTrue(CallBlockingRules.matches("952**", "+86 95212345"));
        assertTrue(CallBlockingRules.matches("952*", "952"));
        assertFalse(CallBlockingRules.matches("952", "9521"));
        assertTrue(CallBlockingRules.matches("13*8000", "13800138000"));
        assertFalse(CallBlockingRules.matches("952*", null));
        assertFalse(CallBlockingRules.decide(false, 1, false, first, false, true, second, "1").blocked);
        assertFalse(CallBlockingRules.decide(true, 0, false, first, false, true, second, "13800138000").blocked);
        assertTrue(CallBlockingRules.decide(true, 1, false, first, false, true, second, null).blocked);
        assertFalse(CallBlockingRules.decide(true, 1, false, first, true, true, second, "13800138000").blocked);
        assertTrue(CallBlockingRules.decide(true, 1, false, first, true, true, second, "9521234").blocked);
        assertTrue(CallBlockingRules.decide(true, 1, false, first, false, false, second, "13800138000").blocked);
        assertFalse(CallBlockingRules.decide(true, -1, true, first, false, true, second, "9521").blocked);
        assertFalse(CallBlockingRules.matches("9[0-9]*", "9521"));
    }
}
