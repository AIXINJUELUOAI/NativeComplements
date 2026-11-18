package dev.local.sunwindowdemo;

import org.junit.Test;
import static org.junit.Assert.*;

public class WindowLifecycleTest {
    @Test public void tickAndPauseTableKeepHiddenSeparateAndPriorityAheadOfScreen(){
        for(int bits=0;bits<16;bits++){
            boolean managed=(bits&1)!=0,restoring=(bits&2)!=0,recovering=(bits&4)!=0,visible=(bits&8)!=0;
            assertEquals(!managed||restoring||recovering?WindowLifecycle.Tick.STOP:visible?WindowLifecycle.Tick.VISIBLE:WindowLifecycle.Tick.HIDDEN,
                WindowLifecycle.tick(managed,restoring,recovering,visible));
        }
        assertEquals(1000,WindowLifecycle.Pause.HIDDEN.delay);assertEquals(250,WindowLifecycle.pause(true,true).delay);
        assertEquals(WindowLifecycle.Pause.PRIORITY,WindowLifecycle.pause(true,true));assertEquals(500,WindowLifecycle.pause(false,true).delay);
        assertEquals(250,WindowLifecycle.pause(false,false).delay);
    }
    private static WindowLifecycle.Continuation continuation(int blocker,int uid){
        return new WindowLifecycle.Continuation(blocker!=0,blocker!=1,blocker!=2,blocker!=3,blocker==4,blocker==5,blocker==6,
            blocker==7,blocker==8,blocker!=9,blocker==10?"normal":"mini",uid);
    }
    @Test public void hiddenContinuationNeedsEveryOwnedMiniQualificationBeforeAnyLeaseRead(){
        assertTrue(WindowLifecycle.continuation(continuation(-1,10000)));
        assertFalse(WindowLifecycle.continuation(continuation(-1,9999)));
        for(int blocker=0;blocker<=10;blocker++)assertFalse("blocker "+blocker,WindowLifecycle.continuation(continuation(blocker,10000)));
    }
    @Test public void nativePriorityAndFocusKeepSameTaskPermissionDistinctFromExternalSecurity(){
        assertEquals(1,WindowLifecycle.priority(true,true,10001,10002,true));assertEquals(2,WindowLifecycle.priority(true,false,10001,10002,true));
        assertEquals(0,WindowLifecycle.priority(false,true,10001,10002,true));assertEquals(0,WindowLifecycle.priority(true,true,-1,10002,true));
        assertEquals(0,WindowLifecycle.priority(true,false,10001,-1,true));assertEquals(0,WindowLifecycle.priority(true,false,10001,10001,true));
        assertEquals(0,WindowLifecycle.priority(true,false,10001,10002,false));
        WindowLifecycle.Priority permission=WindowLifecycle.priority(1),external=WindowLifecycle.priority(2),released=WindowLifecycle.priority(0);
        assertTrue(permission.focusable(false,true,"mini"));assertFalse(external.focusable(true,false,"normal"));
        assertFalse(permission.changed(true,false));assertTrue(external.changed(true,false));
        assertTrue(released.focusable(true,false,"normal"));assertFalse(released.focusable(true,false,"mini"));assertFalse(released.focusable(true,true,"normal"));
    }
    @Test public void normalOnlyRisesAboveAnExistingHigherMini(){
        assertTrue(WindowLifecycle.raiseNormal(2,3));
        assertFalse(WindowLifecycle.raiseNormal(3,2));
        assertFalse(WindowLifecycle.raiseNormal(-1,2));
        assertFalse(WindowLifecycle.raiseNormal(2,-1));
    }
    @Test public void screenshotCallerIsSystemUiOrTheChooserRelayingIt(){
        assertTrue(WindowLifecycle.screenshotCaller(10100,10100,10100,true));
        assertTrue(WindowLifecycle.screenshotCaller(10100,1000,10100,true));
        assertFalse(WindowLifecycle.screenshotCaller(10100,10200,10100,true));
        assertFalse(WindowLifecycle.screenshotCaller(10100,1000,10200,true));
        assertFalse(WindowLifecycle.screenshotCaller(10100,10100,10100,false));
        assertFalse(WindowLifecycle.screenshotCaller(-1,-1,-1,true));
    }
    @Test public void screenshotFlowExcludesOrdinaryImageViewEditAndShare(){
        String edit="android.intent.action.EDIT",view="android.intent.action.VIEW";
        assertTrue(WindowLifecycle.screenshotIntent(edit,"image/png","content","screenshot"));
        assertTrue(WindowLifecycle.screenshotIntent(view,"image/png","content","screenshot"));
        assertFalse(WindowLifecycle.screenshotIntent(edit,"image/png","content",null));
        assertFalse(WindowLifecycle.screenshotIntent(edit,"image/jpeg","content","screenshot"));
        assertFalse(WindowLifecycle.screenshotIntent(view,"image/png","file","screenshot"));
        assertFalse(WindowLifecycle.screenshotIntent("android.intent.action.SEND","image/png","content","screenshot"));
    }
    @Test public void inputPermissionOverridePrecedesAllBlockedContentState(){
        assertEquals(WindowLifecycle.Input.ALLOW,WindowLifecycle.input(false,2));assertEquals(WindowLifecycle.Input.ALLOW,WindowLifecycle.input(true,1));
        assertEquals(WindowLifecycle.Input.BLOCK,WindowLifecycle.input(true,2));assertEquals(WindowLifecycle.Input.STATE,WindowLifecycle.input(true,0));
        for(int bits=0;bits<256;bits++){
            boolean parked=(bits&1)!=0,restoring=(bits&2)!=0,priority=(bits&4)!=0,screen=(bits&8)!=0,rotation=(bits&16)!=0,closing=(bits&32)!=0,mini=(bits&64)!=0,mask=(bits&128)!=0;
            assertEquals(bits!=0,WindowLifecycle.blocksContent(new WindowLifecycle.Content(parked,restoring,priority,screen,rotation,closing,mini?"mini":"normal",mask)));
        }
    }
    @Test public void visibleResumeTableNeverBypassesFinishingVisibilityOrAttachment(){
        assertTrue(WindowLifecycle.resumeCandidate(new WindowLifecycle.Resume(true,false,false,false,false,false)));
        for(int blocker=0;blocker<6;blocker++)assertFalse(WindowLifecycle.resumeCandidate(new WindowLifecycle.Resume(blocker!=0,blocker==1,blocker==2,blocker==3,blocker==4,blocker==5)));
        assertTrue(WindowLifecycle.retainTop(new WindowLifecycle.Top(true,false,true,true,0)));
        for(int blocker=0;blocker<5;blocker++)assertFalse(WindowLifecycle.retainTop(new WindowLifecycle.Top(blocker!=0,blocker==1,blocker!=2,blocker!=3,blocker==4?1:0)));
    }
    @Test public void miniResumeKeepsNativeTransitionBackAndPendingStartGates(){
        assertTrue(WindowLifecycle.resumeMini(true,true,"mini",false,false,false));
        for(int blocker=0;blocker<6;blocker++)assertFalse(WindowLifecycle.resumeMini(blocker!=0,blocker!=1,blocker==2?"normal":"mini",blocker==3,blocker==4,blocker==5));
        for(String state:new String[]{"PAUSED","STARTED","STOPPED","STOPPING"})assertTrue(WindowLifecycle.resumableState(state));
        for(String state:new String[]{"RESUMED","INITIALIZING","DESTROYED",null})assertFalse(WindowLifecycle.resumableState(state));
    }
}
