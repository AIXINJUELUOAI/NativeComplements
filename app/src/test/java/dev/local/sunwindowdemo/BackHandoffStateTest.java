package dev.local.sunwindowdemo;

import org.junit.Test;
import static org.junit.Assert.*;
import static dev.local.sunwindowdemo.BackHandoffState.Action.*;

public class BackHandoffStateTest {
    @Test public void nativeBusyRetriesAreBoundedAndCancellationIsOnce(){
        BackHandoffState state=new BackHandoffState(3);
        for(int attempt=0;attempt<40;attempt++){
            assertEquals(CHECK_NATIVE,state.check(true,3,attempt));
            assertEquals(IGNORE,state.check(true,3,attempt));
            assertEquals(WAIT_NATIVE,state.nativeState(true,true,true,3));
        }
        assertEquals(ABORT,state.check(true,3,40));
        assertFalse(state.cancel());assertEquals(IGNORE,state.check(true,3,41));
        assertEquals(IGNORE,state.idle(true,3));
    }
    @Test public void idleCallbackCanCommitOnlyOnceAndTimeoutCannotCancelSuccess(){
        BackHandoffState state=new BackHandoffState(3);
        assertEquals(CHECK_NATIVE,state.check(true,3,0));
        assertEquals(WAIT_IDLE,state.nativeState(false,true,true,3));
        assertEquals(COMMIT,state.idle(true,3));
        assertEquals(IGNORE,state.idle(true,3));
        assertEquals(COMMIT,state.commit(true,3));
        assertEquals(IGNORE,state.commit(true,3));
        state.completed();
        assertFalse(state.cancel());assertEquals(IGNORE,state.idleTimeout());
    }
    @Test public void timeoutInvalidatesLateIdleWithoutResurrectingTheTicket(){
        BackHandoffState state=new BackHandoffState(3);
        state.check(true,3,0);state.nativeState(false,true,true,3);
        assertEquals(ABORT,state.idleTimeout());
        assertEquals(IGNORE,state.idleTimeout());assertEquals(IGNORE,state.idle(true,3));
        assertEquals(IGNORE,state.commit(true,3));assertFalse(state.cancel());
    }
    @Test public void noControllerCommitIsAlsoOneShotAndSendingFailureCanCancelOnce(){
        BackHandoffState state=new BackHandoffState(3);
        state.check(true,3,0);
        assertEquals(COMMIT,state.nativeState(false,false,true,3));
        assertEquals(COMMIT,state.commit(true,3));
        assertTrue(state.cancel());assertFalse(state.cancel());
        assertEquals(IGNORE,state.commit(true,3));
    }
    @Test public void generationOrLeaseLossAtAnyAdmissionPointAbortsOldTicket(){
        for(boolean live:new boolean[]{true,false}){
            int generation=live?4:3;
            BackHandoffState start=new BackHandoffState(3);
            assertEquals(ABORT,start.check(live,generation,0));
            BackHandoffState nativeWait=new BackHandoffState(3);nativeWait.check(true,3,0);
            assertEquals(ABORT,nativeWait.nativeState(false,true,live,generation));
            BackHandoffState idleWait=new BackHandoffState(3);idleWait.check(true,3,0);idleWait.nativeState(false,true,true,3);
            assertEquals(ABORT,idleWait.idle(live,generation));
            BackHandoffState ready=new BackHandoffState(3);ready.check(true,3,0);ready.nativeState(false,false,true,3);
            assertEquals(ABORT,ready.commit(live,generation));
        }
    }
}
