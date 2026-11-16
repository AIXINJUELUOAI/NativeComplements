package dev.local.sunwindowdemo.features.alive;

import org.junit.Test;
import static org.junit.Assert.*;

public class AlivePhaseTest {
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
        sleep.end();sleep.begin(2000); // Zero-duration/suspend/end cannot be rearmed by duplicate sleep.
        assertFalse(sleep.active(2000));
        sleep.wake();sleep.begin(3000);
        assertTrue(sleep.active(3000));
        assertTrue(AlivePhase.drawable(true,true,false,false,AlivePhase.DisplayState.ON,sleep.active(3000)));
        assertFalse(AlivePhase.drawable(true,false,false,false,AlivePhase.DisplayState.ON,sleep.active(3100)));
        assertTrue(AlivePhase.drawable(true,true,false,false,AlivePhase.DisplayState.ON,sleep.active(3426)));
        sleep.wake();assertFalse(sleep.active(3500));
    }
}
