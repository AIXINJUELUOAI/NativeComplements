package dev.local.sunwindowdemo.features.shade;

import org.junit.Test;
import static org.junit.Assert.*;

public class StatusIconScaleTest {
    @Test public void nativeDefaultAndSmallerSizesAreExact(){
        assertEquals(1,StatusIconScale.multiplier(100,18,18,24,24),0);
        assertEquals(.8f,StatusIconScale.multiplier(80,18,18,24,24),0);
        assertEquals(.8f,StatusIconScale.multiplier(-1,18,18,24,24),0);
    }
    @Test public void enlargementCannotExceedNativeContentViewport(){
        assertEquals(1.2f,StatusIconScale.multiplier(120,18,18,24,24),.00001f);
        assertEquals(20f/18,StatusIconScale.multiplier(120,18,18,24,20),.00001f);
        assertEquals(1,StatusIconScale.multiplier(120,30,18,24,24),0);
        assertEquals(1,StatusIconScale.multiplier(120,0,18,24,24),0);
        assertEquals(1,StatusIconScale.multiplier(120,Float.NaN,18,24,24),0);
    }
    @Test public void densityChangeKeepsSameNativeRatio(){
        assertEquals(StatusIconScale.multiplier(120,18,18,24,20),StatusIconScale.multiplier(120,36,36,48,40),0);
    }
    @Test public void systemControlsExcludeNetworkBatteryAndClock(){
        assertTrue(StatusIconScale.systemSlot("alarm_clock"));assertTrue(StatusIconScale.systemSlot("bluetooth"));
        assertTrue(StatusIconScale.systemSlot("hotspot"));
        for(String slot:new String[]{"mobile","wifi","battery","clock","no_calling","unknown"})assertFalse(slot,StatusIconScale.systemSlot(slot));
    }
}
