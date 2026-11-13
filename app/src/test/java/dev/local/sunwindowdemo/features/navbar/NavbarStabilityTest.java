package dev.local.sunwindowdemo.features.navbar;

import org.junit.Test;
import static org.junit.Assert.*;

public class NavbarStabilityTest {
    @Test public void matchingEdgesTakePriorityOverMovingCenter() {
        assertEquals(0xffeeeeee, NavbarColors.background(new int[]{0xffeeeeee, 0xff000000, 0xff000000, 0xffeeeeee}));
    }
    @Test public void smallColorChangesAreIgnored() {
        NavbarColors.Stability stability = new NavbarColors.Stability();
        assertEquals(0xffeeeeee, stability.choose(0xffeeeeee));
        assertEquals(0xffeeeeee, stability.choose(0xffe4e4e4));
        assertFalse(stability.needsConfirmation());
    }
    @Test public void transientCandidatesKeepExistingBackground() {
        NavbarColors.Stability stability = new NavbarColors.Stability();
        stability.choose(0xffffffff);
        assertEquals(0xffffffff, stability.choose(0xff123456));
        assertEquals(0xffffffff, stability.choose(0xff654321));
        assertTrue(stability.needsConfirmation());
        assertEquals(0xff654321, stability.choose(0xff654321));
        assertFalse(stability.needsConfirmation());
    }
    @Test public void restoringWindowClearsPreviousPageColor() {
        NavbarColors.Stability stability = new NavbarColors.Stability();
        stability.choose(0xffffffff);
        stability.choose(0xff000000);
        stability.reset();
        assertEquals(0xff123456, stability.choose(0xff123456));
        assertFalse(stability.needsConfirmation());
    }
}
