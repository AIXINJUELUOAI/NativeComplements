package dev.local.sunwindowdemo;

import dev.local.sunwindowdemo.features.darkaod.DarkAodPolicy;
import org.junit.Test;
import static org.junit.Assert.*;

public class DarkAodPolicyTest {
    @Test public void darknessNeedsTenSecondsAndBrightnessNeedsTwo() {
        DarkAodPolicy p = new DarkAodPolicy();
        p.sample(0, 0);
        assertEquals(-1, p.deadline());
        p.configure(true, 1, 5);
        p.sample(0, 100);
        p.sample(0.8f, 200);
        assertEquals(10_100, p.deadline());
        p.tick(10_099);
        assertFalse(p.isDark());
        p.tick(10_100);
        assertTrue(p.isDark());
        p.sample(8, 11_000);
        p.tick(12_999);
        assertTrue(p.isDark());
        p.tick(13_000);
        assertFalse(p.isDark());
    }

    @Test public void middleBandCancelsPendingButPreservesCommittedState() {
        DarkAodPolicy p = new DarkAodPolicy();
        p.configure(true, 1, 5);
        p.sample(0, 0);
        p.sample(2, 9_999);
        p.tick(10_000);
        assertFalse(p.isDark());
        assertEquals(-1, p.deadline());
        p.sample(1, 20_000);
        p.tick(30_000);
        p.sample(5, 31_000);
        p.sample(3, 32_000);
        p.tick(33_000);
        assertTrue(p.isDark());
        assertEquals(-1, p.deadline());
    }

    @Test public void invalidInputDisableAndSessionResetReleaseDarkCause() {
        DarkAodPolicy p = new DarkAodPolicy();
        p.configure(true, 1, 5);
        p.sample(0, 0);
        p.tick(10_000);
        p.configure(true, 1, 5); // idempotent reload must not restart debounce
        assertTrue(p.isDark());
        p.sample(Float.NaN, 11_000);
        assertFalse(p.isDark());
        p.sample(0, 12_000);
        p.configure(false, 1, 5);
        p.tick(22_000);
        assertFalse(p.isDark());
        assertEquals(-1, p.deadline());
        p.configure(true, 5, 5); // missing hysteresis is not a usable config
        p.sample(0, 23_000);
        assertEquals(-1, p.deadline());
        p.configure(true, 1, 5);
        p.sample(0, 24_000);
        p.reset();
        p.tick(40_000);
        assertFalse(p.isDark());
        assertEquals(-1, p.deadline());
    }
}
