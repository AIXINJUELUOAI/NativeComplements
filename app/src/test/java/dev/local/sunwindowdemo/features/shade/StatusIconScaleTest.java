package dev.local.sunwindowdemo.features.shade;

import org.junit.Test;
import static org.junit.Assert.*;

public class StatusIconScaleTest {
    @Test public void nativeDefaultAndSmallerSizesAreExact(){
        assertEquals(1,ShadePolicy.multiplier(100,18,18,24,24),0);
        assertEquals(.8f,ShadePolicy.multiplier(80,18,18,24,24),0);
        assertEquals(.8f,ShadePolicy.multiplier(-1,18,18,24,24),0);
    }
    @Test public void enlargementCannotExceedNativeContentViewport(){
        assertEquals(1.2f,ShadePolicy.multiplier(120,18,18,24,24),.00001f);
        assertEquals(20f/18,ShadePolicy.multiplier(120,18,18,24,20),.00001f);
        assertEquals(1,ShadePolicy.multiplier(120,30,18,24,24),0);
        assertEquals(1,ShadePolicy.multiplier(120,0,18,24,24),0);
        assertEquals(1,ShadePolicy.multiplier(120,Float.NaN,18,24,24),0);
    }
    @Test public void densityChangeKeepsSameNativeRatio(){
        assertEquals(ShadePolicy.multiplier(120,18,18,24,20),ShadePolicy.multiplier(120,36,36,48,40),0);
    }
    @Test public void systemControlsExcludeNetworkBatteryAndClock(){
        assertTrue(ShadePolicy.systemSlot("alarm_clock"));assertTrue(ShadePolicy.systemSlot("bluetooth"));
        assertTrue(ShadePolicy.systemSlot("hotspot"));
        for(String slot:new String[]{"mobile","wifi","battery","clock","no_calling","unknown"})assertFalse(slot,ShadePolicy.systemSlot(slot));
    }
}
