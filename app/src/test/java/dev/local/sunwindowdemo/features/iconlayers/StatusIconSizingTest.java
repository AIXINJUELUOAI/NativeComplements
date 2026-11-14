package dev.local.sunwindowdemo.features.iconlayers;

import org.junit.Test;
import static org.junit.Assert.*;

public class StatusIconSizingTest {
    @Test public void visualTargetIsAbsoluteNotAccumulatedAndClampedToSlot() {
        assertEquals(.75f,StatusIconSizing.scale(18,24,24,24,24,.5f),0);
        assertEquals(.75f,StatusIconSizing.scale(18,24,24,24,24,.75f),0);
        assertEquals(.5f,StatusIconSizing.scale(18,24,24,12,24,1),0);
        assertEquals(.5f,StatusIconSizing.scale(18,24,24,24,12,1),0);
    }
    @Test public void wideSystemViewportCannotEscapeItsNativeSlot() {
        assertEquals(.6f,StatusIconSizing.scale(14,30,20,18,24,1),.0001f);
    }
    @Test public void unknownRulerPreservesNativeScale() {
        for(float target:new float[]{0,-1,Float.NaN,Float.POSITIVE_INFINITY})
            assertEquals(.6f,StatusIconSizing.scale(target,24,24,24,24,.6f),0);
        assertEquals(.6f,StatusIconSizing.scale(18,0,0,24,24,.6f),0);
    }
}
