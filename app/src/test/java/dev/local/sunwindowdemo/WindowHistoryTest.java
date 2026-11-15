package dev.local.sunwindowdemo;

import org.junit.Test;
import static org.junit.Assert.*;

public class WindowHistoryTest {
    private WindowHistory history(){
        WindowHistory history=new WindowHistory();history.windowed(new WindowHistory.Entry(1,0,"example.app","example.app/.Main",null));return history;
    }
    @Test public void stableObservationDoesNotRemoveHistoryBeforeNativeSnapshot(){
        WindowHistory history=history();assertFalse(history.observeFullscreen(1,true,1000));
        assertFalse(history.observeFullscreen(1,true,1119));assertTrue(history.observeFullscreen(1,true,1120));
        assertNotNull(history.get(1)); // Adapter records the real snapshot here, then commits release.
        assertTrue(history.fullscreenReady(1,false,true,true,true,false));history.clearFullscreen(1);assertNull(history.get(1));
    }
    @Test public void invalidFrameResetsStabilityAndTasksHaveIndependentClocks(){
        WindowHistory history=history();history.observeFullscreen(1,true,0);history.observeFullscreen(2,true,80);
        assertFalse(history.observeFullscreen(1,false,119));assertFalse(history.observeFullscreen(1,true,120));
        assertFalse(history.observeFullscreen(2,true,199));assertTrue(history.observeFullscreen(2,true,200));
        assertFalse(history.observeFullscreen(1,true,239));assertTrue(history.observeFullscreen(1,true,240));
    }
    @Test public void missingOrReusedRemovalKeepsOldSeenButExplicitReleaseClearsIt(){
        WindowHistory history=history();history.observeFullscreen(1,true,100);history.removed(1);
        history.windowed(new WindowHistory.Entry(1,0,"example.other","example.other/.Main",null));
        assertTrue(history.observeFullscreen(1,true,220)); // Preserve existing missing/reused pruning semantics.
        history.clearFullscreen(1);assertFalse(history.observeFullscreen(1,true,220));
    }
}
