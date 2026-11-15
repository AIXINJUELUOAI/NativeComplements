package dev.local.sunwindowdemo;

import org.junit.Test;
import static org.junit.Assert.*;

public class OpenWaitStateTest {
    private static OpenWaitState.Change show(OpenWaitState state,String token,String pkg){
        return state.update(true,true,true,true,token,pkg);
    }
    @Test public void missingFrameAndDisabledEnvironmentKeepCurrentRequest(){
        OpenWaitState state=new OpenWaitState();assertEquals(OpenWaitState.Change.SHOW,show(state,"one","example.one"));
        assertEquals(OpenWaitState.Change.KEEP,state.update(false,false,false,false,null,null));
        assertEquals(OpenWaitState.Change.KEEP,state.update(true,true,true,false,"two","example.two"));
        assertEquals("example.one",state.packageName());assertTrue(state.timeoutMatches("one"));assertFalse(state.timeoutMatches("two"));
        assertEquals(OpenWaitState.Change.KEEP,show(state,"one","example.changed"));assertEquals("example.one",state.packageName());
    }
    @Test public void invalidOrFinishedRequestHidesWithoutCancellingToken(){
        OpenWaitState state=new OpenWaitState();show(state,"one","example.one");
        assertEquals(OpenWaitState.Change.HIDE,state.update(true,true,false,false,null,null));assertNull(state.packageName());
        assertEquals(OpenWaitState.Change.SHOW,show(state,"one","example.one"));
        assertEquals(OpenWaitState.Change.HIDE,state.update(true,false,true,false,"one","example.one"));
        assertEquals(OpenWaitState.Change.SHOW,show(state,"one","example.one"));
        state.hide();assertEquals(OpenWaitState.Change.SHOW,show(state,"one","example.one"));
    }
    @Test public void cancelReturnsExactRequestOnceAndTombstoneSurvivesHideAndDifferentRequest(){
        OpenWaitState state=new OpenWaitState();show(state,"one","example.one");
        assertEquals(new OpenWaitState.Request("one","example.one"),state.cancel());assertNull(state.cancel());
        state.hide();assertEquals(OpenWaitState.Change.KEEP,show(state,"one","example.one"));
        assertEquals(OpenWaitState.Change.SHOW,show(state,"two","example.two"));
        assertEquals(OpenWaitState.Change.KEEP,show(state,"one","example.one"));assertEquals("example.two",state.packageName());
        assertEquals(new OpenWaitState.Request("two","example.two"),state.cancel());
        // The existing UI retains only the last cancelled token, not an unbounded token history.
        assertEquals(OpenWaitState.Change.SHOW,show(state,"one","example.one"));
    }
    @Test public void oldTimeoutCannotCancelNewTokenButSameTokenRecreatedKeepsExistingSemantics(){
        OpenWaitState state=new OpenWaitState();show(state,"one","example.one");show(state,"two","example.two");
        assertFalse(state.timeoutMatches("one"));assertTrue(state.timeoutMatches("two"));
        state.hide();assertFalse(state.timeoutMatches("two"));show(state,"two","example.two");
        assertTrue(state.timeoutMatches("two")); // No invented generation changes the historical token-based timeout.
    }
}
