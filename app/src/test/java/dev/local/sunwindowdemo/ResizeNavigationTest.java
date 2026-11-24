package dev.local.sunwindowdemo;

import org.junit.Test;
import static org.junit.Assert.*;

public class ResizeNavigationTest {
    private final ResizeNavigation resize=new ResizeNavigation();
    private final Geometry.Placement normal=new Geometry.Placement(500,900,.75f,40,80);
    private final Geometry.Placement expanded=new Geometry.Placement(500,900,1,0,0);

    @Test public void nativeStartRollsBackTheSameResizeAfterItsRelease(){
        resize.track(100,normal);
        resize.track(100,expanded);

        assertEquals(normal,resize.start());
        assertFalse(resize.accepts(100));
        assertFalse(resize.accepts(101));
        resize.finish(110);
        assertFalse(resize.accepts(100));
        assertFalse(resize.accepts(110));
        assertTrue(resize.accepts(111));
    }

    @Test public void startingNavigationBeforeTheFirstMoveStillInvalidatesTheRelease(){
        assertNull(resize.start());
        resize.finish(110);

        assertFalse(resize.accepts(100));
        assertTrue(resize.accepts(111));
    }

    @Test public void touchCancellationInvalidatesPendingMovesEvenIfNoneReachedTheBackend(){
        assertNull(resize.cancel(100));
        assertFalse(resize.accepts(100));
        resize.track(101,normal);
        assertFalse(resize.accepts(100));
        assertEquals(normal,resize.cancel(101));
        assertFalse(resize.accepts(101));
    }

    @Test public void upwardAdmissionLeavesNativeQuickSwitchAngleAndDownwardMovementAlone(){
        assertTrue(DefaultWindowNavigation.upward(0,-20));
        assertTrue(DefaultWindowNavigation.upward(20,-20));
        assertFalse(DefaultWindowNavigation.upward(100,-20));
        assertFalse(DefaultWindowNavigation.upward(-100,-20));
        assertFalse(DefaultWindowNavigation.upward(0,20));
        assertFalse(DefaultWindowNavigation.upward(0,0));
    }

    @Test public void finishedResizeIsNeverRolledBack(){
        resize.track(100,normal);
        resize.complete();

        assertFalse(resize.accepts(100));
        assertNull(resize.start());
    }
}
