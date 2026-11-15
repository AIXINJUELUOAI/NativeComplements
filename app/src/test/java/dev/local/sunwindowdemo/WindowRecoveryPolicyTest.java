package dev.local.sunwindowdemo;

import org.junit.Test;
import static org.junit.Assert.*;
import static dev.local.sunwindowdemo.WindowRecoveryPolicy.Eligibility.*;

public class WindowRecoveryPolicyTest {
    @Test public void bootIdentityTablePreservesLiveMissingAndLegacyBaseIdDifferences(){
        for(boolean live:new boolean[]{false,true})for(boolean ready:new boolean[]{false,true}){
            assertEquals(ready?RELEASE_STALE:live?COMPLETE:WAIT,WindowRecoveryPolicy.eligibility(live,ready,false,false,false,false));
            assertEquals(ready?RELEASE_STALE:REJECT,WindowRecoveryPolicy.eligibility(live,ready,true,false,true,true));
            assertEquals(live||ready?RESTORE:WAIT,WindowRecoveryPolicy.eligibility(live,ready,true,true,true,false));
            assertEquals(RESTORE,WindowRecoveryPolicy.eligibility(live,ready,true,true,true,true));
        }
        assertEquals(REJECT,WindowRecoveryPolicy.eligibility(true,false,true,true,false,true));
        assertEquals(RELEASE_STALE,WindowRecoveryPolicy.eligibility(true,true,true,true,false,true));
    }
    @Test public void retryStopsAfterOneHundredDelaysAndReadinessIsConsumedEvenBeforeExhaustion(){
        var retry=new WindowRecoveryPolicy.Retry();retry.begin();
        for(int i=1;i<=100;i++){assertTrue(retry.failed());assertEquals(i,retry.waits());}
        assertFalse(retry.failed());assertEquals(101,retry.waits());assertTrue(retry.needsReadinessCheck());
        assertTrue(retry.becameReady());assertEquals(0,retry.waits());assertFalse(retry.needsReadinessCheck());
        retry.begin();assertFalse(retry.becameReady());assertFalse(retry.needsReadinessCheck());
        for(int i=0;i<101;i++)retry.failed();
        assertFalse(retry.needsReadinessCheck()); // No second readiness retry in the same recovery.
        assertFalse(retry.becameReady());assertEquals(101,retry.waits());
        retry.resetWaits();assertFalse(retry.needsReadinessCheck());retry.begin();assertTrue(retry.needsReadinessCheck());
    }
}
