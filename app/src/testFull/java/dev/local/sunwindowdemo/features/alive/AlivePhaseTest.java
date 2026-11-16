package dev.local.sunwindowdemo.features.alive;

import org.junit.Test;
import static org.junit.Assert.*;

public class AlivePhaseTest {
    @Test public void wakeRetentionNeedsSuccessfulSleepAndNeverRenewsOrConsumesOnVisibility(){
        AlivePhase.SleepWindow sleep=new AlivePhase.SleepWindow();
        AlivePhase.WakeWindow wake=new AlivePhase.WakeWindow();
        assertFalse(wake.arm(100,true,sleep)); // Ordinary app hide/show has no successful sleep frame.
        sleep.begin(100);
        assertFalse(wake.arm(110,true,sleep));
        assertTrue(sleep.present(120));
        assertFalse(wake.arm(130,true,sleep)); // present() only requests a frame; it is not its GL acknowledgement.
        assertTrue(sleep.start(140));
        assertFalse(wake.arm(145,true,sleep)); // Historical success without same-renderer eligibility is insufficient.
        wake.presented(wake.epoch());
        assertFalse(wake.arm(149,false,sleep)); // Ordinary component/preview/display/surface/lock gates remain outside.
        wake.presented(wake.epoch());
        for(long repeated:new long[]{147,148,149}){ // ambient, PREPARE/system-sleep, SCREEN_OFF after first swap.
            boolean fresh=sleep.begin(repeated);
            assertFalse(fresh);
            if(fresh)wake.invalidate(); // Same service branch: repeats must preserve successful-renderer eligibility.
        }
        assertTrue(wake.arm(150,true,sleep));
        sleep.wake();
        assertEquals(2000,wake.remaining(150));
        assertTrue(wake.active(170)); // Initial native visible=true while ambient is still true does not consume it.
        assertTrue(wake.active(200)); // A subsequent hidden gap can still pause the existing scene.
        assertFalse(wake.arm(250,true,sleep)); // Repeated SCREEN_ON/ambient-exit cannot renew.
        assertEquals(1900,wake.remaining(250));
        assertTrue(wake.active(300)); // Native visibility restored: still available for another short gap.
        assertTrue(wake.active(2149));
        assertFalse(wake.active(2150));
        assertEquals(0,wake.remaining(2151));
        assertFalse(wake.arm(2151,true,sleep)); // Timeout never manufactures another successful sleep.
        assertTrue(sleep.begin(2200));wake.invalidate(); // Only a new episode clears retention/eligibility.
        assertFalse(wake.active(2200));
        assertFalse(wake.arm(2201,true,sleep));
        assertTrue(sleep.present(2210));assertTrue(sleep.start(2220));
        wake.presented(wake.epoch());
        assertTrue(wake.arm(2230,true,sleep));
        long oldEpoch=wake.epoch();
        wake.invalidate(); // Surface/reload invalidates only wake eligibility, not legitimate sleep progress.
        assertTrue(sleep.active(2240));assertTrue(sleep.holding(2240));
        wake.presented(oldEpoch); // A late old-renderer callback must not make the replacement eligible.
        assertFalse(wake.active(2240));
        assertFalse(wake.arm(2240,true,sleep));
        sleep.wake();sleep.begin(2300);
        long seedEpoch=wake.epoch();
        assertTrue(sleep.present(2310));
        wake.invalidate(); // First surface can arrive while noninteractive; original seeded sleep remains legal.
        assertTrue(sleep.active(2320));
        assertTrue(sleep.start(2330));wake.presented(seedEpoch);
        assertTrue(sleep.holding(2340));
        assertFalse(wake.arm(2340,true,sleep));
        sleep.end();wake.invalidate();
        assertFalse(sleep.begin(2350)); // An ended episode is not a fresh sleep, even if duplicate signals follow.
        assertFalse(wake.arm(2350,true,sleep));
    }
    @Test public void disabledOrUnknownAodNeverUsesAmbientButKeepsLockHomeAnimation(){
        for(boolean interactive:new boolean[]{false,true})for(boolean ambient:new boolean[]{false,true}){
            assertEquals(AlivePhase.LOCK,AlivePhase.phase(false,interactive,ambient,true,true));
            assertEquals(AlivePhase.HOME,AlivePhase.phase(false,interactive,ambient,false,false));
        }
        assertFalse(AlivePhase.animate(false,AlivePhase.AMBIENT,AlivePhase.LOCK));
        assertFalse(AlivePhase.animate(false,AlivePhase.HOME,AlivePhase.AMBIENT));
        assertTrue(AlivePhase.animate(false,AlivePhase.LOCK,AlivePhase.HOME));
        assertTrue(AlivePhase.animate(true,AlivePhase.AMBIENT,AlivePhase.LOCK));
        AlivePhase.SleepWindow sleep=new AlivePhase.SleepWindow();
        sleep.end(); // Observer cancels the old AOD policy while already awake.
        assertFalse(sleep.begin(100));
        sleep.wake(); // Actual ON + interactive + !ambient resets only the old sleep latch.
        assertTrue(sleep.begin(200));assertTrue(sleep.present(210));assertTrue(sleep.start(220));
    }
    @Test public void phaseUsesCurrentFactsAndNeverLatchesAnUnlockCommand(){
        for(int bits=0;bits<8;bits++){
            boolean interactive=(bits&1)!=0,ambient=(bits&2)!=0,locked=(bits&4)!=0;
            assertEquals(!interactive||ambient?AlivePhase.AMBIENT:locked?AlivePhase.LOCK:AlivePhase.HOME,
                AlivePhase.phase(interactive,ambient,locked));
        }
        assertEquals(0,AlivePhase.phase(false,false,true));
        assertEquals(1,AlivePhase.phase(true,false,true));
        assertEquals(2,AlivePhase.phase(true,false,false));
        assertEquals(1,AlivePhase.phase(true,false,true)); // Cancelled/relocked: no sticky HOME state.
        for(AlivePhase.DisplayState display:AlivePhase.DisplayState.values())for(int bits=0;bits<16;bits++){
            boolean own=(bits&1)!=0,visible=(bits&2)!=0,interactive=(bits&4)!=0,ambient=(bits&8)!=0;
            boolean expected=visible&&(display==AlivePhase.DisplayState.ON&&interactive
                ||(display==AlivePhase.DisplayState.DOZE||display==AlivePhase.DisplayState.DOZE_SUSPEND)&&own&&ambient);
            assertEquals(expected,AlivePhase.drawable(own,visible,interactive,ambient,display));
        }
        assertFalse(AlivePhase.drawable(true,true,false,true,AlivePhase.DisplayState.OFF));
        assertFalse(AlivePhase.drawable(false,true,false,true,AlivePhase.DisplayState.DOZE));
        assertTrue(AlivePhase.drawable(true,true,false,true,AlivePhase.DisplayState.DOZE));
        assertFalse(AlivePhase.drawable(true,false,false,true,AlivePhase.DisplayState.DOZE_SUSPEND));
        AlivePhase.SleepWindow sleep=new AlivePhase.SleepWindow();
        sleep.begin(100);
        assertTrue(sleep.active(100+AlivePhase.SLEEP_WAIT_MS-1));
        sleep.begin(800); // Repeated signals cannot extend the wait for native visibility.
        sleep.visibility(false,900); // Prepared ambient duration=0 may keep this bounded wait.
        assertFalse(sleep.holding(900)); // A waiting episode alone cannot admit a DOZE ACK.
        assertFalse(AlivePhase.drawable(true,false,false,true,AlivePhase.DisplayState.ON,sleep.active(900)));
        assertEquals(4300, sleep.remaining(1800));
        assertTrue(sleep.present(4200)); // Native late visibility still gets the full collapse.
        assertTrue(sleep.presented());
        assertFalse(sleep.present(4300));
        assertTrue(sleep.start(4400)); // Decode/first swap precedes the actual animation budget.
        assertFalse(sleep.start(4500));
        assertEquals(1700,sleep.remaining(4500));
        sleep.visibility(false,4500);
        assertTrue(sleep.active(6300));
        sleep.visibility(true,6300);
        assertEquals(1600,sleep.remaining(6300)); // Absolute handoff cap still bounds pauses.
        assertFalse(sleep.active(7900));
        assertTrue(AlivePhase.drawable(true,true,false,false,AlivePhase.DisplayState.ON,true));
        assertFalse(AlivePhase.drawable(false,true,false,false,AlivePhase.DisplayState.ON,true));
        assertFalse(AlivePhase.drawable(true,false,false,false,AlivePhase.DisplayState.ON,true));
        assertFalse(AlivePhase.drawable(true,true,false,true,AlivePhase.DisplayState.OFF,true));
        assertFalse(AlivePhase.drawable(true,true,false,true,AlivePhase.DisplayState.UNKNOWN,true));
        sleep.end();assertFalse(sleep.begin(2000)); // Zero-duration/suspend/end cannot be rearmed by duplicate sleep.
        assertFalse(sleep.active(2000));
        sleep.wake();sleep.begin(3000);
        assertTrue(sleep.active(3000));
        assertTrue(AlivePhase.drawable(true,true,false,false,AlivePhase.DisplayState.ON,sleep.active(3000)));
        assertFalse(AlivePhase.drawable(true,false,false,false,AlivePhase.DisplayState.ON,sleep.active(3100)));
        assertTrue(AlivePhase.drawable(true,true,false,false,AlivePhase.DisplayState.ON,sleep.active(3426)));
        sleep.wake();assertFalse(sleep.active(3500));
        sleep.begin(10_000);assertTrue(sleep.present(10_010));assertTrue(sleep.start(10_020));
        assertFalse(sleep.active(11_821));
        assertTrue(sleep.holding(11_821)); // Finished curve must not drop the buffer while native display remains ON.
        assertEquals(5_979,sleep.holdRemaining(11_821));
        assertFalse(sleep.holding(17_800)); // Same absolute cap, not another wait interval.
        sleep.end();assertFalse(sleep.holding(12_000));
        sleep.wake();assertFalse(sleep.holding(12_000));
    }
}
