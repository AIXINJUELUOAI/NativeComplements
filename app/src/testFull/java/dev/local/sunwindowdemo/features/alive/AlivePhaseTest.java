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
    }
}
