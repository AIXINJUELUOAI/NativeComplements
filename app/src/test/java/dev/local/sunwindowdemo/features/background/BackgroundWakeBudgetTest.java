package dev.local.sunwindowdemo.features.background;

import org.junit.Test;

import static org.junit.Assert.*;

public final class BackgroundWakeBudgetTest {
    @Test public void releasingAndReacquiringDoesNotResetBudget() {
        var budget = new BackgroundWakeBudget(new BackgroundStore.WakeLockRule(5, 3, 30), 0, 0);
        budget.holding(true, 0);
        assertEquals(8000, budget.deadline(0, true));
        budget.holding(false, 6000);
        budget.holding(true, 9000);
        assertFalse(budget.exhausted(10_999));
        assertTrue(budget.exhausted(11_000));
        budget.holding(false, 11_000);
        assertEquals(35_000, budget.deadline(11_000, true));
        assertFalse(budget.exhausted(35_000));
        budget.holding(true, 35_000);
        assertEquals(38_000, budget.deadline(35_000, true));
    }

    @Test public void continuousLockCarriesOnlyCurrentWindowUsageAcrossBoundary() {
        var budget = new BackgroundWakeBudget(new BackgroundStore.WakeLockRule(0, 3, 30), 0, 0);
        budget.holding(true, 29_500);
        assertEquals(30_000, budget.deadline(29_500, true));
        assertFalse(budget.exhausted(31_000));
        assertEquals(33_000, budget.deadline(31_000, true));
        assertEquals(Long.MAX_VALUE, budget.deadline(31_000, false));
        assertTrue(budget.exhausted(33_000));
    }
}
