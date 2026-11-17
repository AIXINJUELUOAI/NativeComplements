package dev.local.sunwindowdemo.features.darkaod;

import org.junit.Test;
import static org.junit.Assert.*;

public class DarkAodHostTest {
    @Test public void darkAndProximityReasonsCannotReleaseEachOther() {
        assertTrue(DarkAodHost.wantsPause(true, "DOZE_AOD_PAUSED", "DOZE_AOD", false));
        assertTrue(DarkAodHost.wantsPause(true, "DOZE_AOD_PAUSING", "DOZE_AOD", true));
        assertFalse(DarkAodHost.wantsRestore(true, false, "DOZE_AOD_PAUSED", true));
        assertFalse(DarkAodHost.wantsRestore(true, false, "DOZE_AOD_PAUSED", null));
        assertFalse(DarkAodHost.wantsRestore(true, true, "DOZE_AOD_PAUSED", false));
        assertFalse(DarkAodHost.wantsRestore(false, false, "DOZE_AOD_PAUSED", false));
        assertTrue(DarkAodHost.wantsRestore(true, false, "DOZE_AOD_PAUSED", false));
    }

    @Test public void onlyOrdinaryAodAndCompletedPulseCanEnterDarkPause() {
        assertTrue(DarkAodHost.wantsPause(true, "DOZE_AOD", "DOZE_AOD", false));
        assertTrue(DarkAodHost.wantsPause(true, "DOZE_PULSE_DONE", "DOZE_AOD", false));
        assertTrue(DarkAodHost.wantsPause(true, "DOZE_PULSE_DONE", "DOZE_AOD", null, true));
        assertFalse(DarkAodHost.wantsPause(true, "DOZE_PULSE_DONE", "DOZE_AOD", null, false));
        assertFalse(DarkAodHost.wantsPause(true, "DOZE_AOD", "DOZE_AOD", null));
        assertFalse(DarkAodHost.wantsPause(true, "DOZE_AOD", "DOZE_AOD", true));
        for (String state : new String[]{"FINISH", "DOZE", "INITIALIZED", "DOZE_REQUEST_PULSE",
                "DOZE_PULSING", "DOZE_PULSING_AUTH_UI", "DOZE_AOD_DOCKED", "DOZE_AOD_MINMODE"}) {
            assertFalse(state, DarkAodHost.wantsPause(true, state, "DOZE_AOD", false));
            assertFalse(state, DarkAodHost.wantsRestore(true, false, state, false));
        }
        assertFalse(DarkAodHost.wantsPause(true, "DOZE_AOD", "FINISH", false));
        assertFalse(DarkAodHost.wantsPause(true, "DOZE_AOD", "DOZE_REQUEST_PULSE", false));
        assertFalse(DarkAodHost.wantsPause(false, "DOZE_AOD", "DOZE_AOD", false));
    }
}
