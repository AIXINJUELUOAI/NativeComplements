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
    }
}
