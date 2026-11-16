package dev.local.sunwindowdemo.features.alive;

import org.junit.Test;
import static org.junit.Assert.*;

public class AliveTransitionTraceTest {
    @Test public void observationsKeepLatestValueWithoutPerFrameLogsAndHaveAFixedBudget(){
        AliveTransitionTrace.Edge edge=new AliveTransitionTrace.Edge();
        assertFalse(edge.sample(Float.NaN));
        assertTrue(edge.sample(0f));
        assertFalse(edge.sample(0f));
        assertTrue(edge.sample(.1f)); // First actual direction.
        for(int i=2;i<10;i++)assertFalse(edge.sample(i/10f));
        assertEquals(.9f,edge.last,0f);
        assertTrue(edge.sample(1f));
        assertTrue(edge.sample(.7f)); // Reversal, not every interpolated frame.
        assertFalse(edge.sample(.6f));
        assertTrue(edge.summary()); // A canceled transition can end between endpoints.
        assertEquals(.6f,edge.last,0f);
        assertFalse(edge.summary());
        for(int i=0;i<100;i++)edge.sample((i&1)==0?0f:1f);
        assertEquals(8,edge.count);
        assertEquals(1f,edge.last,0f); // Budget exhaustion does not lose the final sampled value.
        AliveTransitionTrace.Edge effects=new AliveTransitionTrace.Edge();
        assertTrue(effects.effect("PowerButtonReveal"));
        assertFalse(effects.effect("PowerButtonReveal"));
        assertTrue(effects.effect("LiftReveal"));
        assertTrue(AliveTransitionTrace.currentSnapshot("a","a",0));
        assertTrue(AliveTransitionTrace.currentSnapshot("a","a",100));
        assertFalse(AliveTransitionTrace.currentSnapshot("a","a",101));
        assertFalse(AliveTransitionTrace.currentSnapshot("a","a",-1));
        assertFalse(AliveTransitionTrace.currentSnapshot("a",null,1)); // Canceled while queued.
        assertFalse(AliveTransitionTrace.currentSnapshot("a","b",1)); // Another episode cannot inherit a queued step.
        assertFalse(AliveTransitionTrace.currentSnapshot(null,"a",1));
    }
}
