package dev.local.sunwindowdemo;

import org.junit.Test;
import static org.junit.Assert.*;

public class LaunchCoverFlowTest {
    @Test public void intermediatePagesRemainMaskedUntilTheLatestTargetStabilizes(){
        LaunchCoverFlow flow=new LaunchCoverFlow(0);
        assertFalse(flow.ready(true,100));
        flow.changed(748);
        assertFalse(flow.ready(true,800));
        assertFalse(flow.ready(true,1799));
        assertTrue(flow.ready(true,1800));
        flow.finish();assertFalse(flow.active());
    }
    @Test public void invalidDrawingRestartsTheStabilityInterval(){
        LaunchCoverFlow flow=new LaunchCoverFlow(0);
        assertFalse(flow.ready(true,100));
        assertFalse(flow.ready(false,900));
        assertFalse(flow.ready(true,1000));
        assertFalse(flow.ready(true,1999));
        assertTrue(flow.ready(true,2000));
    }
    @Test public void repeatedRedirectsCannotExtendTheAbsoluteTimeout(){
        LaunchCoverFlow flow=new LaunchCoverFlow(100);
        flow.changed(4900);
        assertFalse(flow.ready(true,5000));
        assertTrue(flow.timedOut(5100));assertFalse(flow.continuing(5100));
        assertTrue(flow.ready(true,5100));
        assertFalse(flow.ready(false,5101));
    }
    @Test public void screenOffPausesTheQuietIntervalAndTimeout(){
        LaunchCoverFlow flow=new LaunchCoverFlow(0);
        flow.ready(true,100);flow.visibility(false,200);
        flow.changed(5000);
        flow.visibility(true,10_200);
        assertFalse(flow.timedOut(10_200));
        assertFalse(flow.ready(true,10_200));
        assertFalse(flow.ready(true,11_199));
        assertTrue(flow.ready(true,11_200));
    }
}
